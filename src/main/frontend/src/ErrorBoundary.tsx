import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorPanel } from './ErrorPanel'
import { messageOf } from './errors'

interface Props {
  children: ReactNode
  /** Heading for the fallback panel (default: a generic render-failure message). */
  title?: string
}
// `unknown`, not `Error`: React hands these handlers whatever was thrown, which is NOT necessarily an
// Error (likec4-internal code can throw a string or a null-prototype object — see messageOf/errors.ts).
// Typing it `unknown` forces every consumer through messageOf (the one safe normaliser) rather than
// letting a future `error.message`/`error.stack` access compile against a false Error guarantee and
// throw at runtime on a non-Error throw.
interface State { error: unknown }

/**
 * Classic error boundary around the diagram render. `LikeC4Model.create(data)` / `<LikeC4Diagram>`
 * run on UNTYPED data deserialized from the worker/cache; a render-time throw there is NOT caught by
 * `<Suspense>` and would blank the entire `createRoot` (the macro's whole container goes empty).
 * Catching it here degrades a single broken diagram to an inline ErrorPanel instead.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: unknown): State {
    return { error }
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.error('likec4: diagram render crashed', error, info.componentStack)
  }

  render(): ReactNode {
    const { error } = this.state
    if (error) {
      return (
        <ErrorPanel
          failure={{
            title: this.props.title ?? 'Could not render diagram',
            tone: 'error',
            // messageOf, not `error.message || String(error)`: String() throws on a null-prototype
            // thrown value, which would blow up the boundary's own render. messageOf is the one safe
            // normaliser (same discipline as worker.ts / the pipeline).
            lines: [messageOf(error)],
          }}
        />
      )
    }
    return this.props.children
  }
}
