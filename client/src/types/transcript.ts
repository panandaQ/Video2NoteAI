export interface MediaTranscriptSegment {
  startMs: number
  endMs: number
  text: string
  source: string
  chapterId: string | null
}

export interface MediaTranscriptResponse {
  mediaId: number
  available: boolean
  segments: MediaTranscriptSegment[]
}
