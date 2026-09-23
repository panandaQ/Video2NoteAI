<script setup lang="ts">
import { computed } from 'vue'
import type { MediaSummary } from '../../types/media'
import Badge from '../ui/Badge.vue'

const props = defineProps<{ media: MediaSummary }>()
const emit = defineEmits<{ open: [mediaId: number] }>()

const durationLabel = computed(() => {
  const ms = props.media.durationMs
  if (typeof ms !== 'number' || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = String(total % 60).padStart(2, '0')
  return `${minutes}:${seconds}`
})

function open() {
  emit('open', props.media.id)
}
</script>

<template>
  <article
    class="card"
    role="button"
    tabindex="0"
    :aria-label="`打开视频：${media.title || '未命名视频'}`"
    @click="open"
    @keydown.enter="open"
  >
    <div class="card__cover">
      <img v-if="media.coverUrl" :src="media.coverUrl" :alt="media.title" loading="lazy" />
      <div v-else class="card__cover-empty">无封面</div>
      <Badge tone="success" class="card__status">可学习</Badge>
      <span v-if="durationLabel" class="card__duration">{{ durationLabel }}</span>
    </div>
    <div class="card__body">
      <h3 class="card__title">{{ media.title || '未命名视频' }}</h3>
      <p class="card__meta">{{ media.author || '未知作者' }}</p>
    </div>
  </article>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  overflow: hidden;
  cursor: pointer;
  transition: border-color var(--motion-fast), box-shadow var(--motion-fast);
}
.card:hover,
.card:focus-visible {
  border-color: var(--color-brand);
  box-shadow: var(--shadow-overlay);
}
.card__cover {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--color-bg);
}
.card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.card__cover-empty {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.card__status {
  position: absolute;
  top: 8px;
  left: 8px;
}
.card__duration {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 1px 6px;
  border-radius: var(--radius-tag);
  background: rgba(31, 28, 22, 0.72);
  color: #fff;
  font-size: var(--text-xs);
}
.card__body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card__title {
  font-size: var(--text-base);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__meta {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
</style>
