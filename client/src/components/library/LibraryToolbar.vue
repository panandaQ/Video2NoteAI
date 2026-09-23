<script setup lang="ts">
import type { LibraryCatalogStatus } from '../../composables/useLibraryCatalog'

export type LibraryFilter = 'ALL' | LibraryCatalogStatus

defineProps<{
  counts: Record<LibraryFilter, number>
}>()

const query = defineModel<string>('query', { required: true })
const status = defineModel<LibraryFilter>('status', { required: true })

const filters: Array<{ value: LibraryFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'READY', label: '已就绪' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'FAILED', label: '失败' }
]
</script>

<template>
  <div class="toolbar">
    <label class="toolbar__search">
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
        <circle cx="10.8" cy="10.8" r="6.8" />
        <path d="m16 16 4 4" />
      </svg>
      <input v-model="query" type="search" placeholder="搜索视频" />
    </label>

    <div class="toolbar__filters" role="group" aria-label="按状态筛选">
      <button
        v-for="filter in filters"
        :key="filter.value"
        type="button"
        :class="{ 'is-active': status === filter.value }"
        @click="status = filter.value"
      >
        {{ filter.label }}
        <span>{{ counts[filter.value] }}</span>
      </button>
    </div>

    <div class="toolbar__sort" aria-label="当前排序方式">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
        <path d="M8 6h12M8 12h9M8 18h6M4 5v14" />
      </svg>
      最近添加
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto 150px;
  align-items: center;
  gap: 18px;
}
.toolbar__search {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--color-text-muted);
}
.toolbar__search:focus-within {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px var(--color-brand-soft);
}
.toolbar__search input {
  width: 100%;
  padding: 11px 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-text-strong);
  font-size: var(--text-sm);
}
.toolbar__filters {
  display: flex;
  align-items: center;
  gap: 7px;
}
.toolbar__filters button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 15px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: #eeefec;
  color: var(--color-text-body);
  font-size: var(--text-sm);
  transition: color var(--motion-fast), border-color var(--motion-fast), background var(--motion-fast);
}
.toolbar__filters button span {
  color: var(--color-text-muted);
  font-size: 10px;
}
.toolbar__filters button.is-active {
  border-color: var(--color-brand);
  background: var(--color-surface);
  color: var(--color-brand-strong);
  font-weight: 600;
}
.toolbar__sort {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--color-text-body);
  font-size: var(--text-sm);
}
@media (max-width: 1080px) {
  .toolbar { grid-template-columns: 1fr; }
  .toolbar__filters { overflow-x: auto; }
  .toolbar__sort { display: none; }
}
</style>
