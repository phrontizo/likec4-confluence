import type { PoolWorker } from './workerPool'

/** Vite bundles `./worker.ts` as a separate ES-module worker chunk (lazy: created on first job). */
export function createDiagramWorker(): PoolWorker {
  return new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' }) as unknown as PoolWorker
}
