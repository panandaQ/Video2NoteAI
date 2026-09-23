<script setup lang="ts">
import type { KnowledgeConversation } from '../../types/knowledge'
import SkeletonBlock from '../ui/SkeletonBlock.vue'

defineProps<{
  items: KnowledgeConversation[]
  loading: boolean
  error: string
  activeConversationId: number | null
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [conversationId: number] }>()

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}
</script>

<template>
  <section class="conversation-history">
    <div v-if="loading" class="conversation-history__loading" aria-busy="true">
      <SkeletonBlock v-for="n in 5" :key="n" height="74px" />
    </div>
    <p v-else-if="error" class="conversation-history__error">{{ error }}</p>
    <div v-else-if="items.length === 0" class="conversation-history__empty">
      <p>当前视频还没有历史会话</p>
      <span>在“连续问答”中提问后，会话会保存在这里。</span>
    </div>
    <ul v-else class="conversation-history__list">
      <li v-for="item in items" :key="item.conversationId">
        <button
          type="button"
          class="conversation-history__item"
          :class="{ 'is-active': item.conversationId === activeConversationId }"
          :disabled="disabled"
          @click="emit('select', item.conversationId)"
        >
          <span class="conversation-history__title">{{ item.title }}</span>
          <span class="conversation-history__meta">
            {{ item.lastTurnNo }} 轮问答 · {{ formatTime(item.updatedAt) }}
          </span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.conversation-history { min-height: 0; }
.conversation-history__loading {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.conversation-history__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.conversation-history__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 64px 10px;
  color: var(--color-text-muted);
  text-align: center;
}
.conversation-history__empty p {
  margin: 0;
  color: var(--color-text-body);
  font-weight: 600;
}
.conversation-history__empty span { font-size: var(--text-xs); }
.conversation-history__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.conversation-history__item {
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 11px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
  text-align: left;
  cursor: pointer;
}
.conversation-history__item:hover:not(:disabled) {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.conversation-history__item.is-active {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.conversation-history__item:disabled { cursor: not-allowed; opacity: 0.6; }
.conversation-history__title {
  overflow: hidden;
  width: 100%;
  color: var(--color-text-strong);
  font-size: var(--text-sm);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conversation-history__meta {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}
</style>
