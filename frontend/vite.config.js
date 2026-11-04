import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发态：/api 代理到后端 8080，前端不用管跨域
// 生产态：把 VITE_API_BASE 设成后端的实际地址（如 https://api.example.com/api）
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    open: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia', 'axios'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
