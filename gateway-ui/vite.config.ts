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
  },
})