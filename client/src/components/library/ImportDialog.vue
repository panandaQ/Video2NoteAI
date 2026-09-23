<script setup lang="ts">
import { ref, watch } from 'vue'
import { importApi } from '../../api/endpoints'
import Modal from '../ui/Modal.vue'
import Button from '../ui/Button.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; accepted: [importId: number] }>()

const url = ref('')
const quality = ref<number>(480)
const loading = ref(false)
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      url.value = ''
      error.value = ''
    }
  }
)

async function submit() {
  const target = url.value.trim()
  if (loading.value || !target) return
  loading.value = true
  error.value = ''
  try {
    const result = await importApi.create(target, quality.value)
    emit('accepted', result.importId)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '提交失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="导入视频" @close="emit('close')">
    <form class="import-form" @submit.prevent="submit">
      <label class="import-form__field">
        <span>视频链接</span>
        <input
          v-model="url"
          type="text"
          inputmode="url"
          placeholder="https://www.bilibili.com/video/BV..."
          :disabled="loading"
        />
      </label>
      <label class="import-form__field">
        <span>清晰度</span>
        <select v-model.number="quality" :disabled="loading">
          <option :value="360">360P</option>
          <option :value="480">480P</option>
          <option :value="720">720P</option>
          <option :value="1080">1080P</option>
        </select>
      </label>
      <p class="import-form__hint">当前支持 B 站普通单 P，或明确指定分 P（?p=n）。</p>
      <p v-if="error" class="import-form__error" role="alert">{{ error }}</p>
      <div class="import-form__actions">
        <Button variant="secondary" :disabled="loading" @click="emit('close')">取消</Button>
        <Button type="submit" :loading="loading" :disabled="!url.trim()">提交</Button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.import-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.import-form__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--text-sm);
}
.import-form__field input,
.import-form__field select {
  padding: 9px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-bg);
}
.import-form__field input:focus,
.import-form__field select:focus {
  outline: none;
  border-color: var(--color-brand);
}
.import-form__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}
.import-form__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.import-form__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}
</style>
