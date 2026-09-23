<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { importApi } from '../../api/endpoints'
import type { LibraryCatalogItem } from '../../composables/useLibraryCatalog'
import { useTaskEvents } from '../../composables/useTaskEvents'
import { formatClock } from '../../utils/format'
import Badge from '../ui/Badge.vue'
import Button from '../ui/Button.vue'

const props = defineProps<{ item: LibraryCatalogItem }>()
const emit = defineEmits<{
  open: [mediaId: number]
  refresh: [importId: number]
  retry: [importId: number]
}>()

const isReady = computed(() => props.item.status === 'READY')
const statusLabel = computed(() => {
  if (props.item.status === 'READY') return '已就绪'
  if (props.item.status === 'FAILED') return '失败'
  return '处理中'
})
const badgeTone = computed(() => {
  if (props.item.status === 'READY') return 'success'
  if (props.item.status === 'FAILED') return 'danger'
  return 'processing'
})
const durationLabel = computed(() => {
  if (props.item.kind !== 'media' || !props.item.media.durationMs) return ''
  return formatClock(props.item.media.durationMs)
})
const importProgress = computed(() => {
  if (props.item.kind !== 'import' || props.item.job.counts.total <= 0) return null
  const finished = props.item.job.counts.completed + props.item.job.counts.failed
  return Math.min(100, Math.round((finished / props.item.job.counts.total) * 100))
})
const processingLabel = computed(() => {
  if (props.item.kind !== 'import') return ''
  const item = props.item.job.items[0]
  if (item?.status === 'ACQUIRING') return '正在获取视频'
  if (item?.status === 'ANALYSIS_QUEUED') return '等待生成字幕与笔记'
  if (item?.status === 'ANALYZING') return '正在生成字幕、笔记与知识索引'
  if (props.item.job.status === 'RESOLVING') return '正在解析视频链接'
  if (props.item.job.status === 'QUEUED' || props.item.job.status === 'PENDING_DISPATCH') return '等待任务调度'
  return '正在准备视频知识内容'
})
const errorLabel = computed(() => {
  if (props.item.kind !== 'import') return ''
  return props.item.job.errorMessage || props.item.job.items[0]?.errorCode || '视频处理失败，请重试'
})
const updatedLabel = computed(() => {
  if (props.item.kind !== 'import') return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(props.item.job.updatedAt))
})

const events = props.item.kind === 'import'
  ? useTaskEvents(importApi.eventsUrl(props.item.job.importId), {
      onTerminal: () => emit('refresh', props.item.kind === 'import' ? props.item.job.importId : 0)
    })
  : null

onMounted(() => {
  if (props.item.status === 'PROCESSING') events?.start()
})

function open() {
  if (props.item.kind === 'media') emit('open', props.item.media.id)
}
</script>

<template>
  <article
    class="media-card"
    :class="[`media-card--${item.status.toLowerCase()}`, { 'is-clickable': isReady }]"
    :tabindex="isReady ? 0 : undefined"
    :role="isReady ? 'button' : undefined"
    :aria-label="isReady ? `打开视频：${item.title}` : undefined"
    @click="open"
    @keydown.enter="open"
  >
    <div class="media-card__cover">
      <img
        v-if="item.kind === 'media' && item.media.coverUrl"
        :src="item.media.coverUrl"
        :alt="item.title"
        loading="lazy"
      />
      <div v-else class="media-card__cover-fallback" aria-hidden="true">
        <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
          <rect x="3" y="4" width="18" height="16" rx="2.5" />
          <path d="m10 8.5 5.5 3.5-5.5 3.5v-7Z" />
        </svg>
        <span>{{ item.status === 'FAILED' ? '处理未完成' : '正在获取视频知识' }}</span>
      </div>
      <Badge :tone="badgeTone" class="media-card__status">
        <span class="media-card__status-dot" />
        {{ statusLabel }}
      </Badge>
      <span v-if="durationLabel" class="media-card__duration">{{ durationLabel }}</span>
    </div>

    <div class="media-card__body">
      <h3 class="media-card__title">{{ item.title }}</h3>

      <template v-if="item.kind === 'media'">
        <p class="media-card__meta">{{ item.media.author || item.media.platform || '已生成知识内容' }}</p>
        <div class="media-card__footer">
          <span>字幕、笔记与索引已就绪</span>
          <span aria-hidden="true">•••</span>
        </div>
      </template>

      <template v-else-if="item.status === 'PROCESSING'">
        <p class="media-card__meta">任务 #{{ item.job.importId }} · {{ updatedLabel }}</p>
        <div class="media-card__progress" :class="{ 'is-indeterminate': importProgress === null || importProgress === 0 }">
          <span v-if="importProgress !== null && importProgress > 0" :style="{ width: `${importProgress}%` }" />
          <span v-else />
        </div>
        <div class="media-card__process-row">
          <span>{{ processingLabel }}</span>
          <strong v-if="importProgress !== null">{{ importProgress }}%</strong>
        </div>
      </template>

      <template v-else>
        <p class="media-card__meta">任务 #{{ item.job.importId }} · {{ updatedLabel }}</p>
        <p class="media-card__error" role="alert">{{ errorLabel }}</p>
        <Button
          v-if="item.job.retryable"
          class="media-card__retry"
          variant="danger"
          @click.stop="emit('retry', item.job.importId)"
        >
          重新处理
        </Button>
      </template>
    </div>
  </article>
</template>

<style scoped>
.media-card {
  display: flex;
  min-width: 0;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
  transition: border-color var(--motion-fast), box-shadow var(--motion-fast), transform var(--motion-fast);
}
.media-card.is-clickable { cursor: pointer; }
.media-card.is-clickable:hover,
.media-card.is-clickable:focus-visible {
  transform: translateY(-2px);
  border-color: var(--color-brand);
  box-shadow: 0 8px 24px rgba(31, 49, 42, 0.1);
  outline: none;
}
.media-card__cover {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  background: #eef2ef;
}
.media-card__cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.media-card__cover-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  background: linear-gradient(135deg, #e8eee9, #d3e2d8);
  color: var(--color-brand-strong);
  font-size: var(--text-xs);
}
.media-card--failed .media-card__cover-fallback {
  background: linear-gradient(135deg, #f3efec, #e9d8d3);
  color: var(--color-danger);
}
.media-card__status {
  position: absolute;
  top: 10px;
  right: 10px;
  backdrop-filter: blur(4px);
}
.media-card__status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.media-card__duration {
  position: absolute;
  right: 9px;
  bottom: 9px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(24, 28, 25, 0.78);
  color: #fff;
  font-size: 11px;
}
.media-card__body {
  display: flex;
  min-height: 146px;
  flex: 1;
  flex-direction: column;
  padding: 14px;
}
.media-card__title {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--color-text-strong);
  font-size: 15px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.media-card__meta {
  overflow: hidden;
  margin: 7px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.media-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: auto;
  padding-top: 14px;
  color: var(--color-text-muted);
  font-size: 11px;
}
.media-card__progress {
  position: relative;
  height: 6px;
  overflow: hidden;
  margin-top: auto;
  border-radius: 999px;
  background: #e3e7e4;
}
.media-card__progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-brand-strong);
}
.media-card__progress.is-indeterminate span {
  width: 38%;
  animation: progress-slide 1.4s ease-in-out infinite;
}
.media-card__process-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  color: var(--color-text-muted);
  font-size: 11px;
}
.media-card__process-row strong { color: var(--color-brand-strong); }
.media-card__error {
  display: -webkit-box;
  overflow: hidden;
  margin: 13px 0 0;
  color: var(--color-danger);
  font-size: var(--text-xs);
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.media-card__retry {
  align-self: flex-end;
  margin-top: auto;
  padding: 6px 12px;
  font-size: var(--text-xs);
}
@keyframes progress-slide {
  from { transform: translateX(-100%); }
  to { transform: translateX(270%); }
}
</style>
