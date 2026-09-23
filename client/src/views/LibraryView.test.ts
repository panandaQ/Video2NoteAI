import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import LibraryView from './LibraryView.vue'
import type { VideoImportDetail, VideoImportJobStatus } from '../types/import'

const mocks = vi.hoisted(() => ({
  fetchList: vi.fn(),
  detail: vi.fn(),
  retry: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  library: {
    loading: false,
    error: '',
    items: [{ id: 62, title: 'Transformer 精读', author: 'test1', durationMs: 60000 }]
  },
  auth: { user: { id: 2, nickname: 'test1' } },
  route: { query: {} as Record<string, string> }
}))

vi.mock('../stores/library', () => ({
  useLibraryStore: () => ({ ...mocks.library, fetchList: mocks.fetchList })
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../api/endpoints', () => ({
  importApi: { detail: mocks.detail, retry: mocks.retry }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
  useRoute: () => mocks.route
}))

function job(importId: number, status: VideoImportJobStatus): VideoImportDetail {
  return {
    importId,
    status,
    targetType: 'SINGLE_VIDEO',
    platform: 'BILIBILI',
    container: null,
    counts: { total: 1, reused: 0, completed: 0, failed: status === 'FAILED' ? 1 : 0 },
    retryable: status === 'FAILED',
    errorCode: status === 'FAILED' ? 'DOWNLOAD_FAILED' : null,
    errorMessage: status === 'FAILED' ? '下载失败' : null,
    items: [{
      mediaId: importId + 100,
      itemOrder: 1,
      reused: false,
      title: status === 'FAILED' ? '失败视频' : '处理中的视频',
      canonicalUrl: null,
      status: status === 'FAILED' ? 'FAILED' : 'ANALYZING',
      stage: null,
      retryable: status === 'FAILED',
      errorCode: status === 'FAILED' ? 'DOWNLOAD_FAILED' : null
    }],
    createdAt: '2026-09-24T10:00:00Z',
    updatedAt: '2026-09-24T10:05:00Z'
  }
}

const ImportDialogStub = defineComponent({
  name: 'ImportDialog',
  emits: ['accepted', 'close'],
  template: '<button data-testid="accept-import" @click="$emit(\'accepted\', 29)">接受导入</button>'
})

const LibraryMediaCardStub = defineComponent({
  name: 'LibraryMediaCard',
  props: ['item'],
  emits: ['open', 'refresh', 'retry'],
  template: `
    <article class="catalog-card" :data-status="item.status">
      {{ item.title }}
      <button v-if="item.kind === 'import'" class="refresh-card" @click="$emit('refresh', item.job.importId)">刷新</button>
    </article>
  `
})

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  mocks.route.query = {}
  mocks.fetchList.mockResolvedValue(undefined)
  mocks.retry.mockResolvedValue(undefined)
  mocks.detail.mockImplementation((importId: number) => {
    if (importId === 18) return Promise.resolve(job(18, 'FAILED'))
    return Promise.resolve(job(importId, 'PROCESSING'))
  })
})

describe('LibraryView catalog filters', () => {
  it('同时展示已就绪、处理中和失败，并按状态筛选', async () => {
    localStorage.setItem('dovideo.importRef:2', '[17,18]')
    const wrapper = mount(LibraryView, {
      global: {
        stubs: {
          ImportDialog: ImportDialogStub,
          LibraryMediaCard: LibraryMediaCardStub,
          EmptyState: true,
          SkeletonBlock: true,
          Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' }
        }
      }
    })
    await flushPromises()

    expect(wrapper.findAll('.catalog-card').map((card) => card.attributes('data-status'))).toEqual([
      'READY',
      'PROCESSING',
      'FAILED'
    ])

    const failedFilter = wrapper.findAll('.toolbar__filters button').find((button) => button.text().includes('失败'))
    await failedFilter!.trigger('click')
    expect(wrapper.findAll('.catalog-card')).toHaveLength(1)
    expect(wrapper.get('.catalog-card').attributes('data-status')).toBe('FAILED')
  })

  it('新增任务和终态刷新会同步本地引用', async () => {
    localStorage.setItem('dovideo.importRef:2', '[17,18]')
    const wrapper = mount(LibraryView, {
      global: {
        stubs: {
          ImportDialog: ImportDialogStub,
          LibraryMediaCard: LibraryMediaCardStub,
          EmptyState: true,
          SkeletonBlock: true,
          Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' }
        }
      }
    })
    await flushPromises()

    await wrapper.get('[data-testid="accept-import"]').trigger('click')
    await flushPromises()
    expect(localStorage.getItem('dovideo.importRef:2')).toBe('[17,18,29]')

    mocks.detail.mockImplementation((importId: number) => {
      if (importId === 17) return Promise.resolve(job(17, 'COMPLETED'))
      if (importId === 18) return Promise.resolve(job(18, 'FAILED'))
      return Promise.resolve(job(importId, 'PROCESSING'))
    })
    await wrapper.findAll('.refresh-card')[0].trigger('click')
    await flushPromises()
    expect(localStorage.getItem('dovideo.importRef:2')).toBe('[18,29]')
    expect(mocks.fetchList).toHaveBeenCalledTimes(2)
  })
})
