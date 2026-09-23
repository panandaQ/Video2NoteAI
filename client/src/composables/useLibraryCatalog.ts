import { computed, ref, shallowRef, type Ref } from 'vue'
import { importApi } from '../api/endpoints'
import type { VideoImportDetail } from '../types/import'
import type { MediaSummary } from '../types/media'
import { useImportTracking } from './useImportTracking'

export type LibraryCatalogStatus = 'READY' | 'PROCESSING' | 'FAILED'

export type LibraryCatalogItem =
  | {
      key: string
      kind: 'media'
      status: 'READY'
      title: string
      media: MediaSummary
    }
  | {
      key: string
      kind: 'import'
      status: 'PROCESSING' | 'FAILED'
      title: string
      job: VideoImportDetail
    }

const failedStatuses = new Set<VideoImportDetail['status']>(['FAILED', 'DISPATCH_FAILED', 'PARTIAL_SUCCESS'])

function importTitle(job: VideoImportDetail): string {
  return job.items[0]?.title || job.container?.title || `视频导入任务 #${job.importId}`
}

function toImportItem(job: VideoImportDetail): LibraryCatalogItem {
  const status: LibraryCatalogStatus = failedStatuses.has(job.status) ? 'FAILED' : 'PROCESSING'
  return {
    key: `import-${job.importId}`,
    kind: 'import',
    status,
    title: importTitle(job),
    job
  }
}

/**
 * 视频库目录聚合：READY 来自媒体列表，处理中/失败来自当前用户可恢复的导入任务引用。
 * 服务端事实始终通过详情接口读取，本地只保留 importId。
 */
export function useLibraryCatalog(userId: number | null, mediaItems: Ref<MediaSummary[]>) {
  const tracking = useImportTracking(userId)
  const importJobs = ref<VideoImportDetail[]>([])
  const loadingImports = shallowRef(false)
  const importError = shallowRef('')

  const items = computed<LibraryCatalogItem[]>(() => [
    ...mediaItems.value.map((media) => ({
      key: `media-${media.id}`,
      kind: 'media' as const,
      status: 'READY' as const,
      title: media.title || '未命名视频',
      media
    })),
    ...importJobs.value.map(toImportItem)
  ])

  async function loadTrackedImports() {
    loadingImports.value = true
    importError.value = ''
    const jobs: VideoImportDetail[] = []
    const importIds = [...tracking.trackedImportIds.value]

    try {
      const results = await Promise.allSettled(
        importIds.map((importId) => importApi.detail(importId))
      )
      let hasUnreadableImport = false
      results.forEach((result, index) => {
        const importId = importIds[index]
        if (result.status === 'fulfilled') {
          if (result.value.status === 'COMPLETED') {
            tracking.forget(importId)
          } else {
            jobs.push(result.value)
          }
          return
        }
        const status = (result.reason as Error & { status?: number }).status
        if (status === 404) {
          tracking.forget(importId)
        } else {
          hasUnreadableImport = true
        }
      })
      importJobs.value = jobs
      if (hasUnreadableImport) {
        importError.value = '部分导入任务状态暂时无法读取'
      }
    } finally {
      loadingImports.value = false
    }
  }

  async function trackImport(importId: number) {
    tracking.track(importId)
    await loadTrackedImports()
  }

  function forgetImport(importId: number) {
    tracking.forget(importId)
    importJobs.value = importJobs.value.filter((job) => job.importId !== importId)
  }

  async function retryImport(importId: number) {
    await importApi.retry(importId)
    await loadTrackedImports()
  }

  return {
    items,
    importJobs,
    loadingImports,
    importError,
    trackedImportIds: tracking.trackedImportIds,
    loadTrackedImports,
    trackImport,
    forgetImport,
    retryImport
  }
}
