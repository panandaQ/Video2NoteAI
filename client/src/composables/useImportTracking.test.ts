import { beforeEach, describe, expect, it } from 'vitest'
import { useImportTracking } from './useImportTracking'

beforeEach(() => localStorage.clear())

describe('useImportTracking', () => {
  it('按用户持久化多个任务，并在新页面实例中恢复', () => {
    const firstPage = useImportTracking(2)
    firstPage.track(17)
    firstPage.track(18)
    firstPage.track(17)

    expect(firstPage.trackedImportIds.value).toEqual([17, 18])
    expect(localStorage.getItem('dovideo.importRef:2')).toBe('[17,18]')

    const refreshedPage = useImportTracking(2)
    expect(refreshedPage.trackedImportIds.value).toEqual([17, 18])
  })

  it('完成后删除单个任务引用，最后一个完成时删除存储键', () => {
    localStorage.setItem('dovideo.importRef:2', '[17,18]')
    const tracking = useImportTracking(2)

    tracking.forget(17)
    expect(tracking.trackedImportIds.value).toEqual([18])
    expect(localStorage.getItem('dovideo.importRef:2')).toBe('[18]')

    tracking.forget(18)
    expect(tracking.trackedImportIds.value).toEqual([])
    expect(localStorage.getItem('dovideo.importRef:2')).toBeNull()
  })

  it('隔离不同用户，并忽略损坏或非法的本地值', () => {
    localStorage.setItem('dovideo.importRef:1', '[1]')
    localStorage.setItem('dovideo.importRef:2', '[2,-1,"bad",2,3]')
    localStorage.setItem('dovideo.importRef:3', '{broken')

    expect(useImportTracking(1).trackedImportIds.value).toEqual([1])
    expect(useImportTracking(2).trackedImportIds.value).toEqual([2, 3])
    expect(useImportTracking(3).trackedImportIds.value).toEqual([])
  })
})
