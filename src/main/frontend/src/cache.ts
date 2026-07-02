import type { ComputeError, DriftInfo } from './compute'

export interface CachedDump {
  key: string
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
  accessedAt: number
}

export type DumpValue = Pick<CachedDump, 'data' | 'errors' | 'drifts'>

/** A persisted row is only usable if it carries the dump shape the pipeline reads (errors/drifts
 *  arrays + a data field). Anything else (a partial write, or a future incompatible shape) is a miss. */
function isValidDump(row: unknown): row is Omit<CachedDump, 'accessedAt'> {
  const r = row as Record<string, unknown> | null
  return !!r && 'data' in r && Array.isArray(r.errors) && Array.isArray(r.drifts)
}

export interface DumpCache {
  get(key: string): Promise<CachedDump | null>
  put(key: string, value: DumpValue): Promise<void>
}

/**
 * Build the per-dump cache key. Scoped by the resolved commit `sha` (content-addressed) and the
 * sub-`path`. The likec4/app version is NOT part of the key — it scopes the whole DATABASE (DB_NAME)
 * instead, so an incompatible-shape dump from an old build is in a different DB and never read.
 *
 * The sha is length-prefixed (`<len>:<sha>:<path>`) so the (sha, path) tuple is encoded injectively: a
 * plain `${sha}:${path}` would let ("a", "b:c") and ("a:b", "c") collide to the same "a:b:c" key, and a
 * `path` may legitimately contain a colon. The length prefix pins the sha boundary regardless.
 */
export function cacheKey(sha: string, path: string | undefined): string {
  return `${sha.length}:${sha}:${path ?? ''}`
}

// Token for the likec4/app build that produced the serialized model shape. The cached `data` is a
// `LayoutedLikeC4ModelData` whose structure is owned by the pinned likec4 version — a likec4 upgrade
// can change that shape, so a dump serialized by an OLD build must never be fed to a NEW
// `LikeC4Model.create`. Baking the token into the DB NAME isolates each build's cache: a new build
// opens a fresh database and simply never reads the incompatible rows of the old one.
export const CACHE_VERSION = '1.58.0'
export const DB_NAME = `likec4-diagram-${CACHE_VERSION}`
// The heavy dump payloads ({key,data,errors,drifts}) live in STORE. The LRU stamp lives in the SEPARATE,
// lightweight STAMP_STORE ({key,accessedAt}) so a cache READ can bump the stamp WITHOUT re-writing the
// whole (potentially multi-MB) dump — a plain object-store `put` always rewrites the entire record, so
// keeping accessedAt inside STORE forced every hit to re-serialize + re-persist the full model just to
// touch a number. The two are written/deleted together in one transaction (IndexedDB commits a
// multi-store tx atomically), so they never desync. The accessedAt index (for seeding + eviction) lives
// on STAMP_STORE, whose records are tiny.
export const STORE = 'dumps'
export const STAMP_STORE = 'stamps'
// Bump on any change to the persisted ROW shape (either store). `onupgradeneeded` drops+recreates both
// stores on a version change, purging now-incompatible rows (the previous code only created the store
// when missing, so a bump left stale rows in place). Bumped to 3 for the dumps/stamps split.
export const DB_VERSION = 3

// Monotonic LRU stamp counter, shared MODULE-WIDE (not per instance). The viewer and the macro editor
// build separate IndexedDbDumpCache instances against the SAME database on one page; a per-instance
// counter would let their stamps collide (both seed 0 and reach the same value), so an interleaved write
// from one instance could look as old as an earlier write from the other and be evicted out of order. A
// single shared counter keeps stamps globally monotonic across every instance in the JS context. It is
// still seeded on open from the highest persisted accessedAt, so it also keeps increasing across reloads
// (a fresh context resets it to 0, and the seed lifts it above anything already on disk).
let sharedSeq = 0

export class IndexedDbDumpCache implements DumpCache {
  private dbp: Promise<IDBDatabase> | null = null

  // Keep maxEntries UNIFORM across every instance that shares DB_NAME (the viewer and the editor both
  // default to 50). Eviction trims to *this* instance's bound on its own puts, so a second instance opened
  // with a smaller bound against the same DB would trim the shared store down on every write it makes.
  constructor(private readonly maxEntries = 50) {}

  private stamp(): number {
    return ++sharedSeq
  }

  private db(): Promise<IDBDatabase> {
    if (this.dbp) return this.dbp
    const p = new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onupgradeneeded = () => {
        const db = req.result
        // Drop any pre-existing stores so a DB_VERSION bump purges rows of the now-incompatible shape,
        // then (re)create them empty. (Creating only when missing left stale rows behind.)
        if (db.objectStoreNames.contains(STORE)) db.deleteObjectStore(STORE)
        if (db.objectStoreNames.contains(STAMP_STORE)) db.deleteObjectStore(STAMP_STORE)
        db.createObjectStore(STORE, { keyPath: 'key' })
        const stamps = db.createObjectStore(STAMP_STORE, { keyPath: 'key' })
        stamps.createIndex('accessedAt', 'accessedAt')
      }
      req.onerror = () => reject(req.error)
      // Another connection (an older tab holding this DB at a lower version) is blocking our upgrade. It
      // will not clear until that tab yields, so don't await the open forever: reject and let get/put
      // degrade to recompute. (Our own connections yield via db.onversionchange below, so this only bites
      // an OLD build's tab that predates that handler.)
      req.onblocked = () =>
        reject(new DOMException('IndexedDB open blocked by another connection', 'AbortError'))
      req.onsuccess = () => {
        // The seed below runs db.transaction()/objectStore()/index() SYNCHRONOUSLY inside this success
        // handler. If any of it throws (a corrupt DB, or a store/index missing at open time — e.g. a
        // shape the current onupgradeneeded didn't produce), the exception would escape the handler and
        // leave this promise pending FOREVER: every later get/put would then await an open that never
        // settles (the retry-reset below only fires on REJECTION, not on a hang). Wrap it so a
        // synchronous seed failure rejects instead, degrading to recompute like every other cache error.
        try {
          const db = req.result
          // Yield to a newer build's upgrade: if another tab opens this DB at a higher DB_VERSION, close
          // our connection so its onupgradeneeded is not blocked (and our now-stale build stops holding an
          // outdated schema open). Without this, a DB_VERSION bump across two open tabs would deadlock the
          // newer tab's open. Also DROP the cached promise: once closed, a resolved promise still pointing
          // at this connection would make every later get/put throw InvalidStateError from db.transaction()
          // forever — a permanently dead cache (the p.catch reset below only fires when the OPEN rejects,
          // never when a resolved connection is closed after the fact). Nulling it lets the next call
          // reopen and either recover (if the DB is now openable) or degrade to recompute via a fresh open.
          db.onversionchange = () => {
            db.close()
            if (this.dbp === p) this.dbp = null
          }
          // Seed the counter from the max persisted accessedAt (in the stamp store) before any get/put.
          const tx = db.transaction(STAMP_STORE, 'readonly')
          const cursorReq = tx.objectStore(STAMP_STORE).index('accessedAt').openCursor(null, 'prev')
          cursorReq.onsuccess = () => {
            const cur = cursorReq.result
            // cur.key is the indexed accessedAt (always a number we wrote). Guard with Number.isFinite so
            // a foreign/older row with a non-numeric accessedAt can't reseed seq to NaN/0 and risk
            // premature eviction of fresh entries; a non-finite seed is simply ignored.
            if (cur) {
              const seed = Number(cur.key)
              if (Number.isFinite(seed)) sharedSeq = Math.max(sharedSeq, seed)
            }
          }
          tx.oncomplete = () => resolve(db)
          // Close the opened connection on a seed-tx failure too: we're rejecting the open (degrade to
          // recompute; the next call reopens a fresh connection via the p.catch reset), so this one would
          // otherwise leak and could onblocked a later DB_VERSION upgrade.
          tx.onerror = () => { db.close(); reject(tx.error) }
        } catch (e) {
          // Same for a synchronous seed throw: the connection is already open here, so close it before
          // rejecting rather than leaking it.
          try { req.result.close() } catch { /* connection already closing/closed */ }
          reject(e)
        }
      }
    })
    // Do NOT cache a rejected open forever: a transient failure (a one-off quota error during the seed
    // cursor, a version-change block) would otherwise leave every subsequent get/put returning the same
    // rejected promise — silently dead cache for the page lifetime. Null it out so the next call retries.
    p.catch(() => { if (this.dbp === p) this.dbp = null })
    this.dbp = p
    return p
  }

  async get(key: string): Promise<CachedDump | null> {
    const db = await this.db()
    return new Promise<CachedDump | null>((resolve) => {
      // The LRU touch is a `stamps.put` on every read — a tiny {key,accessedAt} record, NOT a rewrite of
      // the dump — so this is a readwrite tx over both stores. The touch is BEST-EFFORT: a failed stamp
      // write (quota, a flaky store) must NOT discard a perfectly good cached dump we already read — that
      // would force a needless recompute exactly when the cache would help most. So capture the read
      // result up front and resolve with it even if the write side aborts the transaction.
      let result: CachedDump | null = null
      let settled = false
      const done = () => { if (!settled) { settled = true; resolve(result) } }
      const tx = db.transaction([STORE, STAMP_STORE], 'readwrite')
      const dumps = tx.objectStore(STORE)
      const stamps = tx.objectStore(STAMP_STORE)
      const getReq = dumps.get(key)
      getReq.onsuccess = () => {
        const row = getReq.result as Record<string, unknown> | undefined
        if (row && isValidDump(row)) {
          const accessedAt = this.stamp()
          result = { ...row, accessedAt }
          // Write ONLY the lightweight stamp — never re-persist the dump `data`. A valid dump missing its
          // stamp (e.g. after a partial legacy state) also self-heals here.
          try { stamps.put({ key, accessedAt }) } catch { /* best-effort LRU touch; result already captured */ }
        } else if (row) {
          // A partially-written or future-incompatible row would crash the pipeline (it reads
          // `errors.length`). Treat it as a miss AND delete it (from both stores) so one poisoned row
          // can't permanently break this diagram until the DB version bumps.
          try { dumps.delete(key) } catch { /* best-effort cleanup */ }
          try { stamps.delete(key) } catch { /* best-effort cleanup */ }
        }
      }
      getReq.onerror = () => { result = null } // genuine read failure → miss → pipeline recomputes
      tx.oncomplete = done
      // A failed/aborted tx (e.g. the LRU put hit quota) must still return the row we already read.
      tx.onerror = done
      tx.onabort = done
    })
  }

  async put(key: string, value: DumpValue): Promise<void> {
    const db = await this.db()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction([STORE, STAMP_STORE], 'readwrite')
      const dumps = tx.objectStore(STORE)
      const stamps = tx.objectStore(STAMP_STORE)
      // Spread `value` FIRST so the controlled `key` always wins: the DumpValue type forbids `key` today,
      // but ordering it last removes any dependence on that — a future value shape can never shadow the
      // row's primary key. The dump record carries NO accessedAt (the stamp lives in STAMP_STORE).
      dumps.put({ ...value, key })
      stamps.put({ key, accessedAt: this.stamp() })
      // Eviction is driven by the stamp store's count (== the dump count in normal operation, since every
      // put/delete touches both stores in one tx). Trim the least-recently-used entries — by ascending
      // accessedAt — from BOTH stores when over the bound.
      const countReq = stamps.count()
      countReq.onsuccess = () => {
        const over = countReq.result - this.maxEntries
        if (over > 0) {
          const cursorReq = stamps.index('accessedAt').openCursor()
          let removed = 0
          cursorReq.onsuccess = () => {
            const cur = cursorReq.result
            if (cur && removed < over) {
              dumps.delete(cur.value.key as IDBValidKey) // drop the heavy dump...
              cur.delete()                               // ...and its stamp, together in this tx
              removed++
              cur.continue()
            }
          }
          // Eviction is best-effort: a cursor failure must not go unobserved. If it aborts the tx,
          // tx.onerror below rejects the (best-effort) put; if not, the next put recomputes `over`
          // from the live count and trims the residue, so the bound self-corrects.
          cursorReq.onerror = () => { /* handled via tx.onerror */ }
        }
      }
      // A transaction can terminate three ways. `oncomplete` is success. A request error surfaces via
      // `onerror`. But an ABORT (quota pressure raised at commit time, an explicit abort, or a
      // versionchange / db.close() force-aborting in-flight transactions) fires ONLY `abort` — not
      // `error` — so without `onabort` this promise would never settle and the `await cache.put(...)`
      // in the pipeline would hang the render at "Loading…" forever. Mirror get()'s three-way handling.
      // tx.error is null for an explicit abort, so fall back to a meaningful error for the warn log.
      const fail = () => reject(tx.error ?? new DOMException('IndexedDB put transaction aborted', 'AbortError'))
      tx.oncomplete = () => resolve()
      tx.onerror = fail
      tx.onabort = fail
    })
  }
}
