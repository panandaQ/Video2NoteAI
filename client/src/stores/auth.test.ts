import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('auth store', () => {
  it('setSession 写入 token 与用户并持久化', () => {
    const auth = useAuthStore()
    auth.setSession('tok-1', { id: 1, nickname: '测试' })
    expect(auth.hasToken).toBe(true)
    expect(auth.user?.nickname).toBe('测试')
    expect(localStorage.getItem('dovideo.auth.token')).toBe('tok-1')
    expect(localStorage.getItem('dovideo.auth.user')).toBeTruthy()
  })

  it('clearSession 清除 token、用户与全部业务本地条目（草稿/pending/conversationRef/importRef）', () => {
    localStorage.setItem('dovideo.auth.token', 'tok-1')
    localStorage.setItem('dovideo.auth.user', JSON.stringify({ id: 1, nickname: '测试' }))
    localStorage.setItem('dovideo.questionDraft:1:7', '未提交的草稿')
    localStorage.setItem('dovideo.pendingQuestion:1', JSON.stringify({ requestId: 'r-1' }))
    localStorage.setItem('dovideo.conversationRef:1:7', '9')
    localStorage.setItem('dovideo.importRef:1', '[5]')
    const unrelated = 'dovideo.unrelated'
    localStorage.setItem(unrelated, '保留')

    const auth = useAuthStore()
    auth.clearSession()

    expect(localStorage.getItem('dovideo.auth.token')).toBeNull()
    expect(localStorage.getItem('dovideo.auth.user')).toBeNull()
    expect(localStorage.getItem('dovideo.questionDraft:1:7')).toBeNull()
    expect(localStorage.getItem('dovideo.pendingQuestion:1')).toBeNull()
    expect(localStorage.getItem('dovideo.conversationRef:1:7')).toBeNull()
    expect(localStorage.getItem('dovideo.importRef:1')).toBeNull()
    expect(localStorage.getItem(unrelated)).toBe('保留')
  })

  it('登出失败（服务端不可达）仍完成本地清理', async () => {
    const auth = useAuthStore()
    auth.setSession('tok-1', { id: 1, nickname: '测试' })
    await auth.logout()
    expect(auth.hasToken).toBe(false)
  })
})
