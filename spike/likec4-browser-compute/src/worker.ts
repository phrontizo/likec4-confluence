import { compute } from './compute'

export interface WorkerRequest { sources: Record<string, string> }
export interface WorkerResponse {
  data: unknown
  errors: Array<{ message: string; line: number; sourceFsPath: string }>
  computeMs: number
}

self.onmessage = async (e: MessageEvent<WorkerRequest>) => {
  const start = performance.now()
  try {
    const { data, errors } = await compute(e.data.sources)
    const res: WorkerResponse = { data, errors, computeMs: performance.now() - start }
    ;(self as unknown as Worker).postMessage(res)
  } catch (err) {
    ;(self as unknown as Worker).postMessage({
      data: null,
      errors: [{ message: String(err), line: 0, sourceFsPath: '' }],
      computeMs: performance.now() - start,
    } satisfies WorkerResponse)
  }
}
