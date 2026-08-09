import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig(({ mode }) => {
  // 读取环境变量（.env / .env.production / .env.development）
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    root: process.cwd(),
    // base 路径可通过环境变量配置，默认 /chat/
    base: env.VITE_BASE_PATH || '/chat/',
    build: {
      outDir: path.resolve(process.cwd(), 'dist'),
      emptyOutDir: true,
      chunkSizeWarningLimit: 800,
      rollupOptions: {
        input: path.resolve(process.cwd(), 'index.html'),
        output: {
          manualChunks: {
            'vendor-react': ['react', 'react-dom', 'react-router-dom'],
            'vendor-ws': ['sockjs-client', '@stomp/stompjs'],
            'vendor-http': ['axios'],
          }
        }
      }
    },
    server: {
      host: '0.0.0.0',
      port: 3000,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true
        },
        '/ws': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
