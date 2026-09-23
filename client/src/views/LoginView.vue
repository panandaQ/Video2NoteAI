<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api/endpoints'
import { useAuthStore } from '../stores/auth'
import Button from '../components/ui/Button.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '', nickname: '' })
const loading = ref(false)
const message = ref('')
const isError = ref(false)

async function submit() {
  if (loading.value) return
  loading.value = true
  message.value = ''
  try {
    if (mode.value === 'login') {
      const data = await authApi.login({ username: form.username, password: form.password })
      await auth.setSession(data.token, data.userInfo)
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
      await router.replace(redirect)
    } else {
      await authApi.register({ username: form.username, password: form.password, nickname: form.nickname })
      isError.value = false
      message.value = '注册成功，请登录'
      mode.value = 'login'
    }
  } catch (err) {
    isError.value = true
    message.value = err instanceof Error ? err.message : '网络连接错误'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-stage">
    <div class="login-card">
      <header class="login-head">
        <span class="login-brand">Do<span>VideoAI</span></span>
        <p class="login-slogan">安静的知识工作台 · 围绕视频连续追问</p>
      </header>

      <div class="login-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'login'"
          :class="{ 'is-active': mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'register'"
          :class="{ 'is-active': mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <label class="field">
          <span>账号</span>
          <input v-model="form.username" type="text" autocomplete="username" required />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="form.password" type="password" autocomplete="current-password" minlength="8" required />
        </label>
        <label v-if="mode === 'register'" class="field">
          <span>昵称</span>
          <input v-model="form.nickname" type="text" autocomplete="nickname" required />
        </label>

        <p v-if="message" class="login-message" :class="{ 'is-error': isError }" role="alert">
          {{ message }}
        </p>

        <Button type="submit" :loading="loading" class="login-submit">
          {{ mode === 'login' ? '登录' : '注册' }}
        </Button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-stage {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.login-card {
  width: min(400px, 100%);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.login-head {
  text-align: center;
}
.login-brand {
  font-size: var(--text-xl);
  font-weight: 700;
  color: var(--color-text-strong);
}
.login-brand span {
  color: var(--color-brand-strong);
}
.login-slogan {
  margin: 6px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.login-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  overflow: hidden;
}
.login-tabs button {
  border: 0;
  background: var(--color-surface);
  color: var(--color-text-muted);
  padding: 8px 0;
}
.login-tabs button.is-active {
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-weight: 500;
}
.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--text-sm);
  color: var(--color-text-body);
}
.field input {
  padding: 9px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-bg);
}
.field input:focus {
  outline: none;
  border-color: var(--color-brand);
}
.login-message {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-brand-strong);
}
.login-message.is-error {
  color: var(--color-danger);
}
.login-submit {
  width: 100%;
  margin-top: 4px;
}
</style>
