// @vitest-environment jsdom
//
// Unit coverage for the compute worker's message handler: a well-formed { sources } message runs
// compute and posts its result; a malformed message (no sources) is rejected up front with a
// computeError WITHOUT calling compute (rather than letting compute(undefined) throw an opaque
// "Object.entries(undefined)"). The heavy compute() is stubbed — its own logic is covered elsewhere.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const computeMock = vi.fn()
vi.mock('../src/compute', () => ({ compute: (...args: unknown[]) => computeMock(...args) }))

// Importing the worker registers self.onmessage against the current `self`.
import '../src/worker'
const handler = self.onmessage as unknown as (e: { data: unknown }) => Promise<void>

let posted: any[]
beforeEach(() => {
  posted = []
  ;(self as any).postMessage = (m: unknown) => posted.push(m)
  computeMock.mockReset()
})
afterEach(() => vi.restoreAllMocks())

describe('compute worker', () => {
  it('runs compute for a well-formed { sources } message and posts the result', async () => {
    computeMock.mockResolvedValue({ data: { model: 1 }, errors: [], drifts: [] })
    await handler({ data: { sources: { 'a.c4': 'specification {}' } } })
    expect(computeMock).toHaveBeenCalledWith({ 'a.c4': 'specification {}' })
    expect(posted).toHaveLength(1)
    expect(posted[0].data).toEqual({ model: 1 })
    expect(posted[0].computeError).toBeUndefined()
  })

  it('rejects a message with no sources up front without calling compute', async () => {
    await handler({ data: {} })
    expect(computeMock).not.toHaveBeenCalled()
    expect(posted).toHaveLength(1)
    expect(posted[0].data).toBeNull()
    expect(typeof posted[0].computeError).toBe('string')
    expect(posted[0].computeError.length).toBeGreaterThan(0)
  })

  it('rejects a null message payload without calling compute', async () => {
    await handler({ data: null })
    expect(computeMock).not.toHaveBeenCalled()
    expect(posted).toHaveLength(1)
    expect(posted[0].computeError).toBeTruthy()
  })

  it('rejects a non-record sources payload (null, string, number, or array) with the specific guard message', async () => {
    // The guard must reject anything that is not a plain Record. `null`/a stray string/number are the
    // obvious cases, but an ARRAY is also `typeof === 'object'` and truthy — it would otherwise slip
    // through to compute(), where Object.entries([...]) yields positional index keys (a malformed model),
    // mismatching the codebase's own !Array.isArray convention (asViewsMap, sanitizeSnapshotBody). Pin
    // that an array is caught up front the same way, never reaching compute(<non-record>).
    for (const bad of [null, 'a.c4=spec', 42, [], ['a.c4=spec']]) {
      posted = []
      await handler({ data: { sources: bad } })
      expect(computeMock).not.toHaveBeenCalled()
      expect(posted).toHaveLength(1)
      expect(posted[0].data).toBeNull()
      expect(posted[0].computeError).toBe('worker received no sources')
    }
  })

  it('reports a compute throw as a computeError rather than rejecting', async () => {
    computeMock.mockRejectedValue(new Error('boom'))
    await handler({ data: { sources: { 'a.c4': 'x' } } })
    expect(posted).toHaveLength(1)
    expect(posted[0].data).toBeNull()
    expect(posted[0].computeError).toContain('boom')
  })
})
