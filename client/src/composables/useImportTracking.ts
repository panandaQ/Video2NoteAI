import { readonly, shallowRef } from 'vue'

const STORAGE_PREFIX = 'dovideo.importRef:'
const MAX_TRACKED_IMPORTS = 20

function storageKey(userId: number): string {
  return `${STORAGE_PREFIX}${userId}`
}

function normalizeImportIds(value: unknown): number[] {
  if (!Array.isArray(value)) return []
  return Array.from(new Set(value.filter((id): id is number => Number.isSafeInteger(id) && id > 0)))
    .slice(-MAX_TRACKED_IMPORTS)
}

function readStoredImportIds(userId: number | null): number[] {
  if (userId === null) return []
  try {
    const raw = localStorage.getItem(storageKey(userId))
    return raw ? normalizeImportIds(JSON.parse(raw)) : []
  } catch {
    return []
  }
}

/**
 * 当前用户的导入任务引用。只保存可重建的 importId，任务状态始终以服务端详情为准。
 */
export function useImportTracking(userId: number | null) {
  const trackedImportIds = shallowRef<number[]>(readStoredImportIds(userId))

  function persist(ids: number[]) {
    if (userId === null) return
    try {
      if (ids.length === 0) {
        localStorage.removeItem(storageKey(userId))
      } else {
        localStorage.setItem(storageKey(userId), JSON.stringify(ids))
      }
    } catch {
      // 浏览器禁用存储时只退化为当前页面内存状态，不能阻塞导入。
    }
  }

  function track(importId: number) {
    const next = normalizeImportIds([...trackedImportIds.value, importId])
    trackedImportIds.value = next
    persist(next)
  }

  function forget(importId: number) {
    const next = trackedImportIds.value.filter((id) => id !== importId)
    trackedImportIds.value = next
    persist(next)
  }

  return {
    trackedImportIds: readonly(trackedImportIds),
    track,
    forget
  }
}
