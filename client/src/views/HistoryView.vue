<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Button from '../components/ui/Button.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import SkeletonBlock from '../components/ui/SkeletonBlock.vue'
import { useConversationHistory } from '../composables/useConversationHistory'
import type { KnowledgeConversation } from '../types/knowledge'

const router = useRouter()
const history = useConversationHistory()

onMounted(() => void history.load())

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

async function open(item: KnowledgeConversation) {
  const mediaId = await history.prepareOpen(item)
  if (mediaId !== null) {
    await router.push({ name: 'workbench', params: { mediaId: String(mediaId) } })
  }
}

async function remove(item: KnowledgeConversation) {
  if (!window.confirm(`删除“${item.title}”及其全部问答记录？此操作不可撤销。`)) return
  await history.remove(item)
}
</script>

<template>
  <section class="history">
    <header class="history__head">
      <div>
        <h1>历史会话</h1>
        <p>长期记录保存在服务端；本机缓存只用于更快恢复最近会话。</p>
      </div>
      <Button variant="secondary" :loading="history.loading.value" @click="history.load">刷新</Button>
    </header>

    <p v-if="history.error.value" class="history__error" role="alert">{{ history.error.value }}</p>

    <div v-if="history.loading.value" class="history__list" aria-busy="true">
      <SkeletonBlock v-for="n in 5" :key="n" height="92px" radius="var(--radius-card)" />
    </div>

    <EmptyState
      v-else-if="history.items.value.length === 0"
      title="还没有历史会话"
      description="从视频库打开一个已完成处理的视频并开始提问，会话会长期保存在这里。"
    >
      <RouterLink to="/library"><Button>前往视频库</Button></RouterLink>
    </EmptyState>

    <div v-else class="history__list">
      <article v-for="item in history.items.value" :key="item.conversationId" class="history-card">
        <button class="history-card__main" type="button" @click="open(item)">
          <span class="history-card__title">{{ item.title }}</span>
          <span class="history-card__meta">
            {{ item.lastTurnNo }} 轮问答 · 更新于 {{ formatTime(item.updatedAt) }}
          </span>
        </button>
        <Button
          variant="ghost"
          :loading="history.deletingId.value === item.conversationId"
          aria-label="删除会话"
          @click="remove(item)"
        >
          删除
        </Button>
      </article>
      <Button
        v-if="history.hasMore.value"
        class="history__more"
        variant="secondary"
        :loading="history.loadingMore.value"
        @click="history.loadMore"
      >
        加载更多
      </Button>
    </div>
  </section>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 900px;
  margin: 0 auto;
}
.history__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}
.history__head h1 {
  font-size: var(--text-xl);
}
.history__head p,
.history__error {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
}
.history__error {
  color: var(--color-danger);
}
.history__list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.history-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
}
.history-card__main {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 0;
  background: transparent;
  text-align: left;
}
.history-card__main:hover .history-card__title {
  color: var(--color-brand-strong);
}
.history-card__title {
  overflow: hidden;
  max-width: 100%;
  color: var(--color-text-strong);
  font-size: var(--text-md);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-card__meta {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.history__more {
  align-self: center;
}
@media (max-width: 640px) {
  .history__head,
  .history-card {
    align-items: stretch;
  }
  .history__head {
    flex-direction: column;
  }
}
</style>
