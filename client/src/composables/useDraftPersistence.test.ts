import { beforeEach, describe, expect, it } from 'vitest'
import { computed } from 'vue'
import { useDraftPersistence, type PendingQuestion } from './useDraftPersistence'
import { KnowledgeMemoryService } from '../storage/knowledgeMemory'
import { MemoryKnowledgeStorageDriver } from '../storage/knowledgeStorageDriver'

const userId = computed(() => 1)
let now = 0
let memory: KnowledgeMemoryService

beforeEach(() => {
  now = Date.parse('2026-01-01T00:00:00Z')
  memory = new KnowledgeMemoryService(new MemoryKnowledgeStorageDriver(), () => now)
})

describe('useDraftPersistence', () => {
  it('草稿读写往返，空文本清除记录', async () => {
    const persistence = useDraftPersistence(userId, memory)
    expect(await persistence.getDraft(7)).toBe('')

    await persistence.setDraft(7, '未提交的问题')
    expect(await persistence.getDraft(7)).toBe('未提交的问题')

    await persistence.setDraft(7, '')
    expect(await persistence.getDraft(7)).toBe('')
  })

  it('pending 连同原问题写入、读取、清除', async () => {
    const persistence = useDraftPersistence(userId, memory)
    const pending: PendingQuestion = {
      requestId: 'r-1',
      question: '如何避免缓存击穿？',
      conversationId: 91,
      turnId: 314,
      mediaId: 27
    }

    await persistence.setPending(pending)
    expect(await persistence.getPending()).toEqual(pending)

    await persistence.clearPending()
    expect(await persistence.getPending()).toBeNull()
  })

  it('conversationRef 按媒体隔离并携带版本', async () => {
    const persistence = useDraftPersistence(userId, memory)
    await persistence.setConversationRef(27, 91, 3)
    await persistence.setConversationRef(28, 92, 4)
    expect(await persistence.getConversationRef(27)).toEqual({ conversationId: 91, version: 3 })
    expect(await persistence.getConversationRef(28)).toEqual({ conversationId: 92, version: 4 })
    await persistence.clearConversationRef(27)
    expect(await persistence.getConversationRef(27)).toBeNull()
    expect(await persistence.getConversationRef(28)).toEqual({ conversationId: 92, version: 4 })
  })

  it('超过 24 小时过期并清除', async () => {
    const persistence = useDraftPersistence(userId, memory)
    await persistence.setDraft(7, '旧草稿')

    now += 25 * 60 * 60 * 1000
    expect(await persistence.getDraft(7)).toBe('')
  })

  it('userId 为空时不读写本地条目', async () => {
    const none = computed(() => null)
    const persistence = useDraftPersistence(none, memory)
    await persistence.setDraft(7, 'x')
    await persistence.setPending({
      requestId: 'r',
      question: '问题',
      conversationId: null,
      turnId: null,
      mediaId: 7
    })
    expect(await persistence.getDraft(7)).toBe('')
    expect(await persistence.getPending()).toBeNull()
  })
})
