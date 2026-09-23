<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { bilibiliApi } from '../api/endpoints'
import Button from '../components/ui/Button.vue'

const cookie = ref('')
const hasCookie = ref(false)
const updatedAt = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const message = ref('')
const error = ref('')

function formatTime(value: string | null) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

async function loadStatus() {
  loading.value = true
  error.value = ''
  try {
    const status = await bilibiliApi.status()
    hasCookie.value = status.hasCookie
    updatedAt.value = status.updatedAt
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  const target = cookie.value.trim()
  if (!target || saving.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await bilibiliApi.saveCookie(target)
    message.value = '已保存'
    cookie.value = ''
    await loadStatus()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function clear() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await bilibiliApi.clearCookie()
    message.value = '已清除'
    hasCookie.value = false
    updatedAt.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : '清除失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadStatus)
</script>

<template>
  <section class="settings">
    <header class="settings__head">
      <div>
        <h1>设置</h1>
        <p>账号与导入偏好。</p>
      </div>
    </header>

    <div class="settings-card">
      <h2 class="settings-card__title">B 站 Cookie</h2>
      <p class="settings-card__desc">
        粘贴你在 bilibili.com 的登录 Cookie，用于下载需要登录或更高清晰度的视频。支持两种格式：浏览器复制的
        Cookie 串（SESSDATA=...; bili_jct=...）或 cookies.txt 全文。Cookie 会在服务端加密保存，绝不回传明文。
      </p>

      <p v-if="hasCookie" class="settings-card__status">
        已保存{{ updatedAt ? ` · 更新于 ${formatTime(updatedAt)}` : '' }}
      </p>
      <p v-else-if="!loading" class="settings-card__status settings-card__status--empty">尚未保存</p>

      <label class="settings-card__field">
        <span>Cookie 字符串</span>
        <textarea
          v-model="cookie"
          rows="4"
          placeholder="SESSDATA=...; bili_jct=...（或直接粘贴 cookies.txt 全文）"
          :disabled="saving"
        ></textarea>
      </label>

      <p v-if="message" class="settings-card__message" role="status">{{ message }}</p>
      <p v-if="error" class="settings-card__error" role="alert">{{ error }}</p>

      <div class="settings-card__actions">
        <Button variant="secondary" :disabled="saving || !hasCookie" @click="clear">清除</Button>
        <Button :loading="saving" :disabled="!cookie.trim()" @click="save">保存</Button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 720px;
  margin: 0 auto;
}
.settings__head h1 {
  font-size: var(--text-xl);
}
.settings__head p {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
}
.settings-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  background: var(--color-surface);
}
.settings-card__title {
  margin: 0;
  font-size: var(--text-md);
}
.settings-card__desc {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: 1.6;
}
.settings-card__status {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-brand-strong);
}
.settings-card__status--empty {
  color: var(--color-text-muted);
}
.settings-card__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  font-size: var(--text-sm);
}
.settings-card__field textarea {
  resize: vertical;
  padding: 9px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-bg);
  font-family: inherit;
  font-size: var(--text-sm);
}
.settings-card__field textarea:focus {
  outline: none;
  border-color: var(--color-brand);
}
.settings-card__message {
  margin: 0;
  color: var(--color-success);
  font-size: var(--text-sm);
}
.settings-card__error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.settings-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
</style>
