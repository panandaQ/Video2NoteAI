import { shallowRef } from 'vue'
import { mediaApi } from '../api/endpoints'
import type { MediaTranscriptSegment } from '../types/transcript'

export function useMediaTranscript(mediaId: number) {
  const segments = shallowRef<MediaTranscriptSegment[]>([])
  const available = shallowRef(false)
  const loading = shallowRef(false)
  const error = shallowRef('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const response = await mediaApi.transcript(mediaId)
      segments.value = response.segments
      available.value = response.available
    } catch (cause) {
      segments.value = []
      available.value = false
      error.value = cause instanceof Error ? cause.message : '字幕加载失败'
    } finally {
      loading.value = false
    }
  }

  return { segments, available, loading, error, load }
}

export type MediaTranscriptState = ReturnType<typeof useMediaTranscript>
