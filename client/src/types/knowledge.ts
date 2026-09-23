export type KnowledgeScopeType = 'SINGLE_VIDEO' | 'LIBRARY'

export interface KnowledgeScope {
  type: KnowledgeScopeType
  mediaId?: number
}

/** 首问携带 scope；追问携带 conversationId（runbook §5.1）。 */
export interface KnowledgeQuestionRequest {
  requestId: string
  question: string
  conversationId?: number
  scope?: KnowledgeScope
}

/** POST /knowledge/questions 的 202 受理响应（runbook §5.2，含幂等回放额外字段 reused）。 */
export interface KnowledgeQuestionAccepted {
  conversationId: number
  turnId: number
  turnNo: number
  requestId: string
  status: string
  conversationVersion: number
  reused?: boolean
  eventsUrl: string
}

/** GET /knowledge/conversations/{id}（2026-09-22 真实响应形状）。 */
export interface KnowledgeConversation {
  conversationId: number
  scopeType: KnowledgeScopeType
  scopeMediaId: number | null
  title: string
  status: string
  version: number
  lastTurnNo: number
  createdAt: string
  updatedAt: string
}

export type KnowledgeTurnStatus = 'PROCESSING' | 'COMPLETED' | 'FAILED'

/** 回答来源模式：视频支撑 / 混合 / 模型知识兜底（旧 answerable 已移除）。 */
export type AnswerMode = 'VIDEO_GROUNDED' | 'HYBRID' | 'MODEL_KNOWLEDGE'

/** 单条引用证据（2026-09-22 真实响应形状：rank/title，无 id/turnId 字段）。 */
export interface KnowledgeEvidence {
  rank: number
  mediaId: number
  title: string
  startMs: number
  endMs: number
  source: string
  snippet: string
  score: number | null
}

/** GET /knowledge/conversations/{id}/turns/{turnId}（2026-09-22 真实响应形状）。 */
export interface KnowledgeTurn {
  turnId: number
  turnNo: number
  requestId: string
  question: string
  rewrittenQuery: string | null
  status: KnowledgeTurnStatus
  answerMode: AnswerMode | null
  answer: string | null
  errorCode: string | null
  createdAt: string
  completedAt: string | null
  evidence: KnowledgeEvidence[]
}
