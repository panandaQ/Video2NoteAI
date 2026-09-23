<script setup lang="ts">
import ImportProgressStrip from '../library/ImportProgressStrip.vue'

defineProps<{ importIds: readonly number[] }>()
const emit = defineEmits<{ finished: [importId: number]; browse: [] }>()
</script>

<template>
  <section class="processing-panel">
    <header class="panel-head">
      <h2>正在处理</h2>
      <span v-if="importIds.length" class="panel-count">{{ importIds.length }}</span>
      <button v-else type="button" class="panel-link" @click="emit('browse')">查看全部 <span aria-hidden="true">›</span></button>
    </header>

    <div v-if="importIds.length" class="processing-panel__tasks">
      <ImportProgressStrip
        v-for="importId in importIds.slice(0, 3)"
        :key="importId"
        :import-id="importId"
        @finished="emit('finished', $event)"
      />
    </div>

    <div v-else class="processing-panel__ready">
      <div class="processing-panel__orb" aria-hidden="true">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M5 12.5l4.2 4L19 7" />
        </svg>
      </div>
      <h3>内容都已准备好</h3>
      <p>新导入的视频会在这里显示字幕、笔记与知识索引的处理进度。</p>
      <button type="button" @click="emit('browse')">导入或查看视频 <span aria-hidden="true">→</span></button>
    </div>
  </section>
</template>

<style scoped>
.processing-panel {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}
.panel-head h2 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 21px;
}
.panel-link {
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: #4389e6;
  font-size: var(--text-sm);
}
.panel-count {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-brand-strong);
  color: #fff;
  font-size: var(--text-xs);
}
.processing-panel__tasks {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
.processing-panel__tasks :deep(.strip) {
  align-items: flex-start;
  flex-direction: column;
  gap: 7px;
  padding: 13px;
  box-shadow: 0 5px 18px rgba(47, 110, 91, 0.05);
}
.processing-panel__ready {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  min-height: 245px;
  padding: 24px 8px 8px;
  text-align: center;
}
.processing-panel__orb {
  display: grid;
  width: 62px;
  height: 62px;
  place-items: center;
  border: 1px solid rgba(67, 173, 80, 0.28);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.78);
  color: var(--color-brand-strong);
  box-shadow: 0 8px 20px rgba(47, 110, 91, 0.08);
}
.processing-panel__ready h3 {
  margin: 17px 0 6px;
  color: var(--color-text-strong);
  font-size: var(--text-md);
}
.processing-panel__ready p {
  max-width: 270px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: 1.65;
}
.processing-panel__ready button {
  margin-top: 18px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-brand-strong);
  font-size: var(--text-sm);
  font-weight: 600;
}
</style>
