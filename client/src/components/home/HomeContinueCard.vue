<script setup lang="ts">
import type { KnowledgeConversation } from '../../types/knowledge'
import type { MediaSummary } from '../../types/media'
import { formatClock } from '../../utils/format'
import Button from '../ui/Button.vue'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

defineProps<{
  media: MediaSummary | null
  conversation: KnowledgeConversation | null
  loading: boolean
}>()

const emit = defineEmits<{
  open: [mediaId: number]
  browse: []
}>()
</script>

<template>
  <article class="continue-card">
    <div class="continue-card__head">
      <h2>继续学习</h2>
    </div>

    <SkeletonBlock v-if="loading" height="226px" radius="12px" />

    <div v-else-if="media" class="continue-card__body">
      <div class="continue-card__cover">
        <img v-if="media.coverUrl" :src="media.coverUrl" :alt="`${media.title}封面`" />
        <div v-else class="continue-card__cover-fallback" aria-hidden="true">
          <span class="continue-card__play">▶</span>
          <span>VIDEO NOTE</span>
        </div>
        <span v-if="media.durationMs" class="continue-card__duration">{{ formatClock(media.durationMs) }}</span>
      </div>

      <div class="continue-card__content">
        <h3>{{ media.title }}</h3>
        <div class="continue-card__meta">
          <span>{{ media.author || media.platform || '我的视频' }}</span>
        </div>
        <div class="continue-card__progress">
          <span class="continue-card__progress-bar" />
        </div>
        <div class="continue-card__progress-meta">
          <span>{{ conversation ? `已完成 ${conversation.lastTurnNo} 轮问答` : '知识笔记已就绪' }}</span>
          <span v-if="conversation">{{ new Date(conversation.updatedAt).toLocaleDateString('zh-CN') }}</span>
        </div>
        <p>{{ conversation?.title || '打开视频笔记，从字幕、章节与时间点中继续提问。' }}</p>
        <Button class="continue-card__action" @click="emit('open', media.id)">
          {{ conversation ? '继续学习' : '开始学习' }}
          <span aria-hidden="true">→</span>
        </Button>
      </div>
    </div>

    <div v-else class="continue-card__empty">
      <span class="continue-card__empty-icon" aria-hidden="true">＋</span>
      <div>
        <h3>从第一个视频开始</h3>
        <p>导入视频后，这里会自动接续最近的学习进度。</p>
      </div>
      <Button @click="emit('browse')">前往视频库</Button>
    </div>
  </article>
</template>

<style scoped>
.continue-card {
  min-width: 0;
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
}
.continue-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: 18px;
}
.continue-card__head h2 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 21px;
  letter-spacing: -0.01em;
}
.continue-card__body {
  display: grid;
  grid-template-columns: minmax(230px, 0.92fr) minmax(240px, 1.08fr);
  min-height: 226px;
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}
.continue-card__cover {
  position: relative;
  min-height: 226px;
  overflow: hidden;
  background: linear-gradient(145deg, #244d42 0%, #4c8e73 58%, #9bc9ad 100%);
}
.continue-card__cover::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent 55%, rgba(16, 44, 35, 0.25));
  content: '';
}
.continue-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.continue-card__cover-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: rgba(255, 255, 255, 0.74);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
}
.continue-card__play {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.45);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 15px;
  padding-left: 2px;
  backdrop-filter: blur(4px);
}
.continue-card__duration {
  position: absolute;
  z-index: 1;
  right: 12px;
  bottom: 12px;
  padding: 3px 7px;
  border-radius: 4px;
  background: rgba(20, 27, 24, 0.78);
  color: white;
  font-size: 11px;
}
.continue-card__content {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  flex-direction: column;
  padding: 22px 24px;
}
.continue-card__content h3 {
  display: -webkit-box;
  overflow: hidden;
  margin: 4px 0 7px;
  color: var(--color-text-strong);
  font-size: 20px;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.continue-card__content p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.continue-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 0;
  color: var(--color-text-muted);
  font-size: 11px;
}
.continue-card__progress {
  width: 100%;
  height: 7px;
  overflow: hidden;
  margin-top: 22px;
  border-radius: 999px;
  background: #e7e9e6;
}
.continue-card__progress-bar {
  display: block;
  width: 72%;
  height: 100%;
  border-radius: inherit;
  background: #43ad50;
}
.continue-card__progress-meta {
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: 7px;
  color: var(--color-text-muted);
  font-size: 11px;
}
.continue-card__content p { margin-top: 16px; }
.continue-card__action { margin-top: auto; }
.continue-card__empty {
  display: flex;
  min-height: 226px;
  align-items: center;
  gap: var(--space-4);
  padding: 28px;
  border: 1px dashed #cfd9d2;
  border-radius: 12px;
  background: linear-gradient(135deg, #f6faf7, #f3f1eb);
}
.continue-card__empty-icon {
  display: grid;
  width: 52px;
  height: 52px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-size: 25px;
}
.continue-card__empty div { flex: 1; }
.continue-card__empty h3,
.continue-card__empty p { margin: 0; }
.continue-card__empty p {
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
@media (max-width: 720px) {
  .continue-card__body { grid-template-columns: 1fr; }
  .continue-card__cover { min-height: 180px; }
  .continue-card__content { gap: var(--space-2); }
  .continue-card__action { margin-top: 10px; }
}
</style>
