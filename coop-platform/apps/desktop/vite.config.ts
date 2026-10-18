import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Backend runs on 8090 because 8080 is occupied on the dev machine.
    proxy: { '/api': 'http://localhost:8090' },
  },
})
