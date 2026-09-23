/** 证据来源 → 中文标签（CC=平台字幕、OCR=画面文字，D-008 字幕优先）。 */
const SOURCE_LABELS: Record<string, string> = {
  subtitle: '字幕',
  cc: '字幕',
  'cc+ocr': '字幕+画面',
  ocr: '画面',
  asr: 'ASR'
}

export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source.toLowerCase()] ?? source
}

/** 章节 key（chapter:vp-{index}-{startMs}）→ 起始毫秒；解析失败返回 null。 */
export function parseChapterStartMs(key: string): number | null {
  const match = /-(\d+)$/.exec(key)
  if (!match) return null
  return Number(match[1])
}
