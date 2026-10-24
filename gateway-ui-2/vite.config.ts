import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// Admin API target for the dev/preview proxies. Override with VITE_ADMIN_API_TARGET
// (env var or .env file) to point a build at a remote control plane.
const apiTarget =
  loadEnv(process.env.NODE_ENV || 'development', process.cwd(), '').VITE_ADMIN_API_TARGET ||
  'http://localhost:9090'

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // Strip noisy console output in production but KEEP warn/error.
  // FIX: `drop: ['console']` removed every console.* call, including main.ts's
  // app.config.errorHandler, so a production error became completely silent. Same bug the legacy
  // gateway-ui terser config had with drop_console.
  esbuild: {
    pure: mode === 'production'
      ? ['console.log', 'console.debug', 'console.trace', 'console.info']
      : [],
    drop: mode === 'production' ? ['debugger'] : [],
  },
  server: {
    // 3001 so it can run alongside the legacy gateway-ui on 3000.
    port: 3001,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4174,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          antd: ['ant-design-vue', '@ant-design/icons-vue'],
          charts: ['echarts', 'vue-echarts'],
          i18n: ['vue-i18n'],
        },
      },
    },
  },
}))
