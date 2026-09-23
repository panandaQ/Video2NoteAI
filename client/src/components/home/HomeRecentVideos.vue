<script setup lang="ts">
import type { MediaSummary } from '../../types/media'
import { formatClock } from '../../utils/format'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

defineProps<{ items: MediaSummary[]; loading: boolean }>()
const emit = defineEmits<{ open: [mediaId: number]; browse: [] }>()
</script>

<template>
  <section class="recent-videos panel">
    <header class="panel__head">
      <h2>最近视频</h2>
      <button type="button" @click="emit('browse')">查看全部 <span aria-hidden="true">→</span></button>
    </header>

    <div v-if="loading" class="recent-videos__grid">
      <SkeletonBlock v-for="n in 4" :key="n" height="176px" radius="10px" />
    </div>
    <div v-else-if="items.length" class="recent-videos__grid">
      <button
        v-for="(item, index) in items"
        :key="item.id"
        class="video-tile"
        type="button"
        @click="emit('open', item.id)"
      >
        <span class="video-tile__cover" :class="`video-tile__cover--${index % 4}`">
          <img v-if="item.coverUrl" :src="item.coverUrl" :alt="`${item.title}封面`" />
          <span v-else class="video-tile__monogram" aria-hidden="true">{{ item.title.slice(0, 1) }}</span>
          <span v-if="item.durationMs" class="video-tile__duration">{{ formatClock(item.durationMs) }}</span>
        </span>
        <span class="video-tile__title">{{ item.title }}</span>
        <span class="video-tile__meta">{{ item.author || item.platform || '已生成知识笔记' }}</span>
      </button>
    </div>
    <button v-else class="recent-videos__empty" type="button" @click="emit('browse')">
      <span aria-hidden="true">＋</span>
      <strong>导入第一个视频</strong>
      <small>把口播内容变成可追问的知识</small>
    </button>
  </section>
</template>

<style scoped>
.panel {
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
}
.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: 18px;
}
.panel__head h2 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 21px;
}
.panel__head button {
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: #4389e6;
  font-size: var(--text-sm);
}
.recent-videos__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.video-tile {
  display: flex;
  min-width: 0;
  align-items: stretch;
  flex-direction: column;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
}
.video-tile__cover {
  position: relative;
  display: grid;
  overflow: hidden;
  aspect-ratio: 16 / 10;
  place-items: center;
  border-radius: 9px;
  background: linear-gradient(145deg, #d9e8dd, #8db9a1);
  transition: transform var(--motion-fast), box-shadow var(--motion-fast);
}
.video-tile:hover .video-tile__cover {
  transform: translateY(-2px);
  box-shadow: 0 9px 22px rgba(32, 62, 50, 0.13);
}
.video-tile__cover--1 { background: linear-gradient(145deg, #e9dfcf, #c4a982); }
.video-tile__cover--2 { background: linear-gradient(145deg, #dfe5ec, #8ea5ba); }
.video-tile__cover--3 { background: linear-gradient(145deg, #eadfdc, #bb8f82); }
.video-tile__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.video-tile__monogram {
  color: rgba(255, 255, 255, 0.9);
  font-family: Georgia, serif;
  font-size: 30px;
  font-weight: 700;
}
.video-tile__duration {
  position: absolute;
  right: 7px;
  bottom: 7px;
  padding: 2px 5px;
  border-radius: 3px;
  background: rgba(18, 24, 21, 0.76);
  color: #fff;
  font-size: 10px;
}
.video-tile__title {
  overflow: hidden;
  margin-top: 9px;
  color: var(--color-text-strong);
  font-size: var(--text-sm);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.video-tile__meta {
  overflow: hidden;
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-videos__empty {
  display: flex;
  width: 100%;
  min-height: 176px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 5px;
  border: 1px dashed #cfd9d2;
  border-radius: 10px;
  background: #fafbf9;
  color: var(--color-text-muted);
}
.recent-videos__empty > span {
  color: var(--color-brand-strong);
  font-size: 25px;
}
.recent-videos__empty strong { color: var(--color-text-strong); }
.recent-videos__empty small { font-size: var(--text-xs); }
@media (max-width: 760px) {
  .recent-videos__grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
