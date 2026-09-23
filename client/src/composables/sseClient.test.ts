import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSseClient, type TaskEventPayload } from './sseClient'

function sseResponse(frames: string[]): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      controller.close()
    }
  })
  return new Response(stream, { headers: { 'content-type': 'text/event-stream' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('createSseClient', () => {
  it('解析事件帧并在终态后停止，不重连', async () => {
    const events: TaskEventPayload[] = []
    const terminals: TaskEventPayload[] = []
    const onReconnecting = vi.fn()
    const fetchMock = vi.fn(async () =>
      sseResponse(['data: {"state":"COMPLETED","message":"视频导入完成"}\n\n'])
    )
    vi.stubGlobal('fetch', fetchMock)

    const client = createSseClient('/events', {
      onEvent: (event) => events.push(event),
      onTerminal: (event) => terminals.push(event),
      onReconnecting
    })
    client.start()

    await vi.waitFor(() => expect(terminals).toHaveLength(1))
    expect(events.map((event) => event.state)).toEqual(['COMPLETED'])
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(onReconnecting).not.toHaveBeenCalled()
    client.stop()
  })

  it('流结束但未终态：指数退避重连原 URL', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(sseResponse(['data: {"state":"PROCESSING"}\n\n']))
      .mockResolvedValueOnce(sseResponse(['data: {"state":"COMPLETED"}\n\n']))
    vi.stubGlobal('fetch', fetchMock)

    const terminals: TaskEventPayload[] = []
    const reconnects: number[] = []
    const client = createSseClient('/events', {
      onTerminal: (event) => terminals.push(event),
      onReconnecting: (attempt) => reconnects.push(attempt)
    })
    client.start()

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2), { timeout: 5000 })
    await vi.waitFor(() => expect(terminals).toHaveLength(1), { timeout: 5000 })
    expect(reconnects).toEqual([1])
    client.stop()
  })

  it('4xx（终态错误）回调 onFatal 且不重连', async () => {
    const onFatal = vi.fn()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"code":40400,"message":"不存在"}', { status: 404 }))
    )

    const client = createSseClient('/events', { onFatal })
    client.start()

    await vi.waitFor(() => expect(onFatal).toHaveBeenCalledOnce())
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(fetch).toHaveBeenCalledTimes(1)
    client.stop()
  })
})
