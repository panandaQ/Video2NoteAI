/**
 * SSE 订阅客户端（纯逻辑，不依赖 Vue）：供 useTaskEvents（组件生命周期绑定）
 * 与 useKnowledgeConversation（按轮次动态建链）共用。
 *
 * 语义契约（与旧 taskEvents.js 同语义，全新实现）：
 * - 帧按 \r?\n\r?\n 分割，data: 行合并后 JSON 解析；
 * - 终态（COMPLETED/FAILED）自动停止，不重连；
 * - 4xx（除 408/429）是终态错误：回调 onFatal 后停止，不自愈空转；
 * - 网络错误/5xx：指数退避重连（1s 起、2 倍、上限 15s），只重连不重提业务请求。
 */

import { fetchRaw } from '../api/http'

export type TaskEventState = 'PROCESSING' | 'COMPLETED' | 'FAILED'

export interface TaskEventPayload {
  state: TaskEventState
  message?: string
  [key: string]: unknown
}

/** 4xx 中除 408（超时）与 429（限流）外都不会自行恢复，视为终态错误。 */
export function isTerminalStatus(status: number): boolean {
  return status >= 400 && status < 500 && status !== 408 && status !== 429
}

export function isTerminalState(state: string): state is 'COMPLETED' | 'FAILED' {
  return state === 'COMPLETED' || state === 'FAILED'
}

export interface SseHandlers {
  onEvent?: (event: TaskEventPayload) => void
  onTerminal?: (event: TaskEventPayload) => void
  onFatal?: (error: Error, attempt: number) => void
  onReconnecting?: (attempt: number) => void
}

export interface SseClient {
  start: () => void
  stop: () => void
}

export function createSseClient(url: string, handlers: SseHandlers = {}): SseClient {
  let controller: AbortController | null = null
  let stopped = false
  let generation = 0

  function start() {
    stop()
    stopped = false
    const myGeneration = ++generation
    controller = new AbortController()
    void run(myGeneration, controller.signal)
  }

  function stop() {
    stopped = true
    controller?.abort()
    controller = null
  }

  async function run(myGeneration: number, signal: AbortSignal) {
    let reconnectAttempt = 0
    while (!signal.aborted && !stopped && generation === myGeneration) {
      try {
        const response = await fetchWithAuth(url, signal)
        if (!response.ok) {
          const text = await response.text()
          const error = new Error(text || `事件流连接失败（HTTP ${response.status}）`)
          if (isTerminalStatus(response.status)) {
            handlers.onFatal?.(error, reconnectAttempt + 1)
            return
          }
          throw error
        }
        if (!response.body) throw new Error('服务端未返回事件流')
        const terminal = await consumeStream(response.body, async (event) => {
          reconnectAttempt = 0
          handlers.onEvent?.(event)
          if (isTerminalState(event.state)) {
            handlers.onTerminal?.(event)
            return true
          }
          return false
        }, signal)
        if (terminal) return
      } catch (error) {
        if (signal.aborted || stopped || generation !== myGeneration) return
      }
      handlers.onReconnecting?.(reconnectAttempt + 1)
      const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
      await waitForRetry(delay, signal)
    }
  }

  return { start, stop }
}

/** 带鉴权的 SSE 读取；非 JSON 响应原样透传（不经统一信封）。 */
function fetchWithAuth(url: string, signal: AbortSignal): Promise<Response> {
  return fetchRaw(url, { signal })
}

async function consumeStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: TaskEventPayload) => Promise<boolean>,
  signal: AbortSignal
): Promise<boolean> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        const data = frame
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        const event = JSON.parse(data) as TaskEventPayload
        if (await onEvent(event)) return true
      }
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}

function waitForRetry(delay: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })
    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}
