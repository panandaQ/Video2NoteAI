import { apiRequest } from './http'
import type {
  KnowledgeConversation,
  KnowledgeQuestionAccepted,
  KnowledgeQuestionRequest,
  KnowledgeTurn
} from '../types/knowledge'
import type { ImportAccepted, VideoImportDetail, BilibiliCookieStatus } from '../types/import'
import type { MediaSummary } from '../types/media'
import type { MediaNoteResponse } from '../types/note'
import type { MediaTranscriptResponse } from '../types/transcript'

/** 客户端幂等键：同一 requestId 永远指向同一次逻辑请求（runbook §10）。 */
export function newRequestId(): string {
  return crypto.randomUUID()
}

function jsonBody(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }
}

export const authApi = {
  login: (payload: { username: string; password: string }) =>
    apiRequest<{ token: string; userInfo: { id: number; nickname: string } }>('/user/login', jsonBody(payload)),
  register: (payload: { username: string; password: string; nickname: string }) =>
    apiRequest<unknown>('/user/register', jsonBody(payload)),
  logout: () => apiRequest<unknown>('/user/logout', { method: 'POST' })
}

export const mediaApi = {
  list: () => apiRequest<MediaSummary[]>('/media/list'),
  playbackUrl: (mediaId: number) => `/media/playback?id=${mediaId}`,
  note: (mediaId: number) => apiRequest<MediaNoteResponse>(`/media/${mediaId}/note`),
  transcript: (mediaId: number) =>
    apiRequest<MediaTranscriptResponse>(`/media/${mediaId}/transcript`)
}

export const importApi = {
  create: (url: string, quality?: number) =>
    apiRequest<ImportAccepted>('/videos/import', jsonBody({ url, quality })),
  detail: (importId: number) => apiRequest<VideoImportDetail>(`/video-imports/${importId}`),
  eventsUrl: (importId: number) => `/video-imports/${importId}/events`,
  retry: (importId: number) => apiRequest<unknown>(`/video-imports/${importId}/retry`, { method: 'POST' })
}

export const bilibiliApi = {
  status: () => apiRequest<BilibiliCookieStatus>('/user/bilibili-cookie'),
  saveCookie: (cookie: string) => apiRequest<unknown>('/user/bilibili-cookie', jsonBody({ cookie })),
  clearCookie: () => apiRequest<unknown>('/user/bilibili-cookie', { method: 'DELETE' })
}

export const knowledgeApi = {
  ask: (body: KnowledgeQuestionRequest) =>
    apiRequest<KnowledgeQuestionAccepted>('/knowledge/questions', jsonBody(body)),
  listConversations: (params?: { cursor?: number; limit?: number; mediaId?: number }) => {
    const query = new URLSearchParams()
    if (params?.cursor !== undefined) query.set('cursor', String(params.cursor))
    if (params?.limit !== undefined) query.set('limit', String(params.limit))
    if (params?.mediaId !== undefined) query.set('mediaId', String(params.mediaId))
    const suffix = query.size > 0 ? `?${query.toString()}` : ''
    return apiRequest<KnowledgeConversation[]>(`/knowledge/conversations${suffix}`)
  },
  getConversation: (conversationId: number, params?: { knownVersion?: number }) => {
    const query = new URLSearchParams()
    if (params?.knownVersion !== undefined) query.set('knownVersion', String(params.knownVersion))
    const suffix = query.size > 0 ? `?${query.toString()}` : ''
    return apiRequest<KnowledgeConversation>(`/knowledge/conversations/${conversationId}${suffix}`)
  },
  getTurns: (
    conversationId: number,
    params?: { beforeTurnNo?: number; limit?: number; knownVersion?: number }
  ) => {
    const query = new URLSearchParams()
    if (params?.beforeTurnNo !== undefined) query.set('beforeTurnNo', String(params.beforeTurnNo))
    if (params?.limit !== undefined) query.set('limit', String(params.limit))
    if (params?.knownVersion !== undefined) query.set('knownVersion', String(params.knownVersion))
    const suffix = query.size > 0 ? `?${query.toString()}` : ''
    return apiRequest<KnowledgeTurn[]>(`/knowledge/conversations/${conversationId}/turns${suffix}`)
  },
  getTurn: (conversationId: number, turnId: number) =>
    apiRequest<KnowledgeTurn>(`/knowledge/conversations/${conversationId}/turns/${turnId}`),
  deleteConversation: (conversationId: number) =>
    apiRequest<string>(`/knowledge/conversations/${conversationId}`, { method: 'DELETE' }),
  eventsUrl: (conversationId: number, turnId: number) =>
    `/knowledge/conversations/${conversationId}/turns/${turnId}/events`
}
