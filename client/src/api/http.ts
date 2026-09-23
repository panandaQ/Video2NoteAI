/**
 * 统一 HTTP 入口。语义契约（与旧实现等价，全新 TS 重写）：
 * - 自动附加 Bearer Token；
 * - 只解包 application/json 的统一信封 { code, message, data }（code === 0 且 HTTP 2xx 视为成功）；
 * - SSE 与二进制等非 JSON 响应原样透传，绝不触碰 body；
 * - 401（非 /user/**）派发 window 事件 auth:expired，由 main.ts 统一清理会话。
 */

const API_BASE = (import.meta.env?.VITE_API_BASE_URL || '').replace(/\/$/, '')

export interface ApiError extends Error {
  status?: number
  code?: number
}

interface Envelope {
  code: number
  message: string
  data?: unknown
}

function isEnvelope(payload: unknown): payload is Envelope {
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return false
  const record = payload as Record<string, unknown>
  return typeof record.code === 'number' && 'message' in record
}

/** 非 JSON 响应专用读取（SSE / 二进制）：附加鉴权与 API_BASE，原样返回 Response，不触碰 body。 */
export async function fetchRaw(path: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem('dovideo.auth.token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return fetch(`${API_BASE}${path}`, { ...options, headers })
}

export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem('dovideo.auth.token')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error
    throw new Error('无法连接后端服务，请确认后端已启动且地址配置正确', { cause: error })
  }

  if (response.status === 401 && !path.startsWith('/user/')) {
    window.dispatchEvent(new Event('auth:expired'))
  }

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) return response as unknown as T

  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    // 声明是 JSON 却解析不了（例如空 body），退回原始响应交给调用方处理。
    return response as unknown as T
  }

  if (!isEnvelope(payload)) return payload as T
  if (response.ok && payload.code === 0) return payload.data as T

  const error = new Error(payload.message || `请求失败（HTTP ${response.status}）`) as ApiError
  error.status = response.status
  error.code = payload.code
  throw error
}
