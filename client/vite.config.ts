import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://localhost:9090'
  const proxyPaths = [
    '/user',
    '/media',
    '/analysis',
    '/admin',
    '/health',
    '/knowledge',
    '/videos',
    '/video-imports'
  ]
  return {
    envDir: '..',
    plugins: [vue()],
    server: {
      proxy: Object.fromEntries(proxyPaths.map((path) => [path, { target: backend, changeOrigin: true }]))
    }
  }
})
