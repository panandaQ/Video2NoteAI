<script setup lang="ts">
withDefaults(
  defineProps<{ tone?: 'brand' | 'success' | 'warning' | 'danger' | 'processing' | 'neutral' }>(),
  { tone: 'neutral' }
)
</script>

<template>
  <span class="badge" :class="`badge--${tone}`"><slot /></span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-tag);
  font-size: var(--text-xs);
  line-height: 1.5;
  border: 1px solid transparent;
  white-space: nowrap;
}
.badge--brand {
  color: var(--color-brand-strong);
  background: var(--color-brand-soft);
  border-color: var(--color-brand);
}
.badge--success {
  color: var(--color-success);
  background: #edf6f0;
  border-color: #bfdfcd;
}
.badge--warning {
  color: var(--color-warning);
  background: #fbf3e4;
  border-color: #ecd9b4;
}
.badge--danger {
  color: var(--color-danger);
  background: #faece9;
  border-color: #ecc8c2;
}
.badge--processing {
  color: var(--color-processing);
  background: #eef1f4;
  border-color: #d3dae1;
}
.badge--neutral {
  color: var(--color-text-muted);
  background: var(--color-surface);
  border-color: var(--color-border);
}
</style>
