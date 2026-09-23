import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SettingsView from './SettingsView.vue'

const mocks = vi.hoisted(() => ({
  status: vi.fn(),
  saveCookie: vi.fn(),
  clearCookie: vi.fn()
}))

vi.mock('../api/endpoints', () => ({
  bilibiliApi: {
    status: mocks.status,
    saveCookie: mocks.saveCookie,
    clearCookie: mocks.clearCookie
  }
}))

const ButtonStub = {
  props: ['disabled', 'loading'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.status.mockResolvedValue({ hasCookie: false, updatedAt: null })
  mocks.saveCookie.mockResolvedValue(undefined)
  mocks.clearCookie.mockResolvedValue(undefined)
})

describe('SettingsView', () => {
  it('挂载时加载保存状态并展示已保存', async () => {
    mocks.status.mockResolvedValue({ hasCookie: true, updatedAt: '2026-09-24T10:00:00Z' })
    const wrapper = mount(SettingsView, {
      global: { stubs: { Button: ButtonStub } }
    })
    await flushPromises()

    expect(mocks.status).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('已保存')
  })

  it('保存把 Cookie 明文交给后端且不回显', async () => {
    const wrapper = mount(SettingsView, {
      global: { stubs: { Button: ButtonStub } }
    })
    await flushPromises()

    await wrapper.find('textarea').setValue('SESSDATA=abc; bili_jct=xyz')
    const saveButton = wrapper.findAll('button').find((b) => b.text() === '保存')
    await saveButton!.trigger('click')
    await flushPromises()

    expect(mocks.saveCookie).toHaveBeenCalledWith('SESSDATA=abc; bili_jct=xyz')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })
})
