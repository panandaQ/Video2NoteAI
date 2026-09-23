<script setup lang="ts">
import { computed } from 'vue'
import { useWorkbenchStore } from '../../stores/workbench'
import { formatTimeRange } from '../../utils/format'
import { sourceLabel } from '../../utils/source'
import Badge from '../ui/Badge.vue'

const props = defineProps<{
  rank: number
  startMs: number
  endMs: number
  source: string
  snippet: string
}>()

const workbench = useWorkbenchStore()

const source = computed(() => sourceLabel(props.source))

const active = computed(() => {
  if (workbench.activeEvidenceRank === props.rank) return true
  return workbench.playbackTimeMs >= props.startMs && workbench.playbackTimeMs <= props.endMs
})

function jump() {
  workbench.highlightEvidence(props.rank)
  workbench.seekTo(props.startMs)
}
</script>

<template>
  <div class="cite-card" :class="{ 'is-active': active }">
    <div class="cite-card__head">
      <span class="cite-card__rank">[{{ rank }}]</span>
      <span class="cite-card__time">{{ formatTimeRange(startMs, endMs) }}</span>
      <Badge tone="brand">{{ source }}</Badge>
      <button type="button" class="cite-card__jump" @click="jump">跳转播放</button>
    </div>
    <p class="cite-card__snippet">{{ snippet }}</p>
  </div>
</template>

<style scoped>
.cite-card {
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
  transition: border-color var(--motion-fast), background var(--motion-fast);
}
.cite-card.is-active {
  border-color: var(--color-brand);
  border-left-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.cite-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.cite-card__rank {
  font-weight: 600;
  color: var(--color-brand-strong);
}
.cite-card__time {
  font-size: var(--text-sm);
  color: var(--color-brand-strong);
  font-variant-numeric: tabular-nums;
}
.cite-card__jump {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--color-brand-strong);
  font-size: var(--text-sm);
  padding: 2px 6px;
  border-radius: var(--radius-tag);
}
.cite-card__jump:hover {
  background: var(--color-brand-soft);
}
.cite-card__snippet {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-body);
}
</style>
