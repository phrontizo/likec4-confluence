import { isValidElement, type ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import { ErrorBoundary } from '../src/ErrorBoundary'
import { OkView } from '../src/Viewer'
import { ViewPicker } from '../src/editor/ViewPicker'

// Walk a (non-rendered) React element tree for the first element of a given type. The keying fixes are
// pure JSX wiring — calling these components as functions builds the element tree without rendering
// (no hooks run; Diagram is lazy), so we can assert WHICH element carries the reset key.
function findByType(node: unknown, type: unknown): ReactElement | null {
  if (!isValidElement(node)) return null
  const el = node as ReactElement
  if (el.type === type) return el
  const kids = (el.props as { children?: unknown }).children
  for (const k of Array.isArray(kids) ? kids : [kids]) {
    const found = findByType(k, type)
    if (found) return found
  }
  return null
}

const okResult = (over: Record<string, unknown> = {}) => ({
  kind: 'ok' as const, sha: 'shaA', data: { views: {} }, viewIds: ['index'],
  startView: 'index', drifts: [], fromCache: false, ...over,
})

describe('diagram reset keying (FE keying regressions)', () => {
  it('Viewer keys the ErrorBoundary on sha:startView so a data OR view change remounts it', () => {
    // Keying the BOUNDARY (not just Diagram) resets the latched render error AND Diagram's navigated
    // viewId on any model swap. sha captures data identity; startView captures a `view`-param change for
    // the same source.
    const a = OkView({ result: okResult() }) as unknown as ReactElement
    expect(a.type).toBe(ErrorBoundary)
    expect(a.key).toBe('shaA:index')
    expect((OkView({ result: okResult({ sha: 'shaB' }) }) as unknown as ReactElement).key)
      .toBe('shaB:index') // new model, same startView -> key must change
    expect((OkView({ result: okResult({ startView: 'sys_detail' }) }) as unknown as ReactElement).key)
      .toBe('shaA:sys_detail') // same model, new startView -> key must change
  })

  it('ViewPicker keys the ErrorBoundary on the selected view so switching views remounts it', () => {
    const tree = ViewPicker({
      views: [{ id: 'index', title: 'Index' }, { id: 'sys_detail', title: 'Sys' }],
      selected: 'sys_detail', onChange: () => {}, data: { views: {} }, drifts: [],
    } as never)
    const boundary = findByType(tree, ErrorBoundary)
    expect(boundary).not.toBeNull()
    expect(boundary!.key).toBe('sys_detail')
  })
})
