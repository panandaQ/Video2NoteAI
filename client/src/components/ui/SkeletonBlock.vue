<script setup lang="ts">
withDefaults(
  defineProps<{ width?: string; height?: string; radius?: string }>(),
  { width: '100%', height: '16px', radius: 'var(--radius-control)' }
)
</script>

<template>
  <span class="skeleton" :style="{ width, height, borderRadius: radius }" aria-hidden="true"></span>
</template>

<style scoped>
.skeleton {
  display: block;
  background: linear-gradient(90deg, #eceae4 25%, #f3f1ec 50%, #eceae4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease infinite;
}
@keyframes shimmer {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
</style>
