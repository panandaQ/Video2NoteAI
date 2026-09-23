<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import HomeContinueCard from '../components/home/HomeContinueCard.vue'
import HomeProcessingPanel from '../components/home/HomeProcessingPanel.vue'
import HomeRecentConversations from '../components/home/HomeRecentConversations.vue'
import HomeRecentVideos from '../components/home/HomeRecentVideos.vue'
import { useConversationHistory } from '../composables/useConversationHistory'
import { useImportTracking } from '../composables/useImportTracking'
import { useAuthStore } from '../stores/auth'
import { useLibraryStore } from '../stores/library'
import type { KnowledgeConversation } from '../types/knowledge'

const router = useRouter()
const auth = useAuthStore()
const library = useLibraryStore()
const history = useConversationHistory()
const imports = useImportTracking(auth.user?.id ?? null)

const recentVideos = computed(() => library.items.slice(0, 4))
const recentConversations = computed(() =>
  history.items.value
    .filter((item) => item.scopeType === 'SINGLE_VIDEO' && item.scopeMediaId !== null)
    .slice(0, 4)
)
const continueConversation = computed(() => recentConversations.value[0] ?? null)
const continueMedia = computed(() => {
  const mediaId = continueConversation.value?.scopeMediaId
  return library.items.find((item) => item.id === mediaId) ?? library.items[0] ?? null
})

onMounted(() => {
  void Promise.all([library.fetchList(), history.load()])
})

async function openMedia(mediaId: number) {
  await router.push({ name: 'workbench', params: { mediaId: String(mediaId) } })
}

async function openConversation(item: KnowledgeConversation) {
  const mediaId = await history.prepareOpen(item)
  if (mediaId !== null) await openMedia(mediaId)
}

async function finishImport(importId: number) {
  imports.forget(importId)
  await library.fetchList()
}
</script>

<template>
  <section class="home">
    <p v-if="library.error || history.error.value" class="home__notice" role="alert">
      {{ library.error || history.error.value }}
    </p>

    <div class="home__grid">
      <HomeContinueCard
        :media="continueMedia"
        :conversation="continueConversation"
        :loading="library.loading || history.loading.value"
        @open="openMedia"
        @browse="router.push('/library')"
      />
      <HomeProcessingPanel
        :import-ids="imports.trackedImportIds.value"
        @finished="finishImport"
        @browse="router.push('/library')"
      />
      <HomeRecentVideos
        :items="recentVideos"
        :loading="library.loading"
        @open="openMedia"
        @browse="router.push('/library')"
      />
      <HomeRecentConversations
        :items="recentConversations"
        :loading="history.loading.value"
        @open="openConversation"
        @browse="router.push('/history')"
      />
    </div>
  </section>
</template>

<style scoped>
.home {
  width: min(1440px, 100%);
  margin: 0 auto;
}
.home__notice {
  margin: 0 0 var(--space-4);
  padding: 10px 13px;
  border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent);
  border-radius: var(--radius-card);
  background: color-mix(in srgb, var(--color-danger) 7%, white);
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.home__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.82fr) minmax(340px, 1fr);
  gap: 20px;
  align-items: stretch;
}
@media (max-width: 1080px) {
  .home__grid { grid-template-columns: 1fr; }
}
</style>
