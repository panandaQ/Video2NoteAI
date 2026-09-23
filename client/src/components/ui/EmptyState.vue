<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="empty">
    <div class="empty__mark" aria-hidden="true"></div>
    <h3 class="empty__title">{{ title }}</h3>
    <p v-if="description" class="empty__desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--space-2);
  padding: 64px 20px;
}
.empty__mark {
  width: 40px;
  height: 40px;
  border: 1.5px dashed var(--color-border);
  border-radius: 50%;
  margin-bottom: var(--space-2);
}
.empty__title {
  font-size: var(--text-md);
}
.empty__desc {
  margin: 0;
  color: var(--color-text-muted);
  max-width: 420px;
}
.empty__action {
  margin-top: var(--space-3);
}
</style>
