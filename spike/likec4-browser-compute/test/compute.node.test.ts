import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { compute } from '../src/compute'
import { loadSources } from '../src/sources'

const targetDir = fileURLToPath(new URL('../fixtures/target', import.meta.url))

describe('fromSources → layoutedModel', () => {
  it('computes a layouted model with the index view', async () => {
    const { data, errors } = await compute(loadSources(targetDir))
    expect(errors).toEqual([])
    const d = data as any
    expect(d._stage).toBe('layouted')
    expect(Object.keys(d.views)).toContain('index')
    const indexView = d.views.index
    expect(indexView.nodes.length).toBeGreaterThan(0)
    expect(typeof indexView.nodes[0].x).toBe('number')
    expect(typeof indexView.nodes[0].y).toBe('number')
  })

  it('surfaces parse/validation errors with line numbers', async () => {
    const brokenDir = fileURLToPath(new URL('../fixtures/broken', import.meta.url))
    const { errors } = await compute(loadSources(brokenDir))
    expect(errors.length).toBeGreaterThan(0)
    expect(errors[0]).toMatchObject({
      message: expect.any(String),
      line: expect.any(Number),
    })
    expect(errors[0].sourceFsPath).toContain('model.likec4')
  })

  it('applies a manual-layout snapshot supplied via sources', async () => {
    const sources = loadSources(targetDir)
    expect(Object.keys(sources)).toContain('.likec4/index.likec4.snap')

    const { data, errors } = await compute(sources)
    // The .snap must not leak into fromSources as a (broken) .c4 document.
    expect(errors).toEqual([])
    const d = data as any
    const indexView = d.views.index
    expect(indexView._layout).toBe('manual')
    const shifted = indexView.nodes.find((n: any) => n.x >= 500)
    expect(shifted, 'expected a node at the manually-shifted x position').toBeTruthy()
    expect(shifted.id).toBe('sys')
    // Prove this is a per-node snapshot override, not a global offset:
    // the other top-level node keeps its auto-layout x.
    const other = indexView.nodes.find((n: any) => n.id === 'ext')
    expect(other.x).toBeLessThan(500)
  })
})
