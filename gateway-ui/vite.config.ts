import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Admin API target for the dev/preview proxies. Override with VITE_ADMIN_API_TARGET (env var
// or .env file) to point a build at a remote control plane; the default keeps today's behaviour.
const apiTarget =
  loadEnv(process.env.NODE_ENV || 'development', process.cwd(), '').VITE_ADMIN_API_TARGET ||
  'http://localhost:9090'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  // Fix for React Router refresh 403 - Serve index.html for all routes
  build: {
    rollupOptions: {
      output: {
        // Code splitting configuration for better performance
        manualChunks: {
          // React core - smallest possible bundle
          'react-vendor': ['react', 'react-dom'],
          // React Router
          'router': ['react-router-dom'],
          // Ant Design UI library
          'antd': ['antd', '@ant-design/icons'],
          // Charts libraries
          'charts': ['echarts', '@nivo/core', '@nivo/line', '@nivo/pie'],
          // Internationalization
          'i18n': ['i18next', 'react-i18next', 'i18next-browser-languagedetector'],
          // Other utilities
          'utils': ['axios', 'copy-to-clipboard', 'react-markdown'],
        },
      },
    },
    // Increase chunk size warning limit since we're intentionally splitting
    chunkSizeWarningLimit: 600,
    // Production optimization: strip noisy console output but KEEP warn/error.
    // FIX: this used `drop_console: true`, which removes EVERY console.* call - including
    // logger.error (src/utils/logger.ts, whose own contract says "warn/error always") and
    // ErrorBoundary.componentDidCatch. The result was a production bundle where DevTools showed
    // no error at all, which is the worst possible outcome for a single-operator deployment.
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_debugger: true, // Remove debugger statements
        pure_funcs: ['console.log', 'console.debug', 'console.trace', 'console.info'],
      }
    },
  },
})