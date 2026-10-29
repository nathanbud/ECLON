import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The version is the one in package.json (Semantic Versioning: MAJOR.MINOR.PATCH). The build is the git commit it was made from and the day it was made; without git it says so.
const git = (cmd: string, fallback: string) => { try { return execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() || fallback } catch { return fallback } }
const version = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version as string
const dirty = git('git status --porcelain', '') ? '+' : ''

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(version),
    __APP_BUILD__: JSON.stringify(git('git rev-parse --short HEAD', 'unknown') + dirty),
    __APP_BUILD_DATE__: JSON.stringify(new Date().toISOString().slice(0, 10)),
  },
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
