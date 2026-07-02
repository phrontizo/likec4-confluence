import { describe, expect, it, vi } from 'vitest'
import { createDepsLifecycle } from '../src/editor/macroEditor'
import type { PipelineDeps } from '../src/pipeline'

function fakeDeps(): PipelineDeps {
  return {
    rest: {} as any,
    cache: {} as any,
    pool: { run: vi.fn(), dispose: vi.fn() } as any,
  }
}

describe('createDepsLifecycle (FE-I4)', () => {
  it('creates deps once and reuses the SAME pool across get()s', () => {
    const factory = vi.fn(fakeDeps)
    const lc = createDepsLifecycle(factory)
    const a = lc.get()
    const b = lc.get()
    expect(factory).toHaveBeenCalledTimes(1)
    expect(a).toBe(b)
  })

  it('disposes the prior pool on dispose() and re-creates on the next get() (no Worker leak)', () => {
    const factory = vi.fn(fakeDeps)
    const lc = createDepsLifecycle(factory)
    const first = lc.get()
    lc.dispose()
    expect(first.pool.dispose as any).toHaveBeenCalledTimes(1)
    const second = lc.get()
    expect(factory).toHaveBeenCalledTimes(2)
    expect(second).not.toBe(first)
  })
})
