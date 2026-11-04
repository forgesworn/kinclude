import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: './',
  envPrefix: 'KINCLUDE_',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2022',
  },
  server: { port: Number(process.env.KINCLUDE_APP_PORT ?? 5180), strictPort: true },
})
