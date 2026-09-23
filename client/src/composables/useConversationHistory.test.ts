import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import { KnowledgeMemoryService } from '../storage/knowledgeMemory'
import { MemoryKnowledgeStorageDriver } from '../storage/knowledgeStorageDriver'
import type { KnowledgeConversation } from '../types/knowledge'
import { useConversationHistory } from './useConversationHistory'

const mocks = vi.hoisted(() => ({
  listConversations: vi.fn(),
  deleteConversation: vi.fn()
}))

vi.mock('../api/endpoints', () => ({ knowledgeApi: mocks }))

function conversation(id: number, mediaId = 27): KnowledgeConversation {
  return {
    conversationId: id,
    scopeType: 'SINGLE_VIDEO',
    scopeMediaId: mediaId,
    title: `会话 ${id}`,
    status: 'ACTIVE',
    version: 3,
    lastTurnNo: 4,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-02T00:00:00.000Z'
  }
}

let memory: KnowledgeMemoryService

beforeEach(async () => {
  localStorage.clear()
  vi.resetAllMocks()
  setActivePinia(createPinia())
  await useAuthStore().setSession('token', { id: 7, nickname: '测试' })
  memory = new KnowledgeMemoryService(new MemoryKnowledgeStorageDriver())
})

describe('useConversationHistory', () => {
  it('加载后可写入版本化引用并进入对应视频', async () => {
    mocks.listConversations.mockResolvedValue([conversation(91)])
    const history = useConversationHistory(memory)

    await history.load()
    expect(history.items.value.map((item) => item.conversationId)).toEqual([91])
    expect(await history.prepareOpen(history.items.value[0]!)).toBe(27)
    expect(await memory.getConversationRef(7, 27)).toEqual({ conversationId: 91, version: 3 })
  })

  it('删除成功后同步删除设备投影', async () => {
    const item = conversation(91)
    mocks.listConversations.mockResolvedValue([item])
    mocks.deleteConversation.mockResolvedValue('会话已删除')
    await memory.saveConversation(7, item, [])
    const history = useConversationHistory(memory)
    await history.load()

    await history.remove(item)

    expect(mocks.deleteConversation).toHaveBeenCalledWith(91)
    expect(history.items.value).toEqual([])
    expect(await memory.getCachedConversation(7, 27)).toBeNull()
  })
})
