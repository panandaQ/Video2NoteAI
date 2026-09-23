<script setup lang="ts">
import type { KnowledgeConversation } from '../../types/knowledge'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

defineProps<{ items: KnowledgeConversation[]; loading: boolean }>()
const emit = defineEmits<{ open: [item: KnowledgeConversation]; browse: [] }>()

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value))
}
</script>

<template>
  <section class="recent-qa">
    <header class="recent-qa__head">
      <h2>最近问答</h2>
      <button type="button" @click="emit('browse')">查看全部 <span aria-hidden="true">›</span></button>
    </header>

    <div v-if="loading" class="recent-qa__list">
      <SkeletonBlock v-for="n in 3" :key="n" height="72px" radius="9px" />
    </div>
    <div v-else-if="items.length" class="recent-qa__list">
      <button v-for="item in items" :key="item.conversationId" type="button" @click="emit('open', item)">
        <span class="recent-qa__icon" aria-hidden="true">?</span>
        <span class="recent-qa__content">
          <strong>{{ item.title }}</strong>
          <small>{{ item.lastTurnNo }} 轮问答 · {{ formatDate(item.updatedAt) }}</small>
        </span>
        <span class="recent-qa__arrow" aria-hidden="true">↗</span>
      </button>
    </div>
    <div v-else class="recent-qa__empty">
      <span aria-hidden="true">?</span>
      <p>还没有问答记录。打开一个视频，提出你真正关心的问题。</p>
    </div>
  </section>
</template>

<style scoped>
.recent-qa {
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
}
.recent-qa__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: 14px;
}
.recent-qa__head h2 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 21px;
}
.recent-qa__head > button {
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: #4389e6;
  font-size: var(--text-sm);
}
.recent-qa__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.recent-qa__list > button {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 11px;
  border: 0;
  border-radius: 9px;
  background: #f8f8f5;
  text-align: left;
  transition: background var(--motion-fast), transform var(--motion-fast);
}
.recent-qa__list > button:hover {
  transform: translateX(2px);
  background: var(--color-brand-soft);
}
.recent-qa__icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: #fff;
  color: var(--color-brand-strong);
  font-family: Georgia, serif;
  font-size: 17px;
  box-shadow: 0 2px 9px rgba(34, 57, 48, 0.07);
}
.recent-qa__content {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}
.recent-qa__content strong {
  overflow: hidden;
  color: var(--color-text-strong);
  font-size: var(--text-sm);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-qa__content small {
  color: var(--color-text-muted);
  font-size: 11px;
}
.recent-qa__arrow {
  color: #9aa59f;
  font-size: var(--text-sm);
}
.recent-qa__empty {
  display: flex;
  min-height: 205px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  padding: 24px;
  border-radius: 9px;
  background: #f8f8f5;
  text-align: center;
}
.recent-qa__empty span {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-family: Georgia, serif;
  font-size: 20px;
}
.recent-qa__empty p {
  max-width: 260px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: 1.6;
}
</style>
