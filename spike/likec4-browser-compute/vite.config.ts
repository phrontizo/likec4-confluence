import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  worker: { format: 'es' },
  resolve: {
    alias: {
      // vscode-languageserver ships browser.js without an exports map entry for
      // 'vscode-languageserver/browser', so Vite can't resolve it bare.
      'vscode-languageserver/browser': 'vscode-languageserver/browser.js',
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.node.test.ts'],
    server: {
      deps: {
        // Pull @likec4 and langium packages through Vite so the resolve.alias
        // for 'vscode-languageserver/browser' is applied to their deep imports.
        inline: [/@likec4\//, /langium/],
      },
    },
  },
})
