import { defineStore } from 'pinia'
import { apiRequest } from '../api/http'
import { knowledgeMemory } from '../storage/knowledgeMemory'

const TOKEN_KEY = 'dovideo.auth.token'
const USER_KEY = 'dovideo.auth.user'

/** 业务本地条目前缀：登出与切换用户时必须全部清空（Spec §4.4.4）。 */
const BUSINESS_KEY_PREFIXES = [
  'dovideo.questionDraft:',
  'dovideo.pendingQuestion:',
  'dovideo.conversationRef:',
  'dovideo.importRef:'
]

function clearLegacyBusinessStorage() {
  for (const key of Object.keys(localStorage)) {
    if (BUSINESS_KEY_PREFIXES.some((prefix) => key.startsWith(prefix))) {
      localStorage.removeItem(key)
    }
  }
}

export interface AuthUser {
  id: number
  nickname: string
  username?: string
}

function readUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) ?? '',
    user: readUser()
  }),
  getters: {
    hasToken: (state) => Boolean(state.token)
  },
  actions: {
    async setSession(token: string, user: AuthUser) {
      const previousUserId = this.user?.id ?? null
      if (previousUserId !== null && previousUserId !== user.id) {
        clearLegacyBusinessStorage()
        await knowledgeMemory.clearUser(previousUserId)
      }
      this.token = token
      this.user = user
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    /** 先同步撤销鉴权，再异步删除当前用户全部设备记忆。 */
    async clearSession() {
      const userId = this.user?.id ?? null
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      clearLegacyBusinessStorage()
      await knowledgeMemory.clearUser(userId)
    },
    async logout() {
      try {
        await apiRequest('/user/logout', { method: 'POST' })
      } catch {
        // 服务端登出失败不阻塞本地清理
      }
      await this.clearSession()
    }
  }
})
