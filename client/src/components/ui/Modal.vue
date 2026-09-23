<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ open: boolean; title: string }>()
const emit = defineEmits<{ close: [] }>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

watch(
  () => props.open,
  (open) => {
    if (open) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  }
)
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="emit('close')">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="title">
        <header class="modal__header">
          <h3>{{ title }}</h3>
          <button class="modal__close" aria-label="关闭" @click="emit('close')">×</button>
        </header>
        <div class="modal__body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="modal__footer">
          <slot name="footer" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(31, 28, 22, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  z-index: 100;
}
.modal {
  width: min(520px, 100%);
  background: var(--color-surface);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-float);
  overflow: hidden;
}
.modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}
.modal__close {
  border: 0;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  color: var(--color-text-muted);
  padding: 4px;
}
.modal__body {
  padding: 20px;
}
.modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: 12px 20px;
  border-top: 1px solid var(--color-border);
}
</style>
