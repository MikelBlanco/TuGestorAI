import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },

  // En desarrollo, proxia las llamadas a /api al backend Tomcat
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },

  build: {
    // El WAR de Tomcat sirve el frontend desde /static
    outDir: '../src/main/webapp/static',
    emptyOutDir: true
  }
})