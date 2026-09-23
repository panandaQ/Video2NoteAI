<script setup lang="ts">
import { useRoute } from 'vue-router'

const items = [
  { to: '/home', label: '首页', icon: 'home' },
  { to: '/library', label: '视频库', icon: 'library' },
  { to: '/history', label: '历史会话', icon: 'history' },
  { to: '/favorites', label: '收藏', icon: 'favorites' },
  { to: '/settings', label: '设置', icon: 'settings' }
] as const

const route = useRoute()

function isActive(to: string): boolean {
  if (to === '/library' && route.name === 'workbench') return true
  return route.path.startsWith(to)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar__brand">
      <span class="sidebar__brand-mark" aria-hidden="true">
        <img src="/video2noteai-mark.svg" alt="" />
      </span>
      <span class="sidebar__brand-copy">
        <strong>Video2NoteAI</strong>
        <small>视频知识工作台</small>
      </span>
    </div>
    <nav class="sidebar__nav" aria-label="全局导航">
      <RouterLink
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="sidebar__item"
        :class="{ 'is-active': isActive(item.to) }"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <template v-if="item.icon === 'home'">
            <path d="M3 11l9-8 9 8" />
            <path d="M5 10v10h14V10" />
          </template>
          <template v-else-if="item.icon === 'library'">
            <rect x="4" y="4" width="16" height="16" rx="2" />
            <path d="M9.5 8.5l6 3.5-6 3.5z" />
          </template>
          <template v-else-if="item.icon === 'history'">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3.5 2" />
          </template>
          <template v-else-if="item.icon === 'favorites'">
            <path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z" />
          </template>
          <template v-else>
            <circle cx="12" cy="12" r="3.2" />
            <path d="M12 2.8v3M12 18.2v3M2.8 12h3M18.2 12h3M5.5 5.5l2.1 2.1M16.4 16.4l2.1 2.1M18.5 5.5l-2.1 2.1M7.6 16.4l-2.1 2.1" />
          </template>
        </svg>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 232px;
  flex-shrink: 0;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  padding: 22px 14px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}
.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 10px 4px;
}
.sidebar__brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
}
.sidebar__brand-mark img {
  display: block;
  width: 32px;
  height: 32px;
}
.sidebar__brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.sidebar__brand-copy strong {
  font-size: 15px;
  color: var(--color-text-strong);
  font-weight: 700;
  letter-spacing: -0.02em;
}
.sidebar__brand-copy small {
  color: var(--color-text-muted);
  font-size: 10px;
}
.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sidebar__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  color: var(--color-text-body);
  transition: background var(--motion-fast), color var(--motion-fast);
}
.sidebar__item:hover {
  background: var(--color-bg);
  color: var(--color-brand-strong);
}
.sidebar__item.is-active {
  background: var(--color-brand-soft);
  color: var(--color-brand-strong);
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--color-brand);
}
</style>
