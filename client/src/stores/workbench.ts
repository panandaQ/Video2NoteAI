import { defineStore } from 'pinia'

/**
 * 工作台跨栏共享状态（设计稿 §11）：播放位置、当前选中引用等。
 * F2/F3 会继续扩展会话态相关字段。
 */
export const useWorkbenchStore = defineStore('workbench', {
  state: () => ({
    playbackTimeMs: 0,
    activeEvidenceRank: null as number | null
  }),
  actions: {
    seekTo(ms: number) {
      this.playbackTimeMs = ms
    },
    highlightEvidence(rank: number | null) {
      this.activeEvidenceRank = rank
    }
  }
})
