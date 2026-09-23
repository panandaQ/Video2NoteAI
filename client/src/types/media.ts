/** GET /media/list 的列表条目（后端只返回 READY 与旧上传 COMPLETED）。 */
export interface MediaSummary {
  id: number
  title: string
  author?: string | null
  durationMs?: number | null
  platform?: string | null
  sourceUrl?: string | null
  fileSize?: number | null
  coverUrl?: string | null
  status?: string | null
}
