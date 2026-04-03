import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
  },
  // Fix for React Router refresh 403 - Serve index.html for all routes
  build: {
    rollupOptions: {
      output: {
        manualChunks: undefined,
      },
    },
  },
})
