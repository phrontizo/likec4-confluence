import { describe, expect, it, vi } from 'vitest'
import { remountElement } from '../src/mountRegistry'

describe('remountElement', () => {
  it('mounts once on the first call without unmounting anything', () => {
    const el = {} as unknown as Element
    const mount = { unmount: vi.fn() }
    const create = vi.fn(() => mount)
    const got = remountElement(el, create)
    expect(got).toBe(mount)
    expect(create).toHaveBeenCalledTimes(1)
    expect(mount.unmount).not.toHaveBeenCalled()
  })

  it('unmounts the prior mount before re-creating on a repeated call for the same element', () => {
    // boot() can run more than once on a page (Confluence re-injecting the macro web-resource). Without
    // this, the second createRoot() runs on a container that already has a root: React 19 warns and the
    // previous root is never unmounted, so its effect cleanups (AbortController, listeners, the shared
    // pipeline reference) leak and two roots fight over one node.
    const el = {} as unknown as Element
    const first = { unmount: vi.fn() }
    const second = { unmount: vi.fn() }
    const order: string[] = []
    remountElement(el, () => { order.push('create-1'); return first })
    remountElement(el, () => {
      // the prior root must already be torn down by the time we build the new one
      expect(first.unmount).toHaveBeenCalledTimes(1)
      order.push('create-2')
      return second
    })
    expect(order).toEqual(['create-1', 'create-2'])
    expect(first.unmount).toHaveBeenCalledTimes(1)
    expect(second.unmount).not.toHaveBeenCalled()
  })

  it('tracks elements independently', () => {
    const a = {} as unknown as Element
    const b = {} as unknown as Element
    const ma = { unmount: vi.fn() }
    const mb = { unmount: vi.fn() }
    remountElement(a, () => ma)
    remountElement(b, () => mb)
    // Mounting b must not disturb a's mount.
    expect(ma.unmount).not.toHaveBeenCalled()
    expect(mb.unmount).not.toHaveBeenCalled()
  })
})
