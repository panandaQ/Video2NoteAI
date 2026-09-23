import { beforeEach, describe, expect, it } from 'vitest'
import { KnowledgeMemoryService } from './knowledgeMemory'
import { MemoryKnowledgeStorageDriver } from './knowledgeStorageDriver'
import type { KnowledgeConversation, KnowledgeTurn } from '../types/knowledge'

function makeHead(id: number, version: number, mediaId = id): KnowledgeConversation {
  return {
    conversationId: id,
    scopeType: 'SINGLE_VIDEO',
    scopeMediaId: mediaId,
    title: `会话 ${id}`,
    status: 'ACTIVE',
    version,
    lastTurnNo: version,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z'
  }
}

function makeTurn(turnNo: number, status: KnowledgeTurn['status'] = 'COMPLETED'): KnowledgeTurn {
  return {
    turnId: turnNo,
    turnNo,
    requestId: `request-${turnNo}`,
    question: `问题 ${turnNo}`,
    rewrittenQuery: null,
    status,
    answerMode: status === 'COMPLETED' ? 'VIDEO_GROUNDED' : null,
    answer: status === 'COMPLETED' ? `答案 ${turnNo}` : null,
    errorCode: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    completedAt: status === 'COMPLETED' ? '2026-01-01T00:00:01.000Z' : null,
    evidence: []
  }
}

let now = 0
let driver: MemoryKnowledgeStorageDriver
let memory: KnowledgeMemoryService

beforeEach(() => {
  now = Date.parse('2026-01-01T00:00:00Z')
  driver = new MemoryKnowledgeStorageDriver()
  memory = new KnowledgeMemoryService(driver, () => now)
})

describe('KnowledgeMemoryService', () => {
  it('只缓存每个会话最近 10 个 COMPLETED 轮次', async () => {
    const turns = Array.from({ length: 13 }, (_, index) => makeTurn(index + 1))
    turns.push(makeTurn(14, 'PROCESSING'))

    await memory.saveConversation(1, makeHead(91, 13, 27), turns)
    const cached = await memory.getCachedConversation(1, 27)

    expect(cached?.turns.map((turn) => turn.turnNo)).toEqual([4, 5, 6, 7, 8, 9, 10, 11, 12, 13])
  })

  it('按最近访问时间仅保留每个用户 20 个会话头', async () => {
    for (let id = 1; id <= 21; id += 1) {
      now += 1
      await memory.saveConversation(1, makeHead(id, 1), [makeTurn(id)])
    }

    const heads = await driver.getAll<{ userId: number; conversationId: number }>('conversationHeads')
    expect(heads.filter((head) => head.userId === 1)).toHaveLength(20)
    expect(heads.some((head) => head.conversationId === 1)).toBe(false)
  })

  it('低版本写入不能覆盖较新的本地投影', async () => {
    await memory.saveConversation(1, makeHead(91, 5, 27), [makeTurn(5)])
    const stale = { ...makeHead(91, 4, 27), title: '过期标题' }

    expect(await memory.saveConversation(1, stale, [makeTurn(4)])).toBe(false)
    const cached = await memory.getCachedConversation(1, 27)
    expect(cached?.head.version).toBe(5)
    expect(cached?.head.title).toBe('会话 91')
  })

  it('相同版本下较小的 lastTurnNo 不能覆盖已受理的新轮次', async () => {
    await memory.saveConversation(1, { ...makeHead(91, 3, 27), lastTurnNo: 5 }, [makeTurn(5)])
    const stale = { ...makeHead(91, 3, 27), lastTurnNo: 4, title: '旧投影' }

    expect(await memory.saveConversation(1, stale, [makeTurn(4)])).toBe(false)
    const cached = await memory.getCachedConversation(1, 27)
    expect(cached?.head.lastTurnNo).toBe(5)
    expect(cached?.head.title).toBe('会话 91')
  })

  it('草稿和 pending 24 小时过期，pending 保留原问题用于同 requestId 恢复', async () => {
    await memory.setDraft(1, 27, '草稿')
    await memory.setPending(1, {
      requestId: 'r-1',
      question: '原问题',
      conversationId: null,
      turnId: null,
      mediaId: 27
    })
    expect(await memory.getPending(1)).toMatchObject({ requestId: 'r-1', question: '原问题' })

    now += 24 * 60 * 60 * 1000 + 1
    expect(await memory.getDraft(1, 27)).toBe('')
    expect(await memory.getPending(1)).toBeNull()
  })

  it('clearUser 只删除目标用户的五类数据', async () => {
    for (const userId of [1, 2]) {
      await memory.saveConversation(userId, makeHead(90 + userId, 1, 20 + userId), [makeTurn(1)])
      await memory.setDraft(userId, 20 + userId, `draft-${userId}`)
      await memory.setPending(userId, {
        requestId: `r-${userId}`,
        question: `question-${userId}`,
        conversationId: 90 + userId,
        turnId: null,
        mediaId: 20 + userId
      })
    }

    await memory.clearUser(1)

    expect(await memory.getCachedConversation(1, 21)).toBeNull()
    expect(await memory.getDraft(1, 21)).toBe('')
    expect(await memory.getPending(1)).toBeNull()
    expect(await memory.getCachedConversation(2, 22)).not.toBeNull()
    expect(await memory.getDraft(2, 22)).toBe('draft-2')
    expect(await memory.getPending(2)).not.toBeNull()
  })
})
