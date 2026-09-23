import { computed, ref } from 'vue'
import { knowledgeApi } from '../api/endpoints'
import { knowledgeMemory, type KnowledgeMemoryService } from '../storage/knowledgeMemory'
import { useAuthStore } from '../stores/auth'
import type { KnowledgeConversation } from '../types/knowledge'

const PAGE_SIZE = 20

/** 历史页编排：列表事实来自 MySQL API，本地只保存进入工作台所需的版本化引用。 */
export function useConversationHistory(memory: KnowledgeMemoryService = knowledgeMemory) {
  const auth = useAuthStore()
  const items = ref<KnowledgeConversation[]>([])
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref('')
  const deletingId = ref<number | null>(null)
  const hasMore = ref(false)
  const userId = computed(() => auth.user?.id ?? null)

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const page = await knowledgeApi.listConversations({ limit: PAGE_SIZE })
      items.value = page
      hasMore.value = page.length === PAGE_SIZE
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '历史会话加载失败'
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    const cursor = items.value.at(-1)?.conversationId
    if (!hasMore.value || cursor === undefined || loadingMore.value) return
    loadingMore.value = true
    error.value = ''
    try {
      const page = await knowledgeApi.listConversations({ cursor, limit: PAGE_SIZE })
      const known = new Set(items.value.map((item) => item.conversationId))
      items.value = [...items.value, ...page.filter((item) => !known.has(item.conversationId))]
      hasMore.value = page.length === PAGE_SIZE
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '更多历史会话加载失败'
    } finally {
      loadingMore.value = false
    }
  }

  async function prepareOpen(item: KnowledgeConversation): Promise<number | null> {
    if (item.scopeType !== 'SINGLE_VIDEO' || item.scopeMediaId === null) return null
    await memory.setConversationRef(
      userId.value,
      item.scopeMediaId,
      item.conversationId,
      item.version,
      item.lastTurnNo
    )
    return item.scopeMediaId
  }

  async function remove(item: KnowledgeConversation) {
    if (deletingId.value !== null) return
    deletingId.value = item.conversationId
    error.value = ''
    try {
      await knowledgeApi.deleteConversation(item.conversationId)
      await memory.removeConversation(userId.value, item.conversationId)
      items.value = items.value.filter((candidate) => candidate.conversationId !== item.conversationId)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除会话失败'
    } finally {
      deletingId.value = null
    }
  }

  return {
    items,
    loading,
    loadingMore,
    error,
    deletingId,
    hasMore,
    load,
    loadMore,
    prepareOpen,
    remove
  }
}
