import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

/** Recursively read every file under `dir` into a record keyed by POSIX relative path. */
export function loadSources(dir: string): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (current: string) => {
    for (const name of readdirSync(current)) {
      const full = join(current, name)
      if (statSync(full).isDirectory()) walk(full)
      else out[relative(dir, full).split(sep).join('/')] = readFileSync(full, 'utf8')
    }
  }
  walk(dir)
  return out
}
