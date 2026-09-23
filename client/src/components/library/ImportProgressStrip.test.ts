import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ImportProgressStrip from './ImportProgressStrip.vue'
import type { SseHandlers } from '../../composables/sseClient'
import type { VideoImportDetail } from '../../types/import'

function makeDetail(overrides: Partial<VideoImportDetail> = {}): VideoImportDetail {
  return {
    importId: 1,
    status: 'PROCESSING',
    targetType: 'SINGLE_VIDEO',
    platform: 'BILIBILI',
    container: null,
    counts: { total: 1, reused: 0, completed: 0, failed: 0 },
    retryable: false,
    errorCode: null,
    errorMessage: null,
    items: [{
      mediaId: 65,
      itemOrder: 1,
      reused: false,
      title: 'Transformer',
      canonicalUrl: 'https://www.bilibili.com/video/BV1G4iMBeEWH',
      status: 'ACQUIRING',
      stage: null,
      retryable: false,
      errorCode: null
    }],
    createdAt: '2026-09-22T00:00:00',
    updatedAt: '2026-09-22T00:01:00',
    ...overrides
  }
}

const mocks = vi.hoisted(() => {
  const importApi = {
    detail: vi.fn(),
    retry: vi.fn(),
    eventsUrl: vi.fn(() => '/video-imports/1/events')
  }
  const sseState: { handlers: SseHandlers | undefined } = { handlers: undefined }
  const client = { start: vi.fn(), stop: vi.fn() }
  const createSseClient = vi.fn((_url: string, handlers: SseHandlers) => {
    sseState.handlers = handlers
    return client
  })
  const push = vi.fn()
  return { importApi, sseState, createSseClient, client, push }
})

vi.mock('../../api/endpoints', () => ({ importApi: mocks.importApi }))
vi.mock('../../composables/sseClient', () => ({ createSseClient: mocks.createSseClient }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

beforeEach(() => {
  vi.resetAllMocks()
  mocks.sseState.handlers = undefined
  mocks.importApi.eventsUrl.mockReturnValue('/video-imports/1/events')
  mocks.importApi.detail.mockResolvedValue(makeDetail())
  mocks.createSseClient.mockImplementation((_url: string, handlers: SseHandlers) => {
    mocks.sseState.handlers = handlers
    return mocks.client
  })
})

describe('ImportProgressStrip', () => {
  it('挂载先查询详情，再订阅未完成任务并显示真实阶段', async () => {
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()
    expect(mocks.importApi.detail).toHaveBeenCalledWith(1)
    expect(mocks.createSseClient).toHaveBeenCalledWith('/video-imports/1/events', expect.anything())
    expect(mocks.client.start).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('处理中')
    expect(wrapper.text()).toContain('正在下载视频')
  })

  it('刷新恢复到已完成任务时直接通知父级清理引用，不再连接 SSE', async () => {
    mocks.importApi.detail.mockResolvedValue(makeDetail({
      status: 'COMPLETED',
      counts: { total: 1, reused: 0, completed: 1, failed: 0 },
      items: [{ ...makeDetail().items[0], status: 'READY' }]
    }))
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()
    expect(wrapper.text()).toContain('导入完成')
    expect(wrapper.emitted('finished')).toEqual([[1]])
    expect(mocks.client.start).not.toHaveBeenCalled()
  })

  it('SSE 收到完成终态后查询详情并通知父级刷新列表', async () => {
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()
    mocks.importApi.detail.mockResolvedValue(makeDetail({ status: 'COMPLETED' }))
    await mocks.sseState.handlers?.onTerminal?.({ state: 'COMPLETED', message: '视频导入完成' })
    await flushPromises()
    expect(wrapper.emitted('finished')).toEqual([[1]])
  })

  it('刷新恢复到失败任务时显示失败与重试，重试后重新订阅', async () => {
    mocks.importApi.retry.mockResolvedValue(null)
    mocks.importApi.detail
      .mockResolvedValueOnce(makeDetail({ status: 'FAILED', retryable: true, errorMessage: '下载失败' }))
      .mockResolvedValueOnce(makeDetail({ status: 'PROCESSING' }))
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()
    expect(wrapper.text()).toContain('导入失败')
    expect(wrapper.text()).toContain('下载失败')
    expect(mocks.client.start).not.toHaveBeenCalled()

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(mocks.importApi.retry).toHaveBeenCalledWith(1)
    expect(wrapper.text()).toContain('处理中')
    expect(mocks.client.start).toHaveBeenCalledOnce()
  })

  it('详情不存在时清理陈旧引用，不再连接 SSE', async () => {
    mocks.importApi.detail.mockRejectedValue(Object.assign(new Error('不存在'), { status: 404 }))
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()

    expect(wrapper.emitted('finished')).toEqual([[1]])
    expect(mocks.client.start).not.toHaveBeenCalled()
  })

  it('Cookie 过期时提示去设置页更新，不提供重试', async () => {
    mocks.importApi.detail.mockResolvedValue(makeDetail({
      status: 'FAILED',
      errorCode: 'BILIBILI_COOKIE_EXPIRED',
      errorMessage: 'B 站 Cookie 已过期，请在设置页更新后重新导入'
    }))
    const wrapper = mount(ImportProgressStrip, { props: { importId: 1 } })
    await flushPromises()

    expect(wrapper.text()).toContain('B 站 Cookie 已过期')
    expect(mocks.client.start).not.toHaveBeenCalled()

    const settingsButton = wrapper.findAll('button').find((b) => b.text().includes('去设置页'))
    await settingsButton!.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'settings' })
  })
})
