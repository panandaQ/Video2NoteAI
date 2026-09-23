<script setup lang="ts">
import Button from '../ui/Button.vue'

const props = defineProps<{ modelValue: string; busy: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: [text: string] }>()

function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    send()
  }
}

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}

function send() {
  const text = props.modelValue.trim()
  if (!text || props.busy) return
  emit('submit', text)
}
</script>

<template>
  <div class="question-input">
    <textarea
      :value="modelValue"
      :disabled="busy"
      :placeholder="busy ? '正在生成上一轮回答…' : '继续追问（Ctrl / ⌘ + Enter 发送）'"
      rows="2"
      @input="onInput"
      @keydown="onKeydown"
    ></textarea>
    <Button :disabled="busy || !modelValue.trim()" @click="send">发送</Button>
  </div>
</template>

<style scoped>
.question-input {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: end;
}
.question-input textarea {
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-bg);
  resize: none;
  min-height: 56px;
}
.question-input textarea:focus {
  outline: none;
  border-color: var(--color-brand);
}
.question-input textarea:disabled {
  opacity: 0.6;
}
</style>
