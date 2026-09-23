<script setup lang="ts">
import type { MediaTranscriptState } from '../../composables/useMediaTranscript'
import { useWorkbenchStore } from '../../stores/workbench'
import { formatClock } from '../../utils/format'
import { sourceLabel } from '../../utils/source'
import Button from '../ui/Button.vue'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

const props = defineProps<{ transcript: MediaTranscriptState }>()
const workbench = useWorkbenchStore()

function isActive(startMs: number, endMs: number) {
  return workbench.playbackTimeMs >= startMs && workbench.playbackTimeMs < endMs
}

function seek(startMs: number) {
  workbench.seekTo(startMs)
}
</script>

<template>
  <section class="transcript-panel">
    <div v-if="props.transcript.loading.value" class="transcript-panel__state" aria-busy="true">
      <SkeletonBlock v-for="n in 8" :key="n" :height="n % 3 === 0 ? '42px' : '58px'" />
    </div>

    <div v-else-if="props.transcript.error.value" class="transcript-panel__state">
      <p class="transcript-panel__error">{{ props.transcript.error.value }}</p>
      <Button variant="secondary" @click="props.transcript.load">重新加载</Button>
    </div>

    <div v-else-if="!props.transcript.available.value" class="transcript-panel__empty">
      <p>该视频暂时没有可展示的字幕。</p>
      <span>字幕或语音识别完成后会显示在这里。</span>
    </div>

    <ol v-else class="transcript-panel__list">
      <li
        v-for="segment in props.transcript.segments.value"
        :key="`${segment.startMs}-${segment.endMs}`"
        class="transcript-row"
        :class="{ 'is-active': isActive(segment.startMs, segment.endMs) }"
      >
        <button type="button" class="transcript-row__time" @click="seek(segment.startMs)">
          {{ formatClock(segment.startMs) }}
        </button>
        <div class="transcript-row__content">
          <p>{{ segment.text }}</p>
          <span>{{ sourceLabel(segment.source) }}</span>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.transcript-panel {
  padding: 16px 18px 22px;
}
.transcript-panel__state {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}
.transcript-panel__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.transcript-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 72px 20px;
  color: var(--color-text-muted);
  text-align: center;
}
.transcript-panel__empty p {
  margin: 0;
  color: var(--color-text-body);
  font-weight: 600;
}
.transcript-panel__empty span { font-size: var(--text-sm); }
.transcript-panel__list {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}
.transcript-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 12px;
  padding: 12px 10px;
  border-bottom: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  transition: background var(--motion-fast);
}
.transcript-row:hover { background: var(--color-bg); }
.transcript-row.is-active { background: var(--color-brand-soft); }
.transcript-row__time {
  align-self: start;
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: var(--color-brand-strong);
  font: inherit;
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.transcript-row__time:hover { text-decoration: underline; }
.transcript-row__content { min-width: 0; }
.transcript-row__content p {
  margin: 0;
  color: var(--color-text-body);
  font-size: var(--text-sm);
  line-height: 1.75;
}
.transcript-row__content span {
  display: inline-block;
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}
</style>
