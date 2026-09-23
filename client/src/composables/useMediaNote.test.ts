import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMediaNote } from './useMediaNote'
import type { MediaNoteResponse } from '../types/note'

const mocks = vi.hoisted(() => ({ note: vi.fn() }))

vi.mock('../api/endpoints', () => ({
  mediaApi: { note: mocks.note, list: vi.fn(), playbackUrl: vi.fn() },
  authApi: {},
  importApi: {},
  knowledgeApi: {},
  newRequestId: vi.fn()
}))

const readyNote: MediaNoteResponse = {
  mediaId: 62,
  status: 'READY',
  stage: 'ANALYSIS_COMPLETED_WITH_WARNINGS',
  profileVersion: 'VIDEO_NOTE_V2',
  retryable: false,
  errorCode: null,
  note: {
    title: '武侠笔记',
    conclusions: ['结论一'],
    suggestions: [],
    sections: [{ key: 'chapter:vp-0-0', title: '童年武侠', items: ['要点'] }],
    evidence: [{ timestampMs: 0, source: 'CC', content: '片段', claim: '结论一' }]
  }
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('useMediaNote', () => {
  it('加载成功：写入 note 与 status', async () => {
    mocks.note.mockResolvedValue(readyNote)
    const state = useMediaNote(62)
    expect(state.loading.value).toBe(false)

    await state.load()

    expect(mocks.note).toHaveBeenCalledWith(62)
    expect(state.note.value?.title).toBe('武侠笔记')
    expect(state.status.value).toBe('READY')
    expect(state.loading.value).toBe(false)
  })

  it('加载失败：记录可读错误并支持重试', async () => {
    mocks.note.mockRejectedValueOnce(new Error('无法连接后端服务'))
    const state = useMediaNote(62)
    await state.load()
    expect(state.error.value).toBe('无法连接后端服务')
    expect(state.note.value).toBeNull()

    mocks.note.mockResolvedValueOnce(readyNote)
    await state.load()
    expect(state.error.value).toBe('')
    expect(state.note.value?.title).toBe('武侠笔记')
  })

  it('处理中视频（note=null）不视为错误', async () => {
    mocks.note.mockResolvedValue({ ...readyNote, status: 'PROCESSING', note: null })
    const state = useMediaNote(62)
    await state.load()
    expect(state.status.value).toBe('PROCESSING')
    expect(state.note.value).toBeNull()
    expect(state.error.value).toBe('')
  })
})
