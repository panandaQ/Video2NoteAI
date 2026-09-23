<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { KnowledgeConversation } from '../../composables/useKnowledgeConversation'
import TurnMessage from './TurnMessage.vue'
import QuestionInput from './QuestionInput.vue'
import Button from '../ui/Button.vue'
import Badge from '../ui/Badge.vue'

const props = defineProps<{
  mediaId: number
  title?: string | null
  conversation: KnowledgeConversation
  showHeader?: boolean
}>()

const conversation = props.conversation
const draft = ref('')
const DRAFT_SAVE_DELAY_MS = 350
let draftTimer: ReturnType<typeof setTimeout> | null = null
let draftRevision = 0
let disposed = false

const suggestions = [
  '这个视频的核心结论是什么？',
  '视频中提到了哪些关键方法？',
  '作者给出的主要论据有哪些？'
]

const showSuggestions = computed(() => conversation.turns.value.length === 0 && !conversation.busy.value)
const processingTurn = computed(() => conversation.turns.value.find((turn) => turn.status === 'PROCESSING'))

onMounted(async () => {
  const revision = draftRevision
  const savedDraft = await conversation.getDraft()
  if (!disposed && revision === draftRevision) draft.value = savedDraft
})

function onDraft(text: string) {
  draft.value = text
  draftRevision += 1
  if (draftTimer !== null) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => {
    draftTimer = null
    void conversation.setDraft(draft.value)
  }, DRAFT_SAVE_DELAY_MS)
}

function flushDraft() {
  if (draftTimer !== null) {
    clearTimeout(draftTimer)
    draftTimer = null
  }
  return conversation.setDraft(draft.value)
}

async function onSubmit(text: string) {
  await conversation.submit(text)
  if (!conversation.submitError.value) onDraft('')
}

async function pickSuggestion(question: string) {
  await conversation.submit(question)
}

onBeforeUnmount(() => {
  disposed = true
  void flushDraft()
})
</script>

<template>
  <section class="qa-panel">
    <header v-if="showHeader !== false" class="qa-panel__head">
      <div>
        <h3 class="qa-panel__title">AI 问答</h3>
        <p class="qa-panel__sub">{{ title || `视频 #${mediaId}` }}</p>
      </div>
      <Button
        variant="ghost"
        :disabled="conversation.turns.value.length === 0"
        @click="void conversation.newConversation()"
      >
        新会话
      </Button>
    </header>

    <div class="qa-panel__list">
      <div v-if="showSuggestions" class="qa-panel__suggestions">
        <p class="qa-panel__suggestions-hint">可以这样开始：</p>
        <button
          v-for="suggestion in suggestions"
          :key="suggestion"
          type="button"
          class="suggestion-chip"
          @click="pickSuggestion(suggestion)"
        >
          {{ suggestion }}
        </button>
      </div>

      <TurnMessage
        v-for="turn in conversation.turns.value"
        :key="turn.turnId"
        :turn="turn"
        @regenerate="(turnId: number) => void conversation.regenerate(turnId)"
      />

      <div v-if="processingTurn" class="qa-panel__busy">
        <Badge tone="processing">第 {{ processingTurn.turnNo }} 轮生成中</Badge>
      </div>
    </div>

    <p v-if="conversation.submitError.value" class="qa-panel__error" role="alert">
      {{ conversation.submitError.value }}
    </p>

    <QuestionInput
      :model-value="draft"
      :busy="conversation.busy.value"
      @update:model-value="onDraft"
      @submit="(text: string) => void onSubmit(text)"
    />
  </section>
</template>

<style scoped>
.qa-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.qa-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.qa-panel__title {
  font-size: var(--text-md);
}
.qa-panel__sub {
  margin: 2px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qa-panel__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-right: 4px;
}
.qa-panel__suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-card);
}
.qa-panel__suggestions-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.suggestion-chip {
  align-self: flex-start;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-brand-strong);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: var(--text-sm);
  text-align: left;
  transition: border-color var(--motion-fast), background var(--motion-fast);
}
.suggestion-chip:hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}
.qa-panel__busy {
  display: flex;
  align-items: center;
}
.qa-panel__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
</style>
