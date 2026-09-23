<script setup lang="ts">
import { computed, onMounted, shallowRef, toRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLibraryStore } from '../stores/library'
import { useAuthStore } from '../stores/auth'
import { useLibraryCatalog } from '../composables/useLibraryCatalog'
import LibraryMediaCard from '../components/library/LibraryMediaCard.vue'
import LibraryToolbar, { type LibraryFilter } from '../components/library/LibraryToolbar.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import SkeletonBlock from '../components/ui/SkeletonBlock.vue'
import Button from '../components/ui/Button.vue'
import ImportDialog from '../components/library/ImportDialog.vue'

const library = useLibraryStore()
const mediaItems = toRef(library, 'items')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const catalog = useLibraryCatalog(auth.user?.id ?? null, mediaItems)
const importOpen = shallowRef(false)
const searchQuery = shallowRef('')
const statusFilter = shallowRef<LibraryFilter>('ALL')
const actionError = shallowRef('')

const counts = computed<Record<LibraryFilter, number>>(() => ({
  ALL: catalog.items.value.length,
  READY: catalog.items.value.filter((item) => item.status === 'READY').length,
  PROCESSING: catalog.items.value.filter((item) => item.status === 'PROCESSING').length,
  FAILED: catalog.items.value.filter((item) => item.status === 'FAILED').length
}))

const visibleItems = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  return catalog.items.value.filter((item) => {
    const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
    const matchesQuery = !query || item.title.toLocaleLowerCase().includes(query)
    return matchesStatus && matchesQuery
  })
})

const pageLoading = computed(() => library.loading || catalog.loadingImports.value)
const pageError = computed(() => actionError.value || library.error || catalog.importError.value)

onMounted(() => {
  void loadCatalog()
})

watch(
  () => route.query.import,
  (value) => {
    if (value === '1') importOpen.value = true
  },
  { immediate: true }
)

async function loadCatalog() {
  await Promise.all([library.fetchList(), catalog.loadTrackedImports()])
}

function open(mediaId: number) {
  void router.push({ name: 'workbench', params: { mediaId: String(mediaId) } })
}

async function closeImport() {
  importOpen.value = false
  if (route.query.import === '1') await router.replace({ path: '/library' })
}

async function onImportAccepted(importId: number) {
  await closeImport()
  await catalog.trackImport(importId)
}

async function refreshImport() {
  await loadCatalog()
}

async function retryImport(importId: number) {
  actionError.value = ''
  try {
    await catalog.retryImport(importId)
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : '任务重试失败'
  }
}
</script>

<template>
  <section class="library">
    <header class="library__head">
      <div>
        <h1>视频库</h1>
        <p>管理你的视频资源，查看知识内容的处理状态。</p>
      </div>
      <Button class="library__import" @click="importOpen = true">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
          <path d="M12 15V3m0 0L7.5 7.5M12 3l4.5 4.5M5 14v5h14v-5" />
        </svg>
        导入视频
      </Button>
    </header>

    <LibraryToolbar v-model:query="searchQuery" v-model:status="statusFilter" :counts="counts" />

    <p v-if="pageError" class="library__error" role="alert">{{ pageError }}</p>

    <div v-if="pageLoading && catalog.items.value.length === 0" class="library__grid" aria-busy="true">
      <div v-for="n in 6" :key="n" class="library__skeleton">
        <SkeletonBlock height="180px" radius="var(--radius-card)" />
        <SkeletonBlock height="18px" />
        <SkeletonBlock width="60%" height="14px" />
      </div>
    </div>

    <div v-else-if="catalog.items.value.length === 0" class="library__state">
      <EmptyState
        title="视频库还是空的"
        description="提交一个 B 站视频链接，系统完成字幕、笔记与知识索引后，你就可以进入工作台连续追问。"
      >
        <Button @click="importOpen = true">导入第一个视频</Button>
      </EmptyState>
    </div>

    <div v-else-if="visibleItems.length === 0" class="library__state">
      <EmptyState title="没有符合条件的视频" description="可以更换状态筛选，或清空搜索关键词后重试。" />
    </div>

    <div v-else class="library__grid">
      <LibraryMediaCard
        v-for="item in visibleItems"
        :key="item.key"
        :item="item"
        @open="open"
        @refresh="refreshImport"
        @retry="retryImport"
      />
    </div>

    <ImportDialog :open="importOpen" @close="closeImport" @accepted="onImportAccepted" />
  </section>
</template>

<style scoped>
.library {
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: min(1440px, 100%);
  margin: 0 auto;
}
.library__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.library__head h1 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 28px;
  letter-spacing: -0.025em;
}
.library__head p {
  margin: 7px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.library__import {
  background: #43ad50;
}
.library__import:hover:not(:disabled) { background: #369641; }
.library__import svg { stroke-linecap: round; stroke-linejoin: round; }
.library__error {
  margin: -6px 0 0;
  padding: 9px 12px;
  border: 1px solid #ecc8c2;
  border-radius: var(--radius-control);
  background: #faece9;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.library__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}
.library__skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
}
.library__state {
  margin-top: 24px;
}
@media (max-width: 1050px) {
  .library__grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .library__head { align-items: flex-start; }
  .library__grid { grid-template-columns: 1fr; }
}
</style>
