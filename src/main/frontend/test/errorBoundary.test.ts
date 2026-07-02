import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ErrorBoundary } from '../src/ErrorBoundary'

describe('ErrorBoundary (FE-I5)', () => {
  it('passes children through when there is no error', () => {
    const html = renderToStaticMarkup(
      React.createElement(ErrorBoundary, null, React.createElement('span', null, 'child-ok')),
    )
    expect(html).toContain('child-ok')
  })

  it('derives error state from a thrown render error', () => {
    const err = new Error('boom')
    expect(ErrorBoundary.getDerivedStateFromError(err)).toEqual({ error: err })
  })

  it('renders the ErrorPanel fallback (not a blank) once an error is caught', () => {
    const eb = new ErrorBoundary({ children: React.createElement('span', null, 'should-not-show') })
    eb.state = ErrorBoundary.getDerivedStateFromError(new Error('render exploded'))
    const html = renderToStaticMarkup(eb.render() as React.ReactElement)
    expect(html).toContain('likec4-error') // ErrorPanel marker
    expect(html).toContain('render exploded') // the failure message surfaces
    expect(html).not.toContain('should-not-show') // children are NOT rendered alongside
  })

  it('does not itself throw when the caught value is a null-prototype object', () => {
    // React hands getDerivedStateFromError whatever was thrown — not necessarily a real Error. A
    // null-prototype thrown value makes String(v) throw ("Cannot convert object to primitive value"),
    // which would blow up the boundary's OWN render and blank the container. messageOf must absorb it.
    const weird = Object.assign(Object.create(null), { detail: 'no-proto' })
    const eb = new ErrorBoundary({ children: null })
    eb.state = ErrorBoundary.getDerivedStateFromError(weird as unknown as Error)
    expect(() => renderToStaticMarkup(eb.render() as React.ReactElement)).not.toThrow()
    const html = renderToStaticMarkup(eb.render() as React.ReactElement)
    expect(html).toContain('likec4-error')
    expect(html).toContain('no-proto') // messageOf falls back to JSON.stringify for a null-proto object
  })

  it('surfaces a custom title in the fallback (used by the editor picker wrap, FE-M2)', () => {
    // mountViewPicker wraps <Editor> in <ErrorBoundary title="View picker failed"> so a throw in the
    // editor's OWN render path (e.g. listViews on a future likec4 shape change) shows an inline panel
    // instead of blanking the picker. Pin that the custom heading flows through to the fallback.
    const eb = new ErrorBoundary({ children: null, title: 'View picker failed' })
    eb.state = ErrorBoundary.getDerivedStateFromError(new Error('listViews exploded'))
    const html = renderToStaticMarkup(eb.render() as React.ReactElement)
    expect(html).toContain('View picker failed') // the supplied heading, not the generic default
    expect(html).toContain('listViews exploded')
    expect(html).not.toContain('Could not render diagram') // default heading is overridden
  })
})
