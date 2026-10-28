import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'SINGVERSE_')
  const target = env.SINGVERSE_DEV_BACKEND || 'http://127.0.0.1:8080'

  return {
    base: './',
    appType: 'mpa',
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target },
      },
    },
    build: {
      manifest: true,
      assetsDir: 'assets',
      rolldownOptions: {
        input: {
          party: fileURLToPath(new URL('./party/index.html', import.meta.url)),
          admin: fileURLToPath(new URL('./admin/index.html', import.meta.url)),
        },
      },
    },
  }
})
