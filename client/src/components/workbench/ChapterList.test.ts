import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChapterList from './ChapterList.vue'
import { useWorkbenchStore } from '../../stores/workbench'
import type { MediaNoteSection } from '../../types/note'

const sections: MediaNoteSection[] = [
  { key: 'chapter:vp-2-236000', title: '民国旧派武侠', items: [] },
  { key: 'chapter:vp-0-0', title: '童年武侠', items: [] },
  { key: 'chapter:vp-1-104000', title: '武侠开山鼻祖', items: [] }
]

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('ChapterList', () => {
  it('按时间排序渲染章节并显示时间', () => {
    const wrapper = mount(ChapterList, { props: { sections } })
    const labels = wrapper.findAll('.chapters__label').map((node) => node.text())
    expect(labels).toEqual(['童年武侠', '武侠开山鼻祖', '民国旧派武侠'])
    expect(wrapper.findAll('.chapters__time').map((node) => node.text())).toEqual(['0:00', '1:44', '3:56'])
  })

  it('点击章节跳转播放', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(ChapterList, { props: { sections } })
    await wrapper.findAll('.chapters__item')[1].trigger('click')
    expect(workbench.playbackTimeMs).toBe(104000)
  })

  it('当前播放位置所在章节高亮', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(ChapterList, { props: { sections } })
    workbench.playbackTimeMs = 200000
    await wrapper.vm.$nextTick()
    const items = wrapper.findAll('.chapters__item')
    expect(items[1].classes()).toContain('is-active')
    expect(items[0].classes()).not.toContain('is-active')
  })

  it('无有效章节时整块隐藏', () => {
    const wrapper = mount(ChapterList, { props: { sections: [] } })
    expect(wrapper.find('.chapters').exists()).toBe(false)
  })
})
