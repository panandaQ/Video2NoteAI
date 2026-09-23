import { computed, ref } from 'vue'
import { knowledgeApi, newRequestId } from '../api/endpoints'
import { useAuthStore } from '../stores/auth'
import { createSseClient, type SseClient } from './sseClient'
import {
  useDraftPersistence,
  type KnowledgePersistence,
  type PendingQuestion
} from './useDraftPersistence'
import type {
  KnowledgeConversation as KnowledgeConversationHead,
  KnowledgeQuestionAccepted,
  KnowledgeQuestionRequest,
  KnowledgeTurn
} from '../types/knowledge'

/**
 * 单视频连续追问核心状态机（Spec §4.4.4）：
 * - 提交：先写 pending 指针 → POST（首问带 scope，追问带 conversationId）→ 202 → 挂 SSE；
 * - POST 结果未知 → 以相同 requestId 重试（服务端幂等收敛）；
 * - SSE 终态 → GET 单轮真源渲染 → 清 pending；断线只重连原 turnId，不重新提交；
 * - 刷新恢复：IndexedDB 先渲染 → pending 恢复 → 服务端版本校正。
 */
export interface UseKnowledgeConversationOptions {
  persistence?: KnowledgePersistence
}

export function useKnowledgeConversation(mediaId: number, options: UseKnowledgeConversationOptions = {}) {
  const auth = useAuthStore()
  const persistence =
    options.persistence ?? useDraftPersistence(computed(() => auth.user?.id ?? null))

  const conversationId = ref<number | null>(null)
  const version = ref(0)
  const turns = ref<KnowledgeTurn[]>([])
  const activeTurnId = ref<number | null>(null)
  const busy = ref(false)
  const submitError = ref('')
  const restored = ref(false)

  let stream: SseClient | null = null

  function refreshBusy() {
    busy.value = turns.value.some((turn) => turn.status === 'PROCESSING')
  }

  function replaceTurn(turn: KnowledgeTurn) {
    const index = turns.value.findIndex((item) => item.turnId === turn.turnId)
    if (index >= 0) {
      const next = [...turns.value]
      next[index] = turn
      turns.value = next
    } else {
      turns.value = [...turns.value, turn]
    }
    if (turn.status !== 'PROCESSING' && activeTurnId.value === turn.turnId) {
      activeTurnId.value = null
    }
  }

  async function applyAccepted(accepted: KnowledgeQuestionAccepted, question: string) {
    if (conversationId.value === null) {
      conversationId.value = accepted.conversationId
    }
    version.value = Math.max(version.value, accepted.conversationVersion)
    await persistence.setConversationRef(
      mediaId,
      accepted.conversationId,
      version.value,
      accepted.turnNo
    )
    // 202 后回填真实身份：崩溃恢复时可按 turnId 直接查单轮真源
    await persistence.setPending({
      requestId: accepted.requestId,
      question,
      conversationId: accepted.conversationId,
      turnId: accepted.turnId,
      mediaId
    })
    const placeholder: KnowledgeTurn = {
      turnId: accepted.turnId,
      turnNo: accepted.turnNo,
      requestId: accepted.requestId,
      question,
      rewrittenQuery: null,
      status: 'PROCESSING',
      answerMode: null,
      answer: null,
      errorCode: null,
      createdAt: new Date().toISOString(),
      completedAt: null,
      evidence: []
    }
    replaceTurn(placeholder)
    activeTurnId.value = accepted.turnId
    refreshBusy()
  }

  function watchTurn(turnId: number, requestId: string) {
    const cid = conversationId.value
    if (cid === null) return
    stream?.stop()
    stream = createSseClient(knowledgeApi.eventsUrl(cid, turnId), {
      onTerminal: () => {
        void settleTurn(turnId, requestId)
      },
      onFatal: () => {
        void settleTurn(turnId, requestId)
      }
    })
    stream.start()
  }

  async function settleTurn(turnId: number, requestId: string) {
    const cid = conversationId.value
    if (cid === null) return
    try {
      const turn = await knowledgeApi.getTurn(cid, turnId)
      replaceTurn(turn)
      const pending = await persistence.getPending()
      if (pending?.requestId === requestId && turn.status !== 'PROCESSING') {
        await persistence.clearPending()
      }
      if (turn.status === 'PROCESSING') {
        // SSE 断开时服务端仍在处理：重新挂监听，不重新提交
        watchTurn(turnId, requestId)
        return
      }
      await persistServerHead(cid)
      stream?.stop()
      stream = null
    } catch {
      // 轮次不存在：指针已失效，丢弃占位轮
      turns.value = turns.value.filter((turn) => turn.turnId !== turnId)
      if (activeTurnId.value === turnId) activeTurnId.value = null
      const pending = await persistence.getPending()
      if (pending?.requestId === requestId) await persistence.clearPending()
    } finally {
      refreshBusy()
    }
  }

  function isUnknownOutcome(err: unknown): boolean {
    return err instanceof Error && !('status' in err)
  }

  function handleBusinessError(err: unknown): string {
    const error = err as Error & { status?: number }
    if (error.status === 503) return '当前排队已满，请稍后再试'
    if (error.status === 409 && turns.value.some((turn) => turn.status === 'PROCESSING')) {
      return '已有问题在处理中，请等待本轮完成'
    }
    return error.message
  }

  async function submit(question: string): Promise<void> {
    const text = question.trim()
    if (!text) return
    if (busy.value) {
      submitError.value = '上一轮回答仍在生成中，请稍候'
      return
    }
    submitError.value = ''

    const requestId = newRequestId()
    const payload: KnowledgeQuestionRequest =
      conversationId.value === null
        ? { requestId, question: text, scope: { type: 'SINGLE_VIDEO', mediaId } }
        : { requestId, conversationId: conversationId.value, question: text }

    await persistence.setPending({
      requestId,
      question: text,
      conversationId: conversationId.value,
      turnId: null,
      mediaId
    })

    try {
      const accepted = await knowledgeApi.ask(payload)
      await applyAccepted(accepted, text)
      watchTurn(accepted.turnId, requestId)
    } catch (firstError) {
      if (isUnknownOutcome(firstError)) {
        // POST 结果未知：同一 requestId 重试，服务端幂等收敛（runbook §8/§10）
        try {
          const accepted = await knowledgeApi.ask(payload)
          await applyAccepted(accepted, text)
          watchTurn(accepted.turnId, requestId)
        } catch (retryError) {
          if (isUnknownOutcome(retryError)) {
            submitError.value = '网络不稳定，提交状态未知；刷新页面可恢复最近一次提问'
            // pending 保留：刷新后按指针恢复（可能已受理也可能未受理，交由幂等收敛）
          } else {
            await persistence.clearPending()
            submitError.value = handleBusinessError(retryError)
            refreshBusy()
          }
        }
        return
      }
      await persistence.clearPending()
      submitError.value = handleBusinessError(firstError)
      refreshBusy()
    }
  }

  async function regenerate(turnId: number) {
    const turn = turns.value.find((item) => item.turnId === turnId)
    if (!turn || busy.value) return
    await submit(turn.question)
  }

  async function newConversation() {
    stream?.stop()
    stream = null
    conversationId.value = null
    version.value = 0
    turns.value = []
    activeTurnId.value = null
    submitError.value = ''
    await persistence.clearPending()
    await persistence.clearConversationRef(mediaId)
    refreshBusy()
  }

  /** 在同一工作台切换到一个既有会话；先完整读取真源，成功后再替换当前界面。 */
  async function openConversation(targetConversationId: number) {
    if (targetConversationId === conversationId.value || busy.value) return
    const head = await knowledgeApi.getConversation(targetConversationId)
    if (head.scopeType !== 'SINGLE_VIDEO' || head.scopeMediaId !== mediaId) {
      throw new Error('该会话不属于当前视频')
    }
    const serverTurns = await knowledgeApi.getTurns(targetConversationId, {
      limit: 50,
      knownVersion: head.version
    })

    stream?.stop()
    stream = null
    conversationId.value = head.conversationId
    version.value = head.version
    turns.value = serverTurns
    activeTurnId.value = serverTurns.find((turn) => turn.status === 'PROCESSING')?.turnId ?? null
    submitError.value = ''
    refreshBusy()
    await persistence.saveConversation(head, serverTurns)
    await persistence.setConversationRef(
      mediaId,
      head.conversationId,
      head.version,
      head.lastTurnNo
    )
  }

  async function persistServerHead(cid: number): Promise<void> {
    try {
      const head = await knowledgeApi.getConversation(cid, { knownVersion: version.value })
      if (head.scopeMediaId !== mediaId || head.version < version.value) return
      version.value = head.version
      await persistence.saveConversation(head, turns.value)
      await persistence.setConversationRef(mediaId, cid, head.version, head.lastTurnNo)
    } catch {
      // 单轮真源已经成功渲染；缓存写回失败或会话头暂不可用不影响正确性。
    }
  }

  async function correctFromServer(
    cid: number,
    knownVersion: number,
    forceTurns = false
  ): Promise<KnowledgeConversationHead | null> {
    const head = await knowledgeApi.getConversation(cid, { knownVersion })
    if (head.scopeMediaId !== mediaId) {
      await persistence.removeConversation(cid)
      await persistence.clearConversationRef(mediaId)
      return null
    }
    if (head.version < version.value) return null

    const shouldLoadTurns = forceTurns || conversationId.value === null || head.version !== version.value
    const serverTurns = shouldLoadTurns
      ? await knowledgeApi.getTurns(cid, { limit: 10, knownVersion: knownVersion })
      : turns.value
    conversationId.value = head.conversationId
    version.value = head.version
    turns.value = serverTurns
    refreshBusy()
    await persistence.saveConversation(head, serverTurns)
    return head
  }

  async function recoverAcceptedPending(pending: PendingQuestion): Promise<boolean> {
    if (pending.conversationId === null || pending.turnId === null) return false
    try {
      const turn = await knowledgeApi.getTurn(pending.conversationId, pending.turnId)
      conversationId.value = pending.conversationId
      await correctFromServer(pending.conversationId, version.value, true)
      replaceTurn(turn)
      refreshBusy()
      if (turn.status === 'PROCESSING') watchTurn(turn.turnId, pending.requestId)
      else await persistence.clearPending()
      return true
    } catch {
      await persistence.clearPending()
      return false
    }
  }

  async function recoverUnknownSubmission(pending: PendingQuestion): Promise<boolean> {
    if (!pending.question.trim()) {
      await persistence.clearPending()
      return false
    }
    const payload: KnowledgeQuestionRequest =
      pending.conversationId === null
        ? {
            requestId: pending.requestId,
            question: pending.question,
            scope: { type: 'SINGLE_VIDEO', mediaId }
          }
        : {
            requestId: pending.requestId,
            conversationId: pending.conversationId,
            question: pending.question
          }
    try {
      const accepted = await knowledgeApi.ask(payload)
      await applyAccepted(accepted, pending.question)
      watchTurn(accepted.turnId, pending.requestId)
      return true
    } catch (error) {
      if (isUnknownOutcome(error)) {
        submitError.value = '网络仍不稳定，已保留上次提问，稍后刷新会继续恢复'
      } else {
        await persistence.clearPending()
        submitError.value = handleBusinessError(error)
      }
      return false
    }
  }

  /** 页面加载恢复：设备缓存先渲染，再由 pending 与服务端版本校正。 */
  async function restore() {
    if (restored.value) return
    restored.value = true

    const cached = await persistence.getCachedConversation(mediaId)
    if (cached) {
      conversationId.value = cached.head.conversationId
      version.value = cached.head.version
      turns.value = cached.turns
      refreshBusy()
    }

    const pending = await persistence.getPending()
    if (pending?.mediaId === mediaId) {
      if (pending.conversationId !== null && pending.turnId !== null) {
        const recovered = await recoverAcceptedPending(pending)
        if (recovered) return
      } else {
        const recovered = await recoverUnknownSubmission(pending)
        if (recovered) return
      }
    }

    const reference = await persistence.getConversationRef(mediaId)
    const refId = cached?.head.conversationId ?? reference?.conversationId ?? null
    if (refId !== null) {
      try {
        await correctFromServer(refId, cached?.head.version ?? reference?.version ?? 0, cached === null)
      } catch {
        if (cached) await persistence.removeConversation(cached.head.conversationId)
        await persistence.clearConversationRef(mediaId)
      }
    }
  }

  function dispose() {
    stream?.stop()
    stream = null
  }

  return {
    mediaId,
    conversationId,
    version,
    turns,
    activeTurnId,
    busy,
    submitError,
    restored,
    submit,
    regenerate,
    newConversation,
    openConversation,
    restore,
    dispose,
    getDraft: () => persistence.getDraft(mediaId),
    setDraft: (text: string) => persistence.setDraft(mediaId, text)
  }
}

export type KnowledgeConversation = ReturnType<typeof useKnowledgeConversation>
