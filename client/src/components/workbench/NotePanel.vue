<script setup lang="ts">
import { computed } from 'vue'
import type { MediaNoteState } from '../../composables/useMediaNote'
import { useWorkbenchStore } from '../../stores/workbench'
import { formatClock } from '../../utils/format'
import { parseChapterStartMs, sourceLabel } from '../../utils/source'
import Badge from '../ui/Badge.vue'
import Button from '../ui/Button.vue'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

const props = withDefaults(defineProps<{ mediaNote: MediaNoteState; showHeader?: boolean }>(), {
  showHeader: true
})
const workbench = useWorkbenchStore()

const note = computed(() => props.mediaNote.note.value)
const loading = computed(() => props.mediaNote.loading.value)
const error = computed(() => props.mediaNote.error.value)

function seek(ms: number) {
  workbench.seekTo(ms)
}

function sectionStart(key: string): number | null {
  return parseChapterStartMs(key)
}
</script>

<template>
  <section class="note-panel" :class="{ 'note-panel--embedded': !showHeader }">
    <header v-if="showHeader" class="note-panel__head">
      <h3 class="note-panel__title">视频笔记</h3>
      <Badge v-if="note && !loading" tone="brand">知识点</Badge>
    </header>

    <div v-if="loading" class="note-panel__state" aria-busy="true">
      <SkeletonBlock v-for="n in 4" :key="n" :height="n === 1 ? '20px' : '14px'" />
    </div>

    <div v-else-if="error" class="note-panel__state">
      <p class="note-panel__error">{{ error }}</p>
      <Button variant="secondary" @click="props.mediaNote.load()">重新加载</Button>
    </div>

    <div v-else-if="!note" class="note-panel__state">
      <p class="note-panel__hint">该视频的默认笔记尚未生成，可以继续观看或直接开始提问。</p>
    </div>

    <div v-else class="note-panel__body">
      <h4 class="note-panel__note-title">{{ note.title }}</h4>

      <div v-if="note.sections.length > 0" class="note-panel__sections">
        <div v-for="section in note.sections" :key="section.key" class="note-section">
          <button
            v-if="sectionStart(section.key) !== null"
            type="button"
            class="note-section__title"
            @click="seek(sectionStart(section.key)!)"
          >
            {{ section.title }}
            <span class="note-section__time">{{ formatClock(sectionStart(section.key)!) }}</span>
          </button>
          <h5 v-else class="note-section__title-plain">{{ section.title }}</h5>
          <ul class="note-section__items">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>

      <ul v-else class="note-panel__conclusions">
        <li v-for="conclusion in note.conclusions" :key="conclusion">{{ conclusion }}</li>
      </ul>

      <details v-if="note.evidence.length > 0" class="note-panel__evidence" open>
        <summary>证据片段（{{ note.evidence.length }}）· 点击时间点跳转播放</summary>
        <ul class="note-evidence__list">
          <li
            v-for="(evidence, index) in note.evidence"
            :key="index"
            class="note-evidence__item"
            :class="{
              'is-active':
                workbench.playbackTimeMs >= evidence.timestampMs &&
                workbench.playbackTimeMs < evidence.timestampMs + 60000
            }"
            role="button"
            tabindex="0"
            @click="seek(evidence.timestampMs)"
            @keydown.enter="seek(evidence.timestampMs)"
          >
            <div class="note-evidence__head">
              <button type="button" class="note-evidence__time" @click.stop="seek(evidence.timestampMs)">
                {{ formatClock(evidence.timestampMs) }}
              </button>
              <Badge tone="neutral">{{ sourceLabel(evidence.source) }}</Badge>
              <p class="note-evidence__claim">{{ evidence.claim }}</p>
            </div>
            <p class="note-evidence__content">{{ evidence.content }}</p>
          </li>
        </ul>
      </details>
    </div>
  </section>
</template>

<style scoped>
.note-panel {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.note-panel--embedded {
  border: 0;
  border-radius: 0;
  padding: 16px 18px;
}
.note-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.note-panel__title {
  font-size: var(--text-md);
}
.note-panel__state {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}
.note-panel__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.note-panel__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.note-panel__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.note-panel__note-title {
  font-size: var(--text-md);
  margin: 0;
}
.note-panel__sections {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.note-section {
  padding: 10px 12px;
  background: var(--color-bg);
  border-radius: var(--radius-card);
}
.note-section__title {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--color-brand-strong);
  font-weight: 600;
  font-size: var(--text-sm);
}
.note-section__title:hover {
  text-decoration: underline;
}
.note-section__time {
  font-weight: 400;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}
.note-section__title-plain {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-strong);
}
.note-section__items {
  margin: 6px 0 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--text-sm);
  color: var(--color-text-body);
}
.note-panel__conclusions {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--text-sm);
}
.note-panel__evidence {
  border-top: 1px dashed var(--color-border);
  padding-top: 10px;
}
.note-panel__evidence summary {
  cursor: pointer;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  margin-bottom: 8px;
}
.note-evidence__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.note-evidence__item {
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  cursor: pointer;
  transition: border-color var(--motion-fast), background var(--motion-fast);
}
.note-evidence__item:hover {
  border-color: var(--color-brand);
}
.note-evidence__item.is-active {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.note-evidence__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.note-evidence__time {
  border: 0;
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  border-radius: var(--radius-tag);
  padding: 1px 6px;
  font: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.note-evidence__claim {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-strong);
}
.note-evidence__content {
  margin: 4px 0 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
