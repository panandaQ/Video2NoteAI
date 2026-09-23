<script setup lang="ts">
import { computed } from 'vue'
import { useWorkbenchStore } from '../../stores/workbench'
import { formatTimeRange } from '../../utils/format'
import { sourceLabel } from '../../utils/source'
import Badge from '../ui/Badge.vue'

export interface TimelineEntry {
  rank: number
  turnNo: number
  startMs: number
  endMs: number
  source: string
  snippet: string
}

const props = defineProps<{ entries: TimelineEntry[] }>()
const workbench = useWorkbenchStore()

function isActive(entry: TimelineEntry): boolean {
  if (workbench.activeEvidenceRank === entry.rank) return true
  return workbench.playbackTimeMs >= entry.startMs && workbench.playbackTimeMs <= entry.endMs
}

const sorted = computed(() => [...props.entries].sort((a, b) => a.startMs - b.startMs))

function seek(entry: TimelineEntry) {
  workbench.highlightEvidence(entry.rank)
  workbench.seekTo(entry.startMs)
}
</script>

<template>
  <div v-if="sorted.length > 0" class="timeline">
    <h3 class="timeline__title">问答引用</h3>
    <ul class="timeline__list">
      <li
        v-for="entry in sorted"
        :key="`${entry.turnNo}-${entry.rank}`"
        class="timeline__item"
        :class="{ 'is-active': isActive(entry) }"
        role="button"
        tabindex="0"
        @click="seek(entry)"
        @keydown.enter="seek(entry)"
      >
        <div class="timeline__head">
          <span class="timeline__time">{{ formatTimeRange(entry.startMs, entry.endMs) }}</span>
          <Badge tone="brand">{{ sourceLabel(entry.source) }}</Badge>
        </div>
        <p class="timeline__snippet">{{ entry.snippet }}</p>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.timeline__title {
  font-size: var(--text-md);
}
.timeline__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.timeline__item {
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
  cursor: pointer;
  transition: border-color var(--motion-fast), background var(--motion-fast);
}
.timeline__item:hover {
  border-color: var(--color-brand);
}
.timeline__item.is-active {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.timeline__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.timeline__time {
  font-size: var(--text-sm);
  color: var(--color-brand-strong);
  font-variant-numeric: tabular-nums;
}
.timeline__snippet {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-body);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
