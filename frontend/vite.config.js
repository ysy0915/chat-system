import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  root: process.cwd(),
  base: '/chat/',
  build: {
    outDir: path.resolve(process.cwd(), '../src/main/resources/static/chat'),
    emptyOutDir: true,
    rollupOptions: {
      input: path.resolve(process.cwd(), 'index.html')
    }
  },
  server: {
    port: 3000
  }
})
