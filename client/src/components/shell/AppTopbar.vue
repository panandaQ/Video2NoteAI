<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import Button from '../ui/Button.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const navItems = [
  { to: '/home', label: '首页', icon: 'home' },
  { to: '/library', label: '视频库', icon: 'library' },
  { to: '/history', label: '问答历史', icon: 'history' },
  { to: '/settings', label: '设置', icon: 'settings' }
] as const

function isActive(to: string): boolean {
  if (to === '/library' && route.name === 'workbench') return true
  return route.path.startsWith(to)
}

async function logout() {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar__inner">
      <RouterLink to="/home" class="topbar__brand" aria-label="Video2NoteAI 首页">
        <span class="topbar__brand-mark" aria-hidden="true">
          <img src="/video2noteai-mark.svg" alt="" />
        </span>
        <strong>Video2NoteAI</strong>
      </RouterLink>

      <nav class="topbar__nav" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="topbar__nav-item"
          :class="{ 'is-active': isActive(item.to) }"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <template v-if="item.icon === 'home'">
              <path d="M3 11.2 12 3l9 8.2" />
              <path d="M5.5 10.5V21h13V10.5" />
            </template>
            <template v-else-if="item.icon === 'library'">
              <rect x="3.5" y="4" width="17" height="16" rx="2.5" />
              <path d="m10 8.5 5.5 3.5-5.5 3.5v-7Z" />
            </template>
            <template v-else-if="item.icon === 'settings'">
              <circle cx="12" cy="12" r="3" />
              <path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.2 5.2l2.1 2.1M16.7 16.7l2.1 2.1M18.8 5.2l-2.1 2.1M7.3 16.7l-2.1 2.1" />
            </template>
            <template v-else>
              <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H10l-5.5 4v-4.7A2.5 2.5 0 0 1 4 13.8V5.5Z" />
            </template>
          </svg>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="topbar__actions">
        <Button class="topbar__import" @click="router.push({ path: '/library', query: { import: '1' } })">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
            <path d="M12 15V3m0 0L7.5 7.5M12 3l4.5 4.5M5 14v5h14v-5" />
          </svg>
          导入视频
        </Button>
        <span class="topbar__avatar" aria-hidden="true">{{ (auth.user?.nickname || 'U').slice(0, 1) }}</span>
        <span class="topbar__name">{{ auth.user?.nickname ?? '' }}</span>
        <Button variant="ghost" aria-label="退出登录" @click="logout">退出</Button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  z-index: 20;
  top: 0;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.topbar__inner {
  width: min(1480px, 100%);
  min-height: 72px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 0 32px;
}
.topbar__brand {
  display: inline-flex;
  min-width: 178px;
  align-items: center;
  gap: 10px;
  color: var(--color-text-strong);
}
.topbar__brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
}
.topbar__brand-mark img {
  display: block;
  width: 30px;
  height: 30px;
}
.topbar__brand strong {
  font-size: 20px;
  letter-spacing: -0.03em;
}
.topbar__nav {
  align-self: stretch;
  display: flex;
  align-items: stretch;
  justify-content: center;
  gap: 54px;
}
.topbar__nav-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px;
  color: var(--color-text-body);
  font-size: 15px;
  font-weight: 600;
}
.topbar__nav-item::after {
  position: absolute;
  right: 4px;
  bottom: 0;
  left: 4px;
  height: 3px;
  border-radius: 3px 3px 0 0;
  background: #43ad50;
  content: '';
  opacity: 0;
  transform: scaleX(.45);
  transition: opacity var(--motion-fast), transform var(--motion-fast);
}
.topbar__nav-item:hover,
.topbar__nav-item.is-active { color: #32953d; }
.topbar__nav-item.is-active::after {
  opacity: 1;
  transform: scaleX(1);
}
.topbar__actions {
  min-width: 300px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
.topbar__import {
  margin-right: 8px;
  background: #43ad50;
}
.topbar__import:hover:not(:disabled) { background: #369641; }
.topbar__import svg { stroke-linecap: round; stroke-linejoin: round; }
.topbar__avatar {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-size: var(--text-sm);
  font-weight: 700;
}
.topbar__name {
  color: var(--color-text-body);
  font-size: var(--text-sm);
}
@media (max-width: 980px) {
  .topbar__inner { gap: 14px; padding: 0 18px; }
  .topbar__brand { min-width: auto; }
  .topbar__brand strong { display: none; }
  .topbar__nav { gap: 18px; }
  .topbar__actions { min-width: auto; }
  .topbar__name,
  .topbar__actions > :last-child { display: none; }
}
@media (max-width: 640px) {
  .topbar__inner { min-height: 64px; }
  .topbar__nav { gap: 8px; }
  .topbar__nav-item { padding: 0 5px; font-size: 13px; }
  .topbar__nav-item svg { display: none; }
  .topbar__import { display: none; }
}
</style>
