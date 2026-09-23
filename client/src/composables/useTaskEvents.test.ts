import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { useTaskEvents } from './useTaskEvents'
import type { SseHandlers } from './sseClient'

const mocks = vi.hoisted(() => {
  const sseState: { handlers: SseHandlers | undefined } = { handlers: undefined }
  const client = { start: vi.fn(), stop: vi.fn() }
  const createSseClient = vi.fn((_url: string, handlers: SseHandlers) => {
    sseState.handlers = handlers
    return client
  })
  return { sseState, createSseClient, client }
})

vi.mock('./sseClient', () => ({ createSseClient: mocks.createSseClient }))

function mountComposable<T>(setup: () => T) {
  let result!: T
  const Host = defineComponent({
    setup() {
      result = setup()
      return () => null
    }
  })
  const wrapper = mount(Host)
  return { wrapper, result: () => result }
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.sseState.handlers = undefined
  mocks.createSseClient.mockImplementation((_url: string, handlers: SseHandlers) => {
    mocks.sseState.handlers = handlers
    return mocks.client
  })
})

describe('useTaskEvents', () => {
  it('事件回放更新 latest 与 connected 状态', async () => {
    const { wrapper, result } = mountComposable(() => useTaskEvents('/events'))
    result().start()
    expect(mocks.client.start).toHaveBeenCalledOnce()

    await mocks.sseState.handlers?.onEvent?.({ state: 'PROCESSING' })
    await wrapper.vm.$nextTick()
    expect(result().latest.value?.state).toBe('PROCESSING')
    expect(result().connected.value).toBe(true)
  })

  it('终态事件更新 latest 并断开 connected', async () => {
    const { wrapper, result } = mountComposable(() => useTaskEvents('/events'))
    result().start()

    await mocks.sseState.handlers?.onTerminal?.({ state: 'COMPLETED', message: '完成' })
    await wrapper.vm.$nextTick()
    expect(result().latest.value?.state).toBe('COMPLETED')
    expect(result().connected.value).toBe(false)
  })

  it('组件卸载时释放 SSE 连接', () => {
    const { wrapper, result } = mountComposable(() => useTaskEvents('/events'))
    result().start()
    wrapper.unmount()
    expect(mocks.client.stop).toHaveBeenCalledOnce()
  })
})
