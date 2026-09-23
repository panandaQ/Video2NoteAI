import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ImportDialog from './ImportDialog.vue'

const mocks = vi.hoisted(() => ({
  create: vi.fn()
}))

vi.mock('../../api/endpoints', () => ({
  importApi: { create: mocks.create }
}))

beforeEach(() => {
  vi.clearAllMocks()
  mocks.create.mockResolvedValue({ importId: 1, status: 'QUEUED', targetType: 'SINGLE', reused: false })
})

describe('ImportDialog', () => {
  it('提交时把所选清晰度传给 importApi.create', async () => {
    const wrapper = mount(ImportDialog, {
      props: { open: true },
      global: {
        stubs: {
          Modal: { template: '<div><slot /></div>' },
          Button: { template: '<button :type="type"><slot /></button>' }
        }
      }
    })

    await wrapper.find('input').setValue('https://www.bilibili.com/video/BV1xx411c7mD')
    await wrapper.find('select').setValue('720')
    await wrapper.find('form').trigger('submit')

    expect(mocks.create).toHaveBeenCalledWith('https://www.bilibili.com/video/BV1xx411c7mD', 720)
  })

  it('默认清晰度为 480P', async () => {
    const wrapper = mount(ImportDialog, {
      props: { open: true },
      global: {
        stubs: {
          Modal: { template: '<div><slot /></div>' },
          Button: { template: '<button :type="type"><slot /></button>' }
        }
      }
    })

    await wrapper.find('input').setValue('https://www.bilibili.com/video/BV1xx411c7mD')
    await wrapper.find('form').trigger('submit')

    expect(mocks.create).toHaveBeenCalledWith('https://www.bilibili.com/video/BV1xx411c7mD', 480)
  })
})
