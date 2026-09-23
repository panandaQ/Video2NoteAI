import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { plain: true } },
    { path: '/library', name: 'library', component: () => import('../views/LibraryView.vue') },
    { path: '/video/:mediaId', name: 'workbench', component: () => import('../views/WorkbenchView.vue') },
    { path: '/home', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/history', name: 'history', component: () => import('../views/HistoryView.vue') },
    { path: '/favorites', name: 'favorites', component: () => import('../views/FavoritesView.vue') },
    { path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.hasToken && to.name !== 'login') {
    return { name: 'login', query: to.fullPath === '/home' ? undefined : { redirect: to.fullPath } }
  }
  if (auth.hasToken && to.name === 'login') return { name: 'home' }
})

export default router
