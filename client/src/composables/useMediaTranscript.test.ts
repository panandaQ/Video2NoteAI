import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMediaTranscript } from './useMediaTranscript'

const mocks = vi.hoisted(() => ({ transcript: vi.fn() }))

vi.mock('../api/endpoints', () => ({ mediaApi: { transcript: mocks.transcript } }))

beforeEach(() => vi.clearAllMocks())

describe('useMediaTranscript', () => {
  it('加载服务端完整字幕状态', async () => {
    mocks.transcript.mockResolvedValue({
      mediaId: 27,
      available: true,
      segments: [{ startMs: 0, endMs: 5000, text: '第一句', source: 'CC', chapterId: null }]
    })
    const transcript = useMediaTranscript(27)

    await transcript.load()

    expect(mocks.transcript).toHaveBeenCalledWith(27)
    expect(transcript.available.value).toBe(true)
    expect(transcript.segments.value[0]?.text).toBe('第一句')
  })

  it('失败时清空旧字幕并给出错误', async () => {
    mocks.transcript.mockRejectedValue(new Error('字幕接口不可用'))
    const transcript = useMediaTranscript(27)

    await transcript.load()

    expect(transcript.available.value).toBe(false)
    expect(transcript.segments.value).toEqual([])
    expect(transcript.error.value).toBe('字幕接口不可用')
  })
})
