<script setup lang="ts">
import Spinner from './Spinner.vue'

withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
    disabled?: boolean
    loading?: boolean
    type?: 'button' | 'submit'
  }>(),
  { variant: 'primary', disabled: false, loading: false, type: 'button' }
)

const emit = defineEmits<{ click: [event: MouseEvent] }>()
</script>

<template>
  <button
    class="btn"
    :class="[`btn--${variant}`, { 'is-loading': loading }]"
    :type="type"
    :disabled="disabled || loading"
    @click="emit('click', $event)"
  >
    <Spinner v-if="loading" :size="14" />
    <slot />
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: 8px 16px;
  border-radius: var(--radius-control);
  border: 1px solid transparent;
  font-size: var(--text-base);
  line-height: 1.4;
  transition:
    background var(--motion-fast),
    border-color var(--motion-fast),
    color var(--motion-fast);
}
.btn--primary {
  background: var(--color-brand-strong);
  color: #fff;
}
.btn--primary:hover:not(:disabled) {
  background: #265c4b;
}
.btn--secondary {
  background: var(--color-surface);
  border-color: var(--color-border);
  color: var(--color-text-body);
}
.btn--secondary:hover:not(:disabled) {
  border-color: var(--color-brand);
  color: var(--color-brand-strong);
}
.btn--ghost {
  background: transparent;
  color: var(--color-brand-strong);
}
.btn--ghost:hover:not(:disabled) {
  background: var(--color-brand-soft);
}
.btn--danger {
  background: var(--color-danger);
  color: #fff;
}
.btn--danger:hover:not(:disabled) {
  background: #9c2e24;
}
.btn:disabled {
  opacity: 0.5;
}
</style>
