import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CitationCard from './CitationCard.vue'
import { useWorkbenchStore } from '../../stores/workbench'

const props = {
  rank: 3,
  startMs: 882000,
  endMs: 900000,
  source: 'CC+OCR',
  snippet: '眉清目秀的小和尚…'
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('CitationCard', () => {
  it('展示编号、时间范围、来源与摘要', () => {
    const wrapper = mount(CitationCard, { props })
    expect(wrapper.find('.cite-card__rank').text()).toBe('[3]')
    expect(wrapper.find('.cite-card__time').text()).toBe('14:42 – 15:00')
    expect(wrapper.find('.cite-card__snippet').text()).toBe('眉清目秀的小和尚…')
  })

  it('点击跳转播放：seekTo(startMs) 并高亮该引用', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(CitationCard, { props })
    await wrapper.find('.cite-card__jump').trigger('click')
    expect(workbench.playbackTimeMs).toBe(882000)
    expect(workbench.activeEvidenceRank).toBe(3)
  })

  it('当前播放位置落在证据区间内时高亮', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(CitationCard, { props })
    expect(wrapper.find('.cite-card').classes()).not.toContain('is-active')

    workbench.playbackTimeMs = 890000
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.cite-card').classes()).toContain('is-active')
  })
})
