import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'test/**/*.test.ts', 'vectors/**/*.test.ts', 'app/src/**/*.test.ts'],
    exclude: ['**/node_modules/**', 'test/e2e/**'],
    environment: 'node',
  },
})
