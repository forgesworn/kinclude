import { defineConfig, devices } from '@playwright/test'

const relayPort = 7788
const appPort = 5180

export default defineConfig({
  testDir: 'test/e2e',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${appPort}/`,
    ...devices['Pixel 7'],
    trace: 'retain-on-failure',
  },
  webServer: [
    { command: `node test/ws-relay.mjs ${relayPort}`, port: relayPort, reuseExistingServer: false },
    {
      command: `npx vite --config app/vite.config.ts --port ${appPort} --strictPort`,
      port: appPort,
      reuseExistingServer: false,
      env: { KINCLUDE_RELAYS: `ws://localhost:${relayPort}` },
    },
  ],
})
