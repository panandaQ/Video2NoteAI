import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TurnMessage from './TurnMessage.vue'
import { useWorkbenchStore } from '../../stores/workbench'
import type { KnowledgeTurn } from '../../types/knowledge'

function makeTurn(overrides: Partial<KnowledgeTurn> = {}): KnowledgeTurn {
  return {
    turnId: 17,
    turnNo: 1,
    requestId: 'r-1',
    question: '这个视频的核心结论是什么？',
    rewrittenQuery: null,
    status: 'COMPLETED',
    answerMode: 'VIDEO_GROUNDED',
    answer: '核心结论如下 [E1]。',
    errorCode: null,
    createdAt: '2026-09-22T00:24:21.979',
    completedAt: '2026-09-22T00:24:48.071',
    evidence: [
      {
        rank: 1,
        mediaId: 62,
        title: '武侠',
        startMs: 0,
        endMs: 60000,
        source: 'CC+OCR',
        snippet: '前阵子我在网上刷到这么一张图…',
        score: null
      }
    ],
    ...overrides
  }
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  // happy-dom 无滚动实现时兜底
  if (typeof Element.prototype.scrollIntoView !== 'function') {
    Element.prototype.scrollIntoView = vi.fn()
  }
})

describe('TurnMessage', () => {
  it('渲染回答并包含 [E1] 引用 chip 与引用卡片', () => {
    const wrapper = mount(TurnMessage, { props: { turn: makeTurn() } })
    expect(wrapper.find('.cite-chip').text()).toBe('[E1]')
    expect(wrapper.find('.cite-card').exists()).toBe(true)
    expect(wrapper.find('.cite-card__time').text()).toBe('0:00 – 1:00')
  })

  it('点击引用 chip：高亮引用并跳转播放时间', async () => {
    const workbench = useWorkbenchStore()
    const wrapper = mount(TurnMessage, { props: { turn: makeTurn() } })
    await wrapper.find('.cite-chip').trigger('click')
    expect(workbench.activeEvidenceRank).toBe(1)
    expect(workbench.playbackTimeMs).toBe(0)
  })

  it('生成中轮次显示骨架与「生成中」标签，不渲染回答', () => {
    const wrapper = mount(TurnMessage, {
      props: { turn: makeTurn({ status: 'PROCESSING', answer: null, answerMode: null, completedAt: null }) }
    })
    expect(wrapper.find('.badge').text()).toBe('生成中')
    expect(wrapper.find('.turn__skeletons').exists()).toBe(true)
    expect(wrapper.find('.answer-body').exists()).toBe(false)
  })

  it('失败轮次显示可读文案与重新生成按钮', async () => {
    const wrapper = mount(TurnMessage, {
      props: {
        turn: makeTurn({
          status: 'FAILED',
          answer: null,
          answerMode: null,
          completedAt: null,
          errorCode: 'RETRIEVAL_UNAVAILABLE'
        })
      }
    })
    expect(wrapper.text()).toContain('检索服务暂不可用')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('regenerate')).toEqual([[17]])
  })

  it('MODEL_KNOWLEDGE 模式正常渲染回答与来源提示，不显示失败态', () => {
    const wrapper = mount(TurnMessage, {
      props: {
        turn: makeTurn({
          answerMode: 'MODEL_KNOWLEDGE',
          answer: '> 本次未在视频中检索到直接依据，以下回答来自模型通用知识。\n\n模型知识回答',
          evidence: []
        })
      }
    })
    expect(wrapper.find('.turn__answer').exists()).toBe(true)
    expect(wrapper.find('.turn__failed').exists()).toBe(false)
    expect(wrapper.find('.answer-body').text()).toContain('模型知识回答')
  })
})
