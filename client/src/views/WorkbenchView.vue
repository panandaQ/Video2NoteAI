<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useLibraryStore } from '../stores/library'
import { useWorkbenchStore } from '../stores/workbench'
import { useKnowledgeConversation } from '../composables/useKnowledgeConversation'
import { useMediaNote } from '../composables/useMediaNote'
import { useMediaTranscript } from '../composables/useMediaTranscript'
import PlayerPanel from '../components/workbench/PlayerPanel.vue'
import ChapterList from '../components/workbench/ChapterList.vue'
import KnowledgeContentPanel from '../components/workbench/KnowledgeContentPanel.vue'
import ConversationWorkspace from '../components/workbench/ConversationWorkspace.vue'
import Badge from '../components/ui/Badge.vue'
import type { TimelineEntry } from '../components/workbench/EvidenceTimeline.vue'

const route = useRoute()
const mediaId = Number(route.params.mediaId)

const library = useLibraryStore()
const workbench = useWorkbenchStore()
const conversation = useKnowledgeConversation(mediaId)
const mediaNote = useMediaNote(mediaId)
const mediaTranscript = useMediaTranscript(mediaId)

const media = computed(() => library.items.find((item) => item.id === mediaId))

const timelineEntries = computed<TimelineEntry[]>(() =>
  conversation.turns.value.flatMap((turn) =>
    turn.evidence.map((evidence) => ({
      rank: evidence.rank,
      turnNo: turn.turnNo,
      startMs: evidence.startMs,
      endMs: evidence.endMs,
      source: evidence.source,
      snippet: evidence.snippet
    }))
  )
)

onMounted(async () => {
  if (library.items.length === 0) {
    await library.fetchList()
  }
  await Promise.all([conversation.restore(), mediaNote.load(), mediaTranscript.load()])
})

onBeforeUnmount(() => {
  conversation.dispose()
  workbench.highlightEvidence(null)
  workbench.seekTo(0)
})
</script>

<template>
  <section class="workbench">
    <header class="workbench__head">
      <RouterLink to="/library" class="workbench__back">← 返回视频库</RouterLink>
      <h1 class="workbench__title">{{ media?.title || `视频 #${mediaId}` }}</h1>
      <Badge tone="success">可学习</Badge>
    </header>

    <div class="workbench__columns">
      <div class="workbench__col workbench__col--left">
        <PlayerPanel :media-id="mediaId" :title="media?.title" />
        <ChapterList :sections="mediaNote.note.value?.sections ?? []" />
      </div>
      <div class="workbench__col workbench__col--mid">
        <KnowledgeContentPanel
          :media-note="mediaNote"
          :transcript="mediaTranscript"
          :timeline-entries="timelineEntries"
        />
      </div>
      <div class="workbench__col workbench__col--right">
        <ConversationWorkspace
          :media-id="mediaId"
          :title="media?.title"
          :conversation="conversation"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: calc(100vh - 148px);
  min-height: 520px;
  width: 100%;
  max-width: 1680px;
  margin: 0 auto;
}
.workbench__head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 32px;
}
.workbench__back {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  flex-shrink: 0;
}
.workbench__back:hover {
  color: var(--color-brand-strong);
}
.workbench__title {
  font-size: var(--text-lg);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workbench__columns {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns:
    clamp(250px, 19vw, 300px)
    minmax(0, 1fr)
    clamp(320px, 24vw, 380px);
  gap: 16px;
}
.workbench__col {
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  padding: 14px;
}
.workbench__col--mid {
  background: transparent;
  border-color: transparent;
  padding: 0;
}

@media (max-width: 1279px) {
  .workbench {
    height: auto;
    min-height: 0;
  }

  .workbench__columns {
    grid-template-columns: minmax(240px, 0.78fr) minmax(0, 1.9fr);
  }

  .workbench__col {
    max-height: 720px;
  }

  .workbench__col--right {
    grid-column: 1 / -1;
    min-height: 560px;
  }
}

@media (max-width: 767px) {
  .workbench__head {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .workbench__title {
    order: 3;
    width: 100%;
  }

  .workbench__columns {
    grid-template-columns: minmax(0, 1fr);
  }

  .workbench__col {
    max-height: none;
    overflow-y: visible;
  }

  .workbench__col--right {
    grid-column: auto;
    min-height: 620px;
  }
}
</style>
