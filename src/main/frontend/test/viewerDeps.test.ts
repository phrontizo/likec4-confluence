import { describe, expect, it } from 'vitest'
import { viewerDeps } from '../src/viewerDeps'

describe('viewerDeps', () => {
  it('memoises the deps so repeated calls reuse one WorkerPool', () => {
    const a = viewerDeps()
    const b = viewerDeps()
    // Same object identity => the same pool/cache/rest are reused. boot() calls viewerDeps(), so a
    // second boot() (Confluence re-injecting the macro web-resource, StrictMode double-invoke) does
    // NOT spawn — and leak — a fresh pool's worker threads.
    expect(b).toBe(a)
    expect(b.pool).toBe(a.pool)
    expect(b.cache).toBe(a.cache)
  })

  it('builds a fully-wired pipeline deps object', () => {
    const d = viewerDeps()
    expect(typeof d.rest.resolve).toBe('function')
    expect(typeof d.cache.get).toBe('function')
    expect(typeof d.pool.run).toBe('function')
    expect(d.timeoutMs).toBeGreaterThan(0)
    expect(typeof d.onCompute).toBe('function')
  })
})
