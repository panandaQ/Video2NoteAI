import { ref } from 'vue'
import { mediaApi } from '../api/endpoints'
import type { MediaNoteContent } from '../types/note'

/**
 * 视频默认笔记（VIDEO_NOTE_V2 Checkpoint 投影）：工作台进入即加载，
 * 中栏「知识点」与左栏「章节」共用同一份数据。
 */
export function useMediaNote(mediaId: number) {
  const note = ref<MediaNoteContent | null>(null)
  const status = ref('')
  const loading = ref(false)
  const error = ref('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const response = await mediaApi.note(mediaId)
      status.value = response.status
      note.value = response.note
    } catch (err) {
      error.value = err instanceof Error ? err.message : '笔记加载失败'
    } finally {
      loading.value = false
    }
  }

  return { note, status, loading, error, load }
}

export type MediaNoteState = ReturnType<typeof useMediaNote>
