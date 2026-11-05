import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts', 'test/**/*.test.ts', 'vectors/**/*.test.ts', 'app/src/**/*.test.ts'],
    exclude: ['**/node_modules/**', 'test/e2e/**'],
    environment: 'node',
    // Scenario tests run several phones over a relay; a slow machine needs
    // more than the default five seconds.
    testTimeout: 30_000,
  },
})
