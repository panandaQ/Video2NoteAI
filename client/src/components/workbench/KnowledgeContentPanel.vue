<script setup lang="ts">
import { shallowRef } from 'vue'
import type { MediaNoteState } from '../../composables/useMediaNote'
import type { MediaTranscriptState } from '../../composables/useMediaTranscript'
import type { TimelineEntry } from './EvidenceTimeline.vue'
import EvidenceTimeline from './EvidenceTimeline.vue'
import NotePanel from './NotePanel.vue'
import TranscriptPanel from './TranscriptPanel.vue'

defineProps<{
  mediaNote: MediaNoteState
  transcript: MediaTranscriptState
  timelineEntries: TimelineEntry[]
}>()

type ContentTab = 'note' | 'transcript'
const activeTab = shallowRef<ContentTab>('note')
</script>

<template>
  <section class="knowledge-content">
    <header class="knowledge-content__tabs" role="tablist" aria-label="视频知识内容">
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'note'"
        :class="{ 'is-active': activeTab === 'note' }"
        @click="activeTab = 'note'"
      >
        智能笔记
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'transcript'"
        :class="{ 'is-active': activeTab === 'transcript' }"
        @click="activeTab = 'transcript'"
      >
        完整字幕
      </button>
    </header>

    <div v-show="activeTab === 'note'" class="knowledge-content__page">
      <NotePanel :media-note="mediaNote" :show-header="false" />
      <EvidenceTimeline :entries="timelineEntries" />
    </div>
    <div v-show="activeTab === 'transcript'" class="knowledge-content__page">
      <TranscriptPanel :transcript="transcript" />
    </div>
  </section>
</template>

<style scoped>
.knowledge-content {
  min-height: 100%;
  overflow: visible;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
}
.knowledge-content__tabs {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 28px;
  min-height: 52px;
  padding: 0 18px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}
.knowledge-content__tabs button {
  position: relative;
  align-self: stretch;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
}
.knowledge-content__tabs button::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  border-radius: 2px;
  background: transparent;
  content: '';
}
.knowledge-content__tabs button.is-active { color: var(--color-brand-strong); }
.knowledge-content__tabs button.is-active::after { background: var(--color-brand); }
.knowledge-content__page { min-width: 0; }

@media (min-width: 1280px) {
  .knowledge-content {
    display: flex;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    flex-direction: column;
  }

  .knowledge-content__tabs {
    position: static;
    flex: 0 0 auto;
  }

  .knowledge-content__page {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }
}
</style>
