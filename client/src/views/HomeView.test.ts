import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HomeView from './HomeView.vue'
import { useAuthStore } from '../stores/auth'

const mocks = vi.hoisted(() => ({
  fetchList: vi.fn(),
  load: vi.fn(),
  prepareOpen: vi.fn(),
  push: vi.fn(),
  forget: vi.fn(),
  library: {
    items: [{ id: 62, title: 'Transformer 精读', author: 'test', durationMs: 60000 }],
    loading: false,
    error: ''
  },
  conversation: {
    conversationId: 51,
    scopeType: 'SINGLE_VIDEO' as const,
    scopeMediaId: 62,
    title: 'Transformer 的注意力机制如何工作？',
    status: 'ACTIVE',
    version: 3,
    lastTurnNo: 4,
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-24T10:00:00Z'
  }
}))

vi.mock('../stores/library', () => ({
  useLibraryStore: () => ({ ...mocks.library, fetchList: mocks.fetchList })
}))
vi.mock('../composables/useConversationHistory', () => ({
  useConversationHistory: () => ({
    items: ref([mocks.conversation]),
    loading: ref(false),
    error: ref(''),
    load: mocks.load,
    prepareOpen: mocks.prepareOpen
  })
}))
vi.mock('../composables/useImportTracking', () => ({
  useImportTracking: () => ({ trackedImportIds: ref([29]), forget: mocks.forget })
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

const ContinueStub = defineComponent({
  props: ['media', 'conversation'],
  emits: ['open'],
  template: '<button data-testid="continue" @click="$emit(\'open\', media.id)">{{ conversation.title }}</button>'
})

const ProcessingStub = defineComponent({
  props: ['importIds'],
  emits: ['finished'],
  template: '<button data-testid="finish" @click="$emit(\'finished\', importIds[0])">完成</button>'
})

const ConversationsStub = defineComponent({
  props: ['items'],
  emits: ['open'],
  template: '<button data-testid="conversation" @click="$emit(\'open\', items[0])">会话</button>'
})

beforeEach(() => {
  vi.clearAllMocks()
  setActivePinia(createPinia())
  useAuthStore().$patch({
    token: 'test-token',
    user: { id: 2, nickname: 'test1' }
  })
  mocks.fetchList.mockResolvedValue(undefined)
  mocks.load.mockResolvedValue(undefined)
  mocks.prepareOpen.mockResolvedValue(62)
})

describe('HomeView', () => {
  it('加载首页事实并可继续最近学习', async () => {
    const wrapper = mount(HomeView, {
      global: {
        stubs: {
          HomeContinueCard: ContinueStub,
          HomeProcessingPanel: ProcessingStub,
          HomeRecentConversations: ConversationsStub,
          HomeRecentVideos: true,
          Button: { template: '<button><slot /></button>' }
        }
      }
    })

    expect(mocks.fetchList).toHaveBeenCalledTimes(1)
    expect(mocks.load).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="continue"]').text()).toContain('Transformer')

    await wrapper.get('[data-testid="conversation"]').trigger('click')
    await nextTick()

    expect(mocks.prepareOpen).toHaveBeenCalledWith(mocks.conversation)
    expect(mocks.push).toHaveBeenCalledWith({ name: 'workbench', params: { mediaId: '62' } })
  })

  it('完成导入后移除本地引用并刷新视频列表', async () => {
    const wrapper = mount(HomeView, {
      global: {
        stubs: {
          HomeContinueCard: ContinueStub,
          HomeProcessingPanel: ProcessingStub,
          HomeRecentConversations: ConversationsStub,
          HomeRecentVideos: true,
          Button: { template: '<button><slot /></button>' }
        }
      }
    })

    await wrapper.get('[data-testid="finish"]').trigger('click')
    await nextTick()

    expect(mocks.forget).toHaveBeenCalledWith(29)
    expect(mocks.fetchList).toHaveBeenCalledTimes(2)
  })

  it('登录用户变更后重新拉取首页事实', async () => {
    mount(HomeView, {
      global: {
        stubs: {
          HomeContinueCard: ContinueStub,
          HomeProcessingPanel: ProcessingStub,
          HomeRecentConversations: ConversationsStub,
          HomeRecentVideos: true,
          Button: { template: '<button><slot /></button>' }
        }
      }
    })

    useAuthStore().$patch({ user: { id: 3, nickname: 'another-user' } })
    await nextTick()

    expect(mocks.fetchList).toHaveBeenCalledTimes(2)
    expect(mocks.load).toHaveBeenCalledTimes(2)
  })
})
