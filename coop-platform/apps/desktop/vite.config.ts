import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Backend runs on 8090 because 8080 is occupied on the dev machine.
    proxy: { '/api': 'http://localhost:8090' },
  },
  // Unit and component tests (npm test). The browser end-to-end tests are in e2e/ and run with npm run e2e.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // The first render of a Material UI screen can take several seconds when files run side by side on a busy machine.
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
})
