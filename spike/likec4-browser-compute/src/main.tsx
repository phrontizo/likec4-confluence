import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Diagram } from './diagram'
import type { WorkerResponse } from './worker'
import spec from '../fixtures/target/spec.likec4?raw'
import model from '../fixtures/target/model.likec4?raw'
import views from '../fixtures/target/views.likec4?raw'
import snap from '../fixtures/target/.likec4/index.likec4.snap?raw'

const sources: Record<string, string> = {
  'spec.likec4': spec,
  'model.likec4': model,
  'views.likec4': views,
  '.likec4/index.likec4.snap': snap,
}

function App() {
  const [resp, setResp] = useState<WorkerResponse | null>(null)
  useEffect(() => {
    const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<WorkerResponse>) => {
      ;(window as any).__spike = e.data
      setResp(e.data)
    }
    worker.postMessage({ sources })
    return () => worker.terminate()
  }, [])

  if (!resp) return <div data-testid="status">computing…</div>
  if (!resp.data) return <div data-testid="error">{resp.errors.map(e => e.message).join('; ')}</div>
  return <Diagram data={resp.data} viewId="index" />
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
