import { defineStore } from 'pinia'
import { apiRequest } from '../api/http'
import type { MediaSummary } from '../types/media'

export const useLibraryStore = defineStore('library', {
  state: () => ({
    items: [] as MediaSummary[],
    loading: false,
    error: ''
  }),
  actions: {
    async fetchList() {
      this.loading = true
      this.error = ''
      try {
        this.items = await apiRequest<MediaSummary[]>('/media/list')
      } catch (err) {
        this.error = err instanceof Error ? err.message : '加载失败'
      } finally {
        this.loading = false
      }
    }
  }
})
