import { onBeforeUnmount, ref } from 'vue'
import {
  createSseClient,
  isTerminalState,
  isTerminalStatus,
  type SseHandlers,
  type TaskEventPayload
} from './sseClient'

export type { TaskEventPayload, TaskEventState } from './sseClient'
export { isTerminalState, isTerminalStatus }

/**
 * 组件内使用的一条 SSE 订阅（固定 URL，如导入任务事件）：
 * 连接即回放当前状态；终态自动停止；断线指数退避重连；组件卸载时释放。
 */
export function useTaskEvents(url: string, options: SseHandlers = {}) {
  const latest = ref<TaskEventPayload | null>(null)
  const connected = ref(false)
  const reconnecting = ref(false)

  const client = createSseClient(url, {
    onEvent: (event) => {
      latest.value = event
      connected.value = true
      reconnecting.value = false
      options.onEvent?.(event)
    },
    onTerminal: (event) => {
      latest.value = event
      connected.value = false
      options.onTerminal?.(event)
    },
    onFatal: (error, attempt) => {
      connected.value = false
      options.onFatal?.(error, attempt)
    },
    onReconnecting: (attempt) => {
      reconnecting.value = true
      options.onReconnecting?.(attempt)
    }
  })

  onBeforeUnmount(() => client.stop())

  return { latest, connected, reconnecting, start: client.start, stop: client.stop }
}
