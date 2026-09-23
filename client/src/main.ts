import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 401 会话失效统一清理：清本地会话与业务条目，回到登录页（http.ts 只负责派发事件）
window.addEventListener('auth:expired', () => {
  const auth = useAuthStore()
  void auth.clearSession()
  void router.replace({ name: 'login' })
})

app.mount('#app')
