import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMediaConversationHistory } from './useMediaConversationHistory'

const mocks = vi.hoisted(() => ({ listConversations: vi.fn() }))

vi.mock('../api/endpoints', () => ({ knowledgeApi: { listConversations: mocks.listConversations } }))

beforeEach(() => vi.clearAllMocks())

describe('useMediaConversationHistory', () => {
  it('只请求当前媒体的服务端会话列表', async () => {
    mocks.listConversations.mockResolvedValue([{ conversationId: 91, scopeMediaId: 27 }])
    const history = useMediaConversationHistory(27)

    await history.load()

    expect(mocks.listConversations).toHaveBeenCalledWith({ mediaId: 27, limit: 50 })
    expect(history.items.value).toHaveLength(1)
  })
})
