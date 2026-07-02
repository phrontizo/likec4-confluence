import { existsSync, readFileSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { type Plugin, defineConfig } from 'vite'
import { mockRest } from './vite-plugin-mock-rest'

const reposDir = fileURLToPath(new URL('./test/fixtures/repos', import.meta.url))

// index.html/editor.html are Vite dev-harness inputs needed only to emit the boot bundle + manifest;
// the emitted HTML pages themselves must NOT ship in the Confluence download resource — they would be
// fetchable at /download/resources/<key>/likec4-web/index.html and auto-boot a viewer against the
// hardcoded fixture project. The boot loader reads the bundle via .vite/manifest.json + assets/main.js,
// never these HTML files, so deleting them after the build is safe (the manifest JSON is untouched).
function stripDevHarnessHtml(outDir: string): Plugin {
  return {
    name: 'likec4-strip-dev-harness-html',
    apply: 'build',
    closeBundle() {
      for (const f of ['index.html', 'editor.html']) {
        const p = join(outDir, f)
        if (existsSync(p)) rmSync(p)
      }
    },
  }
}

const outDir = fileURLToPath(new URL('../resources/likec4-web', import.meta.url))

// Build-time guard for a silent, e2e-only failure mode. The classic loaders (public/boot-loader.js and
// public/editor-loader.js) fetch the entry bundles by FIXED, unhashed paths (Confluence versions the
// whole web-resource batch, so entries carry no content hash). If `entryFileNames` or a rollup input key
// ever drifts, the loaders would 404 the entry and the diagram/editor would silently never boot — a
// failure the unit tests can't see. Assert the emitted manifest still maps each entry to the exact path
// its loader hardcodes, failing the build loudly instead. Keep these paths in lockstep with the loaders.
function assertEntryFilenames(outDir: string): Plugin {
  const EXPECTED: Record<string, string> = {
    'index.html': 'assets/main.js', // boot-loader.js
    'src/editor-confluence.tsx': 'assets/editor-confluence.js', // editor-loader.js
  }
  return {
    name: 'likec4-assert-entry-filenames',
    apply: 'build',
    closeBundle() {
      const manifest = JSON.parse(readFileSync(join(outDir, '.vite', 'manifest.json'), 'utf8'))
      for (const [key, file] of Object.entries(EXPECTED)) {
        const actual = manifest[key]?.file
        if (actual !== file) {
          throw new Error(
            `[likec4] entry '${key}' emitted as '${actual}', but the classic loader hardcodes '${file}'. `
              + 'Update the loader (public/*.js) and this assertion together, or restore entryFileNames.',
          )
        }
      }
    },
  }
}

// likec4/react renders panda-CSS atomic classes (e.g. `.likec4-root`) whose rules + design tokens
// live ONLY in likec4's prebuilt stylesheet (its standalone-app bundle). `likec4/react` ships no CSS
// export, so without this the `.likec4-root` container has no `height` rule, collapses to ~110px,
// react-flow gets ~20px and fitView zooms the diagram to ~scale(0.05) — mounted but invisible.
// The stylesheet is fully `@layer`-scoped, so the host Confluence page's (unlayered) CSS still wins.
// Resolve it via the EXPORTED package.json so the path survives dependency hoisting.
const likec4Dir = dirname(createRequire(import.meta.url).resolve('likec4/package.json'))
const likec4AppStyle = join(likec4Dir, '__app__/src/style.css')

export default defineConfig({
  // Use relative asset paths so the worker URL and dynamic-import chunk paths
  // resolve correctly when served from a deep Confluence resource URL (not '/').
  base: './',
  // mockRest is `apply: 'serve'` (dev/preview only); stripDevHarnessHtml is `apply: 'build'`.
  plugins: [react(), mockRest(reposDir), assertEntryFilenames(outDir), stripDevHarnessHtml(outDir)],
  worker: { format: 'es' },
  resolve: {
    alias: {
      'vscode-languageserver/browser': 'vscode-languageserver/browser.js',
      // `.css` suffix so `*.css` (vite/client) types it; aliased to bypass likec4's `exports` map.
      'likec4-app-style.css': likec4AppStyle,
    },
  },
  build: {
    outDir,
    emptyOutDir: true,
    manifest: true,
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        editor: fileURLToPath(new URL('./editor.html', import.meta.url)),
        // Confluence macro-editor bundle entry (publishes window.LikeC4Editor). Built as a JS
        // entry (no HTML) so the classic editor-loader.js can inject it as <script type="module">.
        'editor-confluence': fileURLToPath(new URL('./src/editor-confluence.tsx', import.meta.url)),
      },
      output: {
        // Stable, unhashed entry filenames so the Confluence web-resource can reference the
        // boot entry by a fixed path (Confluence versions the whole web-resource batch, so the
        // entry needs no content hash). Lazy chunks + assets stay content-hashed for caching.
        entryFileNames: 'assets/[name].js',
      },
    },
  },
  test: {
    environment: 'node',
    // Match .test.tsx as well as .test.ts: today every component test uses createElement inside a
    // .test.ts precisely to sidestep this glob, but a contributor who naturally writes a JSX
    // `Foo.test.tsx` would otherwise have it silently collected-zero and never run behind a green
    // suite -- the exact "unit-green lies" trap CLAUDE.md warns about.
    include: ['test/**/*.test.{ts,tsx}'],
    server: { deps: { inline: [/@likec4\//, /langium/] } },
  },
})
