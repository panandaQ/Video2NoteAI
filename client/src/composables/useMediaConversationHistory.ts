import { shallowRef } from 'vue'
import { knowledgeApi } from '../api/endpoints'
import type { KnowledgeConversation } from '../types/knowledge'

const MEDIA_HISTORY_LIMIT = 50

/** 当前工作台媒体的服务端会话列表；过滤发生在服务端，避免客户端分页遗漏。 */
export function useMediaConversationHistory(mediaId: number) {
  const items = shallowRef<KnowledgeConversation[]>([])
  const loading = shallowRef(false)
  const error = shallowRef('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      items.value = await knowledgeApi.listConversations({
        mediaId,
        limit: MEDIA_HISTORY_LIMIT
      })
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '历史会话加载失败'
    } finally {
      loading.value = false
    }
  }

  return { items, loading, error, load }
}

export type MediaConversationHistory = ReturnType<typeof useMediaConversationHistory>
