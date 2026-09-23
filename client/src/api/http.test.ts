import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './http'

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'content-type': 'application/json' }
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('apiRequest', () => {
  it('解包成功信封并返回 data', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 0, message: 'success', data: { id: 3 } })))
    await expect(apiRequest<{ id: number }>('/media/list')).resolves.toEqual({ id: 3 })
  })

  it('自动附加 Bearer Token', async () => {
    localStorage.setItem('dovideo.auth.token', 'tok-1')
    const spy = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse({ code: 0, message: 'success', data: null })
    )
    vi.stubGlobal('fetch', spy)
    await apiRequest('/media/list')
    const firstCall = spy.mock.calls[0] as [RequestInfo | URL, RequestInit | undefined] | undefined
    const headers = new Headers(firstCall?.[1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer tok-1')
  })

  it('业务错误抛出带 message 与状态码的 Error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 40400, message: '视频不存在' }, 404)))
    let caught: (Error & { status?: number }) | null = null
    try {
      await apiRequest('/knowledge/conversations/1')
    } catch (err) {
      caught = err as Error & { status?: number }
    }
    expect(caught).toBeInstanceOf(Error)
    expect(caught?.message).toBe('视频不存在')
    expect(caught?.status).toBe(404)
  })

  it('SSE（非 JSON）响应原样透传，不触碰 body', async () => {
    const raw = new Response('data: {"state":"COMPLETED"}', {
      headers: { 'content-type': 'text/event-stream' }
    })
    vi.stubGlobal('fetch', vi.fn(async () => raw))
    const result = await apiRequest('/video-imports/1/events')
    expect(result).toBe(raw)
  })

  it('401 且非 /user 路径时派发 auth:expired', async () => {
    const handler = vi.fn()
    window.addEventListener('auth:expired', handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 40100, message: '未登录' }, 401)))
    await expect(apiRequest('/media/list')).rejects.toThrow('未登录')
    expect(handler).toHaveBeenCalledOnce()
  })

  it('/user 路径的 401 不派发 auth:expired（避免登录失败触发登出清理）', async () => {
    const handler = vi.fn()
    window.addEventListener('auth:expired', handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ code: 40100, message: '账号或密码错误' }, 401)))
    await expect(apiRequest('/user/login')).rejects.toThrow('账号或密码错误')
    expect(handler).not.toHaveBeenCalled()
  })
})
