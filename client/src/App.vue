<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppTopbar from './components/shell/AppTopbar.vue'

const route = useRoute()
const isPlain = computed(() => route.meta.plain === true)
</script>

<template>
  <RouterView v-if="isPlain" />
  <div v-else class="app-shell">
    <AppTopbar />
    <main class="app-content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.app-content {
  flex: 1;
  padding: 24px 32px 40px;
}
@media (max-width: 767px) {
  .app-content {
    padding: 16px;
  }
}
</style>
