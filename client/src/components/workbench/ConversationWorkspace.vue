<script setup lang="ts">
import { onMounted, shallowRef, watch } from 'vue'
import type { KnowledgeConversation as KnowledgeConversationState } from '../../composables/useKnowledgeConversation'
import { useMediaConversationHistory } from '../../composables/useMediaConversationHistory'
import Button from '../ui/Button.vue'
import ConversationHistoryPanel from './ConversationHistoryPanel.vue'
import QaPanel from './QaPanel.vue'

const props = defineProps<{
  mediaId: number
  title?: string | null
  conversation: KnowledgeConversationState
}>()

type ConversationTab = 'qa' | 'history'
const activeTab = shallowRef<ConversationTab>('qa')
const actionError = shallowRef('')
const history = useMediaConversationHistory(props.mediaId)

onMounted(() => void history.load())

watch(
  () => props.conversation.version.value,
  (value, previous) => {
    if (value !== previous) void history.load()
  }
)

async function selectConversation(conversationId: number) {
  actionError.value = ''
  try {
    await props.conversation.openConversation(conversationId)
    activeTab.value = 'qa'
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : '会话加载失败'
  }
}

async function createConversation() {
  await props.conversation.newConversation()
  activeTab.value = 'qa'
  await history.load()
}
</script>

<template>
  <section class="conversation-workspace">
    <header class="conversation-workspace__head">
      <div class="conversation-workspace__tabs" role="tablist" aria-label="问答工作区">
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'qa'"
          :class="{ 'is-active': activeTab === 'qa' }"
          @click="activeTab = 'qa'"
        >
          连续问答
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'history'"
          :class="{ 'is-active': activeTab === 'history' }"
          @click="activeTab = 'history'"
        >
          历史会话
          <span v-if="history.items.value.length > 0">{{ history.items.value.length }}</span>
        </button>
      </div>
      <Button
        v-if="activeTab === 'qa'"
        variant="ghost"
        :disabled="props.conversation.turns.value.length === 0"
        @click="createConversation"
      >
        新会话
      </Button>
    </header>

    <p v-if="actionError" class="conversation-workspace__error" role="alert">{{ actionError }}</p>

    <div v-show="activeTab === 'qa'" class="conversation-workspace__page">
      <QaPanel
        :media-id="mediaId"
        :title="title"
        :conversation="conversation"
        :show-header="false"
      />
    </div>
    <div v-show="activeTab === 'history'" class="conversation-workspace__page conversation-workspace__page--history">
      <ConversationHistoryPanel
        :items="history.items.value"
        :loading="history.loading.value"
        :error="history.error.value"
        :active-conversation-id="props.conversation.conversationId.value"
        :disabled="props.conversation.busy.value"
        @select="selectConversation"
      />
    </div>
  </section>
</template>

<style scoped>
.conversation-workspace {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}
.conversation-workspace__head {
  display: flex;
  min-height: 40px;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid var(--color-border);
}
.conversation-workspace__tabs {
  display: flex;
  align-self: stretch;
  gap: 18px;
}
.conversation-workspace__tabs button {
  position: relative;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 1px 10px;
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
}
.conversation-workspace__tabs button::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  border-radius: 2px;
  background: transparent;
  content: '';
}
.conversation-workspace__tabs button.is-active { color: var(--color-brand-strong); }
.conversation-workspace__tabs button.is-active::after { background: var(--color-brand); }
.conversation-workspace__tabs span {
  display: inline-flex;
  min-width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--color-brand-soft);
  font-size: 11px;
}
.conversation-workspace__error {
  margin: 8px 0 0;
  color: var(--color-danger);
  font-size: var(--text-xs);
}
.conversation-workspace__page {
  flex: 1;
  min-height: 0;
  padding-top: 12px;
}
.conversation-workspace__page--history { overflow-y: auto; }
</style>
