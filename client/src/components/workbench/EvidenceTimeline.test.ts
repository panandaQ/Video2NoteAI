import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import EvidenceTimeline, { type TimelineEntry } from './EvidenceTimeline.vue'
import { useWorkbenchStore } from '../../stores/workbench'

const entries: TimelineEntry[] = [
  {
    rank: 1,
    turnNo: 2,
    startMs: 120000,
    endMs: 126000,
    source: 'CC',
    snippet: 'Transformer 使用多头注意力关注不同表示子空间。'
  }
]

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('EvidenceTimeline', () => {
  it('没有问答引用时不显示空区块', () => {
    const wrapper = mount(EvidenceTimeline, { props: { entries: [] } })

    expect(wrapper.find('.timeline').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('证据时间轴')
  })

  it('以问答引用命名并支持点击跳转', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(EvidenceTimeline, { props: { entries } })

    expect(wrapper.find('.timeline__title').text()).toBe('问答引用')
    await wrapper.find('.timeline__item').trigger('click')
    expect(workbench.playbackTimeMs).toBe(120000)
    expect(workbench.activeEvidenceRank).toBe(1)
  })
})
