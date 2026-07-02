import { afterEach, describe, expect, it, vi } from 'vitest'
import { bumpComputeCounter, defaultPoolSize } from '../src/runtime'

describe('defaultPoolSize', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('caps at the provided cap even when the device has more cores', () => {
    vi.stubGlobal('navigator', { hardwareConcurrency: 16 })
    expect(defaultPoolSize(4)).toBe(4)
    expect(defaultPoolSize(2)).toBe(2)
  })

  it('scales down to the core count when it is below the cap', () => {
    vi.stubGlobal('navigator', { hardwareConcurrency: 2 })
    expect(defaultPoolSize(4)).toBe(2)
  })

  it('never returns less than 1', () => {
    vi.stubGlobal('navigator', { hardwareConcurrency: 1 })
    expect(defaultPoolSize(4)).toBe(1)
  })

  it('falls back to the cap when hardwareConcurrency is zero or navigator is unavailable', () => {
    vi.stubGlobal('navigator', { hardwareConcurrency: 0 })
    expect(defaultPoolSize(4)).toBe(4)
    vi.stubGlobal('navigator', undefined)
    expect(defaultPoolSize(3)).toBe(3)
  })

  it('floors a fractional hardwareConcurrency to a whole worker count', () => {
    // Some environments report a fractional core count; the pool compares `created` with `>=`, so a
    // fractional size would only ever be reached at the next whole number (an implicit ceil). Floor it
    // to a predictable integer instead.
    vi.stubGlobal('navigator', { hardwareConcurrency: 1.5 })
    expect(defaultPoolSize(4)).toBe(1)
    vi.stubGlobal('navigator', { hardwareConcurrency: 2.9 })
    expect(defaultPoolSize(4)).toBe(2)
  })
})

describe('bumpComputeCounter', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('increments the window-scoped e2e compute counter, seeding from undefined', () => {
    vi.stubGlobal('window', {})
    bumpComputeCounter()
    expect((globalThis as { window: { __likec4Computes?: number } }).window.__likec4Computes).toBe(1)
    bumpComputeCounter()
    expect((globalThis as { window: { __likec4Computes?: number } }).window.__likec4Computes).toBe(2)
  })
})
