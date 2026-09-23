<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { KnowledgeTurn } from '../../types/knowledge'
import { useWorkbenchStore } from '../../stores/workbench'
import Badge from '../ui/Badge.vue'
import Button from '../ui/Button.vue'
import SkeletonBlock from '../ui/SkeletonBlock.vue'
import CitationCard from './CitationCard.vue'

const props = defineProps<{ turn: KnowledgeTurn }>()
const emit = defineEmits<{ regenerate: [turnId: number] }>()
const workbench = useWorkbenchStore()

const isProcessing = computed(() => props.turn.status === 'PROCESSING')
const isFailed = computed(() => props.turn.status === 'FAILED')

function errorText(code: string | null): string {
  switch (code) {
    case 'RETRIEVAL_UNAVAILABLE':
      return '检索服务暂不可用，请重新生成'
    case 'ANSWER_GENERATION_FAILED':
      return '回答生成失败，请重新生成'
    case 'QUESTION_PROCESS_INTERRUPTED':
      return '回答生成被中断，请重新生成'
    case 'QUESTION_QUEUE_FULL':
      return '当前排队已满，请稍后再试'
    default:
      return code ? `回答生成失败（${code}）` : '回答生成失败'
  }
}

/** 正文内 [En] 引用标记渲染为可点击 chip（n 映射 evidence.rank）。 */
const answerHtml = computed(() => {
  const answer = props.turn.answer
  if (!answer) return ''
  const withChips = answer.replace(/\[E(\d+)\]/gi, (match, n: string) => {
    if (props.turn.evidence.some((evidence) => evidence.rank === Number(n))) {
      return `<button type="button" class="cite-chip" data-cite="${n}">[E${n}]</button>`
    }
    return match
  })
  return marked.parse(withChips, { async: false }) as string
})

function onAnswerClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  const chip = target.closest('.cite-chip') as HTMLElement | null
  if (!chip?.dataset.cite) return
  const rank = Number(chip.dataset.cite)
  const evidence = props.turn.evidence.find((item) => item.rank === rank)
  if (!evidence) return
  workbench.highlightEvidence(rank)
  workbench.seekTo(evidence.startMs)
  document.getElementById(`evidence-${props.turn.turnId}-${rank}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
</script>

<template>
  <article class="turn">
    <div class="turn__question">
      <span class="turn__mark">问</span>
      <p class="turn__question-text">{{ turn.question }}</p>
    </div>

    <div v-if="isProcessing" class="turn__processing" aria-busy="true">
      <Badge tone="processing">生成中</Badge>
      <div class="turn__skeletons">
        <SkeletonBlock v-for="n in 3" :key="n" :height="n === 3 ? '10px' : '16px'" :width="n === 3 ? '55%' : '100%'" />
      </div>
    </div>

    <div v-else-if="isFailed" class="turn__failed">
      <Badge tone="danger">失败</Badge>
      <p class="turn__failed-text">{{ errorText(turn.errorCode) }}</p>
      <Button variant="secondary" @click="emit('regenerate', turn.turnId)">重新生成</Button>
    </div>

    <div v-else class="turn__answer">
      <div class="answer-body" v-html="answerHtml" @click="onAnswerClick"></div>
      <div v-if="turn.evidence.length > 0" class="turn__evidence">
        <h4 class="turn__evidence-title">引用证据</h4>
        <CitationCard
          v-for="evidence in turn.evidence"
          :id="`evidence-${turn.turnId}-${evidence.rank}`"
          :key="`${turn.turnId}-${evidence.rank}`"
          :rank="evidence.rank"
          :start-ms="evidence.startMs"
          :end-ms="evidence.endMs"
          :source="evidence.source"
          :snippet="evidence.snippet"
        />
      </div>
    </div>
  </article>
</template>

<style scoped>
.turn {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
}
.turn__question {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.turn__mark {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-tag);
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-size: var(--text-xs);
  font-weight: 600;
}
.turn__question-text {
  margin: 0;
  color: var(--color-text-strong);
}
.turn__processing,
.turn__failed {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}
.turn__skeletons {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.turn__failed-text {
  margin: 0;
  font-size: var(--text-sm);
}
.turn__evidence {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.turn__evidence-title {
  font-size: var(--text-sm);
  color: var(--color-text-muted);
  font-weight: 500;
}
.answer-body :deep(.cite-chip) {
  border: 0;
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  border-radius: var(--radius-tag);
  padding: 0 5px;
  margin: 0 2px;
  font: inherit;
  cursor: pointer;
  vertical-align: baseline;
}
.answer-body :deep(.cite-chip:hover) {
  background: #d7ecdf;
  text-decoration: underline;
}
.answer-body :deep(p) {
  margin: 0 0 8px;
}
.answer-body :deep(p:last-child) {
  margin-bottom: 0;
}
.answer-body :deep(ul),
.answer-body :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.answer-body :deep(code) {
  background: var(--color-bg);
  border-radius: var(--radius-tag);
  padding: 1px 5px;
  font-size: 0.92em;
}
</style>
