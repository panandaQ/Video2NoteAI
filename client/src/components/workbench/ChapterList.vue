<script setup lang="ts">
import { computed } from 'vue'
import { useWorkbenchStore } from '../../stores/workbench'
import { formatClock } from '../../utils/format'
import { parseChapterStartMs } from '../../utils/source'
import type { MediaNoteSection } from '../../types/note'

const props = defineProps<{ sections: MediaNoteSection[] }>()
const workbench = useWorkbenchStore()

interface Chapter {
  key: string
  title: string
  startMs: number
}

const chapters = computed<Chapter[]>(() =>
  props.sections
    .map((section) => {
      const startMs = parseChapterStartMs(section.key)
      return startMs === null ? null : { key: section.key, title: section.title, startMs }
    })
    .filter((chapter): chapter is Chapter => chapter !== null)
    .sort((a, b) => a.startMs - b.startMs)
)

function activeChapter(startMs: number, index: number): boolean {
  if (workbench.playbackTimeMs < startMs) return false
  const next = chapters.value[index + 1]
  return next ? workbench.playbackTimeMs < next.startMs : true
}

function jump(startMs: number) {
  workbench.seekTo(startMs)
}
</script>

<template>
  <div v-if="chapters.length > 0" class="chapters">
    <h4 class="chapters__title">章节</h4>
    <ul class="chapters__list">
      <li v-for="(chapter, index) in chapters" :key="chapter.key">
        <button
          type="button"
          class="chapters__item"
          :class="{ 'is-active': activeChapter(chapter.startMs, index) }"
          @click="jump(chapter.startMs)"
        >
          <span class="chapters__time">{{ formatClock(chapter.startMs) }}</span>
          <span class="chapters__label">{{ chapter.title }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.chapters {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chapters__title {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-muted);
  font-weight: 500;
}
.chapters__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow-y: auto;
}
.chapters__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  padding: 6px 8px;
  border-radius: var(--radius-control);
  text-align: left;
  color: var(--color-text-body);
  font-size: var(--text-sm);
  transition: background var(--motion-fast), color var(--motion-fast);
}
.chapters__item:hover {
  background: var(--color-bg);
  color: var(--color-brand-strong);
}
.chapters__item.is-active {
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-weight: 500;
}
.chapters__time {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-muted);
}
.chapters__item.is-active .chapters__time {
  color: var(--color-brand-strong);
}
.chapters__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
