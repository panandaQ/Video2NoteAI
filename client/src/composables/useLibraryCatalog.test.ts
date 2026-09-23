import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useLibraryCatalog } from './useLibraryCatalog'
import type { VideoImportDetail, VideoImportJobStatus } from '../types/import'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), retry: vi.fn() }))

vi.mock('../api/endpoints', () => ({
  importApi: { detail: mocks.detail, retry: mocks.retry }
}))

function job(importId: number, status: VideoImportJobStatus): VideoImportDetail {
  return {
    importId,
    status,
    targetType: 'SINGLE_VIDEO',
    platform: 'BILIBILI',
    container: null,
    counts: { total: 1, reused: 0, completed: status === 'COMPLETED' ? 1 : 0, failed: status === 'FAILED' ? 1 : 0 },
    retryable: status === 'FAILED',
    errorCode: status === 'FAILED' ? 'DOWNLOAD_FAILED' : null,
    errorMessage: status === 'FAILED' ? '下载失败' : null,
    items: [{
      mediaId: importId + 100,
      itemOrder: 1,
      reused: false,
      title: `视频任务 ${importId}`,
      canonicalUrl: null,
      status: status === 'FAILED' ? 'FAILED' : status === 'COMPLETED' ? 'READY' : 'ANALYZING',
      stage: null,
      retryable: status === 'FAILED',
      errorCode: status === 'FAILED' ? 'DOWNLOAD_FAILED' : null
    }],
    createdAt: '2026-09-24T10:00:00Z',
    updatedAt: '2026-09-24T10:05:00Z'
  }
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
})

describe('useLibraryCatalog', () => {
  it('合并已就绪媒体与处理中、失败任务，并清理已完成任务引用', async () => {
    localStorage.setItem('dovideo.importRef:2', '[17,18,19]')
    mocks.detail.mockImplementation((importId: number) => {
      if (importId === 17) return Promise.resolve(job(17, 'PROCESSING'))
      if (importId === 18) return Promise.resolve(job(18, 'FAILED'))
      return Promise.resolve(job(19, 'COMPLETED'))
    })

    const catalog = useLibraryCatalog(2, ref([{ id: 62, title: '已就绪视频', author: '作者' }]))
    await catalog.loadTrackedImports()

    expect(catalog.items.value.map((item) => item.status)).toEqual(['READY', 'PROCESSING', 'FAILED'])
    expect(localStorage.getItem('dovideo.importRef:2')).toBe('[17,18]')
  })

  it('失败任务重试后重新读取服务端状态', async () => {
    localStorage.setItem('dovideo.importRef:2', '[18]')
    mocks.retry.mockResolvedValue(undefined)
    mocks.detail.mockResolvedValue(job(18, 'PROCESSING'))

    const catalog = useLibraryCatalog(2, ref([]))
    await catalog.retryImport(18)

    expect(mocks.retry).toHaveBeenCalledWith(18)
    expect(catalog.items.value[0]?.status).toBe('PROCESSING')
  })
})
