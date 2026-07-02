import React from 'react'
import { describe, expect, it } from 'vitest'
// Importing for its side-effect installs the useEffectEvent shim on React's CJS default export.
import '../src/reactCompat'

describe('reactCompat', () => {
  it('installs a useEffectEvent shim on the React default export', () => {
    // @xyflow/react (bundled in likec4@1.58.0) still calls React.useEffectEvent, which React 19 stable
    // dropped — without the shim the diagram does not render (only otherwise proven by the live e2e
    // gate). Guard against the side-effect silently regressing.
    expect(typeof (React as unknown as { useEffectEvent?: unknown }).useEffectEvent).toBe('function')
  })
})
