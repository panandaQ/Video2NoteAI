import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NotePanel from './NotePanel.vue'
import { useWorkbenchStore } from '../../stores/workbench'
import type { MediaNoteState } from '../../composables/useMediaNote'
import type { MediaNoteContent } from '../../types/note'

function makeState(overrides: {
  note?: MediaNoteContent | null
  loading?: boolean
  error?: string
}): MediaNoteState {
  return {
    note: ref(overrides.note ?? null),
    status: ref('READY'),
    loading: ref(overrides.loading ?? false),
    error: ref(overrides.error ?? ''),
    load: vi.fn()
  } as unknown as MediaNoteState
}

const sampleNote: MediaNoteContent = {
  title: '中国武侠百年风云录视频笔记',
  conclusions: ['结论一'],
  suggestions: [],
  sections: [
    { key: 'chapter:vp-0-0', title: '童年武侠', items: ['要点 A'] },
    { key: 'chapter:vp-1-104000', title: '武侠开山鼻祖', items: ['要点 B'] }
  ],
  evidence: [
    { timestampMs: 120000, source: 'CC', content: '片段内容', claim: '石玉昆是奠基人' },
    { timestampMs: 0, source: 'OCR', content: '画面文字', claim: '结论一' }
  ]
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('NotePanel', () => {
  it('加载中显示骨架', () => {
    const wrapper = mount(NotePanel, { props: { mediaNote: makeState({ loading: true }) } })
    expect(wrapper.find('.skeleton').exists()).toBe(true)
  })

  it('加载失败显示错误与重试', async () => {
    const state = makeState({ error: '无法连接后端服务' })
    const wrapper = mount(NotePanel, { props: { mediaNote: state } })
    expect(wrapper.text()).toContain('无法连接后端服务')
    await wrapper.find('button').trigger('click')
    expect(state.load).toHaveBeenCalledOnce()
  })

  it('渲染章节要点：点击章节标题跳转播放', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(NotePanel, { props: { mediaNote: makeState({ note: sampleNote }) } })
    const titles = wrapper.findAll('.note-section__title')
    expect(titles).toHaveLength(2)
    expect(wrapper.text()).toContain('要点 B')

    await titles[1].trigger('click')
    expect(workbench.playbackTimeMs).toBe(104000)
  })

  it('渲染证据片段：点击时间点跳转播放', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(NotePanel, { props: { mediaNote: makeState({ note: sampleNote }) } })
    const times = wrapper.findAll('.note-evidence__time')
    expect(wrapper.text()).toContain('证据片段（2）')

    await times[0].trigger('click')
    expect(workbench.playbackTimeMs).toBe(120000)
  })

  it('note 为空且无错误：提示笔记尚未生成', () => {
    const wrapper = mount(NotePanel, { props: { mediaNote: makeState({ note: null }) } })
    expect(wrapper.text()).toContain('尚未生成')
  })
})
