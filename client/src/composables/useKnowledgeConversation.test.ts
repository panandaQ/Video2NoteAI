import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useKnowledgeConversation } from './useKnowledgeConversation'
import { useDraftPersistence, type KnowledgePersistence } from './useDraftPersistence'
import { KnowledgeMemoryService } from '../storage/knowledgeMemory'
import { MemoryKnowledgeStorageDriver } from '../storage/knowledgeStorageDriver'
import type { KnowledgeConversation, KnowledgeQuestionAccepted, KnowledgeTurn } from '../types/knowledge'
import type { SseHandlers } from './sseClient'

const mocks = vi.hoisted(() => {
  const knowledgeApi = {
    ask: vi.fn(),
    getConversation: vi.fn(),
    getTurns: vi.fn(),
    getTurn: vi.fn(),
    eventsUrl: vi.fn(() => '/events')
  }
  const sseState: { handlers: SseHandlers | undefined } = { handlers: undefined }
  const createSseClient = vi.fn((_url: string, handlers: SseHandlers) => {
    sseState.handlers = handlers
    return { start: vi.fn(), stop: vi.fn() }
  })
  const newRequestId = vi.fn(() => 'r-1')
  return { knowledgeApi, sseState, createSseClient, newRequestId }
})

vi.mock('../api/endpoints', () => ({
  knowledgeApi: mocks.knowledgeApi,
  newRequestId: mocks.newRequestId
}))

vi.mock('./sseClient', () => ({
  createSseClient: mocks.createSseClient
}))

function makeAccepted(overrides: Partial<KnowledgeQuestionAccepted> = {}): KnowledgeQuestionAccepted {
  return {
    conversationId: 91,
    turnId: 314,
    turnNo: 1,
    requestId: 'r-1',
    status: 'PROCESSING',
    conversationVersion: 0,
    eventsUrl: '/events',
    ...overrides
  }
}

function makeTurn(id: number, status: KnowledgeTurn['status'], overrides: Partial<KnowledgeTurn> = {}): KnowledgeTurn {
  return {
    turnId: id,
    turnNo: id === 314 ? 1 : 2,
    requestId: `req-${id}`,
    question: '测试问题',
    rewrittenQuery: null,
    status,
    answerMode: status === 'COMPLETED' ? 'VIDEO_GROUNDED' : null,
    answer: status === 'COMPLETED' ? '答案 [E1]' : null,
    errorCode: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    completedAt: status === 'COMPLETED' ? '2026-01-01T00:00:01.000Z' : null,
    evidence: [],
    ...overrides
  }
}

function makeHead(overrides: Partial<KnowledgeConversation> = {}): KnowledgeConversation {
  return {
    conversationId: 91,
    scopeType: 'SINGLE_VIDEO',
    scopeMediaId: 27,
    title: '缓存视频',
    status: 'ACTIVE',
    version: 1,
    lastTurnNo: 1,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    ...overrides
  }
}

let memory: KnowledgeMemoryService
let persistence: KnowledgePersistence

function createConversation() {
  return useKnowledgeConversation(27, { persistence })
}

beforeEach(async () => {
  localStorage.clear()
  vi.resetAllMocks()
  setActivePinia(createPinia())
  await useAuthStore().setSession('tok-1', { id: 1, nickname: '测试' })
  memory = new KnowledgeMemoryService(new MemoryKnowledgeStorageDriver())
  persistence = useDraftPersistence(computed(() => 1), memory)
  mocks.sseState.handlers = undefined
  mocks.createSseClient.mockImplementation((_url: string, handlers: SseHandlers) => {
    mocks.sseState.handlers = handlers
    return { start: vi.fn(), stop: vi.fn() }
  })
  mocks.newRequestId.mockReturnValue('r-1')
  mocks.knowledgeApi.eventsUrl.mockReturnValue('/events')
  mocks.knowledgeApi.getConversation.mockResolvedValue(makeHead())
  mocks.knowledgeApi.getTurns.mockResolvedValue([])
})

describe('useKnowledgeConversation', () => {
  it('首问：先写 pending，POST 带 scope，202 后追加 PROCESSING 轮并挂 SSE', async () => {
    mocks.knowledgeApi.ask.mockResolvedValue(makeAccepted())
    const conversation = createConversation()

    await conversation.submit('视频里如何避免缓存击穿？')

    expect(mocks.knowledgeApi.ask).toHaveBeenCalledWith({
      requestId: 'r-1',
      question: '视频里如何避免缓存击穿？',
      scope: { type: 'SINGLE_VIDEO', mediaId: 27 }
    })
    expect(conversation.conversationId.value).toBe(91)
    expect(conversation.turns.value).toHaveLength(1)
    expect(conversation.turns.value[0]?.status).toBe('PROCESSING')
    expect(conversation.busy.value).toBe(true)
    expect(await persistence.getPending()).toMatchObject({
      requestId: 'r-1',
      question: '视频里如何避免缓存击穿？',
      conversationId: 91,
      turnId: 314,
      mediaId: 27
    })
    expect(mocks.createSseClient).toHaveBeenCalledOnce()
    expect(await persistence.getConversationRef(27)).toEqual({ conversationId: 91, version: 0 })
  })

  it('追问：携带 conversationId，不再带 scope', async () => {
    mocks.newRequestId.mockReturnValueOnce('r-1').mockReturnValueOnce('r-2')
    mocks.knowledgeApi.ask
      .mockResolvedValueOnce(makeAccepted())
      .mockResolvedValueOnce(makeAccepted({ turnId: 315, turnNo: 2, requestId: 'r-2' }))

    const conversation = createConversation()
    await conversation.submit('第一个问题')
    // 先收敛首轮，释放执行权（同会话一次只允许一个问题处理中）
    mocks.knowledgeApi.getTurn.mockResolvedValue(makeTurn(314, 'COMPLETED'))
    await mocks.sseState.handlers?.onTerminal?.({ state: 'COMPLETED' })
    await vi.waitFor(() => expect(conversation.busy.value).toBe(false))

    await conversation.submit('第二个问题')

    expect(mocks.knowledgeApi.ask).toHaveBeenLastCalledWith({
      requestId: 'r-2',
      conversationId: 91,
      question: '第二个问题'
    })
  })

  it('SSE 终态：GET 单轮真源替换轮次、清 pending、busy 收敛', async () => {
    mocks.knowledgeApi.ask.mockResolvedValue(makeAccepted())
    const conversation = createConversation()
    await conversation.submit('问题')

    mocks.knowledgeApi.getTurn.mockResolvedValue(makeTurn(314, 'COMPLETED'))
    await mocks.sseState.handlers?.onTerminal?.({ state: 'COMPLETED' })

    await vi.waitFor(() => expect(conversation.turns.value[0]?.status).toBe('COMPLETED'))
    expect(mocks.knowledgeApi.getTurn).toHaveBeenCalledWith(91, 314)
    await vi.waitFor(async () => expect(await persistence.getPending()).toBeNull())
    expect(conversation.busy.value).toBe(false)
    expect(conversation.activeTurnId.value).toBeNull()
  })

  it('POST 结果未知：以相同 requestId 重试，服务端幂等收敛', async () => {
    mocks.knowledgeApi.ask
      .mockRejectedValueOnce(new Error('无法连接后端服务，请确认后端已启动且地址配置正确'))
      .mockResolvedValueOnce(makeAccepted())

    const conversation = createConversation()
    await conversation.submit('问题')

    expect(mocks.knowledgeApi.ask).toHaveBeenCalledTimes(2)
    const secondPayload = mocks.knowledgeApi.ask.mock.calls[1]?.[0] as { requestId: string }
    expect(secondPayload.requestId).toBe('r-1')
    expect(conversation.busy.value).toBe(true)
    // pending 保留（已被受理，等待 SSE 终态）
    expect(await persistence.getPending()).toMatchObject({ requestId: 'r-1', question: '问题' })
  })

  it('重试仍失败：保留 pending 供刷新恢复，提示用户', async () => {
    mocks.knowledgeApi.ask.mockRejectedValue(new Error('无法连接后端服务，请确认后端已启动且地址配置正确'))

    const conversation = createConversation()
    await conversation.submit('问题')

    expect(mocks.knowledgeApi.ask).toHaveBeenCalledTimes(2)
    expect(conversation.submitError.value).toContain('刷新页面可恢复')
    expect(await persistence.getPending()).toMatchObject({ requestId: 'r-1', question: '问题' })
    expect(conversation.busy.value).toBe(false)
  })

  it('409 业务冲突：清 pending，展示服务端 message', async () => {
    const conflict = new Error('当前会话已有问题在处理中') as Error & { status?: number }
    conflict.status = 409
    mocks.knowledgeApi.ask.mockRejectedValueOnce(conflict)

    const conversation = createConversation()
    await conversation.submit('问题')

    expect(mocks.knowledgeApi.ask).toHaveBeenCalledTimes(1)
    expect(conversation.submitError.value).toBe('当前会话已有问题在处理中')
    expect(await persistence.getPending()).toBeNull()
  })

  it('刷新恢复（pending 指向 PROCESSING 轮次）：重建并继续监听', async () => {
    await persistence.setPending({
      requestId: 'r-1',
      question: '测试问题',
      conversationId: 91,
      turnId: 314,
      mediaId: 27
    })
    const processing = makeTurn(314, 'PROCESSING')
    mocks.knowledgeApi.getTurn.mockResolvedValue(processing)
    mocks.knowledgeApi.getTurns.mockResolvedValue([processing])

    const conversation = createConversation()
    await conversation.restore()

    expect(conversation.conversationId.value).toBe(91)
    expect(conversation.turns.value).toHaveLength(1)
    expect(conversation.busy.value).toBe(true)
    expect(mocks.createSseClient).toHaveBeenCalledOnce()
  })

  it('刷新恢复（conversationRef）：会话头 + 历史重建完整链路', async () => {
    await persistence.setConversationRef(27, 91, 0)
    mocks.knowledgeApi.getConversation.mockResolvedValue(makeHead({ version: 3, lastTurnNo: 4 }))
    mocks.knowledgeApi.getTurns.mockResolvedValue([
      makeTurn(314, 'COMPLETED'),
      makeTurn(315, 'COMPLETED', { turnNo: 2 })
    ])

    const conversation = createConversation()
    await conversation.restore()

    expect(conversation.conversationId.value).toBe(91)
    expect(conversation.turns.value).toHaveLength(2)
    expect(conversation.busy.value).toBe(false)
  })

  it('conversationRef 指向其他媒体时丢弃引用', async () => {
    await persistence.setConversationRef(27, 91, 0)
    mocks.knowledgeApi.getConversation.mockResolvedValue(makeHead({ scopeMediaId: 999, title: '别的视频' }))

    const conversation = createConversation()
    await conversation.restore()

    expect(conversation.conversationId.value).toBeNull()
    expect(await persistence.getConversationRef(27)).toBeNull()
  })

  it('新会话：清内存与本地引用，不产生服务端调用', async () => {
    mocks.knowledgeApi.ask.mockResolvedValue(makeAccepted())
    await persistence.setConversationRef(27, 91, 1)
    const conversation = createConversation()

    await conversation.newConversation()

    expect(conversation.conversationId.value).toBeNull()
    expect(conversation.turns.value).toHaveLength(0)
    expect(await persistence.getConversationRef(27)).toBeNull()
    expect(mocks.knowledgeApi.ask).not.toHaveBeenCalled()
  })

  it('历史会话切换：读取当前视频会话与轮次后更新本地引用', async () => {
    const historicalTurns = [makeTurn(401, 'COMPLETED', { turnNo: 1, question: '历史问题' })]
    mocks.knowledgeApi.getConversation.mockResolvedValue(makeHead({
      conversationId: 123,
      version: 5,
      lastTurnNo: 1
    }))
    mocks.knowledgeApi.getTurns.mockResolvedValue(historicalTurns)
    const conversation = createConversation()

    await conversation.openConversation(123)

    expect(mocks.knowledgeApi.getConversation).toHaveBeenCalledWith(123)
    expect(mocks.knowledgeApi.getTurns).toHaveBeenCalledWith(123, {
      limit: 50,
      knownVersion: 5
    })
    expect(conversation.conversationId.value).toBe(123)
    expect(conversation.turns.value[0]?.question).toBe('历史问题')
    expect(await persistence.getConversationRef(27)).toEqual({ conversationId: 123, version: 5 })
  })

  it('历史会话切换拒绝其他视频的会话', async () => {
    mocks.knowledgeApi.getConversation.mockResolvedValue(makeHead({
      conversationId: 123,
      scopeMediaId: 999
    }))
    const conversation = createConversation()

    await expect(conversation.openConversation(123)).rejects.toThrow('该会话不属于当前视频')
    expect(mocks.knowledgeApi.getTurns).not.toHaveBeenCalled()
  })

  it('IndexedDB 命中先渲染，随后用服务端更高版本校正', async () => {
    const cachedTurn = makeTurn(314, 'COMPLETED')
    await memory.saveConversation(1, makeHead({ version: 1 }), [cachedTurn])

    let resolveHead!: (head: KnowledgeConversation) => void
    mocks.knowledgeApi.getConversation.mockReturnValue(
      new Promise<KnowledgeConversation>((resolve) => {
        resolveHead = resolve
      })
    )
    const serverTurn = makeTurn(315, 'COMPLETED', { turnNo: 2 })
    mocks.knowledgeApi.getTurns.mockResolvedValue([cachedTurn, serverTurn])

    const conversation = createConversation()
    const restoring = conversation.restore()
    await vi.waitFor(() => expect(conversation.turns.value.map((turn) => turn.turnId)).toEqual([314]))

    resolveHead(makeHead({ version: 2, lastTurnNo: 2 }))
    await restoring

    expect(conversation.version.value).toBe(2)
    expect(conversation.turns.value.map((turn) => turn.turnId)).toEqual([314, 315])
    expect(mocks.knowledgeApi.getConversation).toHaveBeenCalledWith(91, { knownVersion: 1 })
  })

  it('未知 POST 刷新恢复时使用保存的 question 与同一 requestId', async () => {
    await persistence.setPending({
      requestId: 'saved-request',
      question: '保存的问题',
      conversationId: null,
      turnId: null,
      mediaId: 27
    })
    mocks.knowledgeApi.ask.mockResolvedValue(makeAccepted({ requestId: 'saved-request' }))

    const conversation = createConversation()
    await conversation.restore()

    expect(mocks.knowledgeApi.ask).toHaveBeenCalledWith({
      requestId: 'saved-request',
      question: '保存的问题',
      scope: { type: 'SINGLE_VIDEO', mediaId: 27 }
    })
    expect(await persistence.getPending()).toMatchObject({
      requestId: 'saved-request',
      question: '保存的问题',
      conversationId: 91,
      turnId: 314
    })
  })
})
