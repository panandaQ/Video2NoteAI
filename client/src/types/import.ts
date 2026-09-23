/** POST /videos/import 的 202 受理响应。 */
export interface ImportAccepted {
  importId: number
  status: string
  targetType: string
  reused: boolean
}

/** 可选清晰度（高度像素），对应服务端白名单 360/480/720/1080。 */
export type ImportQuality = 360 | 480 | 720 | 1080

/** GET /user/bilibili-cookie 的状态响应（不含明文）。 */
export interface BilibiliCookieStatus {
  hasCookie: boolean
  updatedAt: string | null
}

export type VideoImportJobStatus =
  | 'PENDING_DISPATCH'
  | 'QUEUED'
  | 'RESOLVING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'DISPATCH_FAILED'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'

export type MediaImportStatus =
  | 'PENDING_DISPATCH'
  | 'QUEUED'
  | 'ACQUIRING'
  | 'MEDIA_READY'
  | 'ANALYSIS_QUEUED'
  | 'ANALYZING'
  | 'READY'
  | 'DISPATCH_FAILED'
  | 'FAILED'
  | 'COMPLETED'

export interface VideoImportItem {
  mediaId: number
  itemOrder: number
  reused: boolean
  title: string | null
  canonicalUrl: string | null
  status: MediaImportStatus
  stage: string | null
  retryable: boolean
  errorCode: string | null
}

export interface VideoImportDetail {
  importId: number
  status: VideoImportJobStatus
  targetType: string
  platform: string
  container: { containerId: string; title: string } | null
  counts: { total: number; reused: number; completed: number; failed: number }
  retryable: boolean
  errorCode: string | null
  errorMessage: string | null
  items: VideoImportItem[]
  createdAt: string
  updatedAt: string
}
