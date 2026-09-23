/** GET /media/{mediaId}/note 的真实响应（2026-09-22 实测 media 62）。 */
export interface MediaNoteEvidence {
  timestampMs: number
  source: string
  content: string
  claim: string
}

export interface MediaNoteSection {
  /** 形如 chapter:vp-0-0 / chapter:vp-1-104000，末尾数字为章节起始毫秒 */
  key: string
  title: string
  items: string[]
}

export interface MediaNoteContent {
  title: string
  conclusions: string[]
  evidence: MediaNoteEvidence[]
  suggestions: string[]
  sections: MediaNoteSection[]
}

export interface MediaNoteResponse {
  mediaId: number
  status: string
  stage: string | null
  profileVersion: string
  retryable: boolean
  errorCode: string | null
  note: MediaNoteContent | null
}
