import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CACHE_VERSION, DB_NAME, DB_VERSION, IndexedDbDumpCache, STAMP_STORE, STORE, cacheKey } from '../src/cache'

beforeEach(() => {
  // Fresh database per test.
  globalThis.indexedDB = new IDBFactory()
})

const dump = (tag: string) => ({ data: { tag }, errors: [], drifts: [] })

/** Row count in the live store — the cache exposes no count, so read it directly for the bound check. */
function count(): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onsuccess = () => {
      const tx = req.result.transaction(STORE, 'readonly')
      const cr = tx.objectStore(STORE).count()
      cr.onsuccess = () => resolve(cr.result)
      cr.onerror = () => reject(cr.error)
    }
    req.onerror = () => reject(req.error)
  })
}

/** The persisted LRU stamp (accessedAt) for each stored key — read from the dedicated stamp store. */
function accessedAtByKey(): Promise<Record<string, number>> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onsuccess = () => {
      const out: Record<string, number> = {}
      const tx = req.result.transaction(STAMP_STORE, 'readonly')
      const cur = tx.objectStore(STAMP_STORE).openCursor()
      cur.onsuccess = () => {
        const c = cur.result
        if (c) { out[c.value.key] = c.value.accessedAt; c.continue() }
      }
      tx.oncomplete = () => resolve(out)
      tx.onerror = () => reject(tx.error)
    }
    req.onerror = () => reject(req.error)
  })
}

/** Delete ONLY the LRU stamp for a key, leaving the heavy dump row in STORE — models the "valid dump
 *  present, stamp absent" partial state the get() self-heal branch is meant to recover from. */
function deleteStamp(key: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onsuccess = () => {
      const tx = req.result.transaction(STAMP_STORE, 'readwrite')
      tx.objectStore(STAMP_STORE).delete(key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    }
    req.onerror = () => reject(req.error)
  })
}

describe('IndexedDbDumpCache', () => {
  it('returns null for a missing key', async () => {
    const c = new IndexedDbDumpCache()
    expect(await c.get('nope')).toBeNull()
  })

  it('round-trips a stored dump', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('sha1:path', dump('a'))
    const got = await c.get('sha1:path')
    expect(got?.data).toEqual({ tag: 'a' })
    expect(got?.key).toBe('sha1:path')
  })

  it('self-heals a valid dump whose LRU stamp is missing on read', async () => {
    // A partial legacy state (or an interrupted write) can leave a valid dump in STORE with no matching
    // STAMP_STORE row. get() must still RETURN that dump AND re-write its stamp (self-heal, cache.ts:163-165),
    // so the recovered row rejoins LRU ordering rather than staying stampless and mis-ordered forever.
    const c = new IndexedDbDumpCache()
    await c.put('sha1:path', dump('a')) // creates both stores + a stamp
    await deleteStamp('sha1:path')      // drop ONLY the stamp, leaving the dump in STORE
    expect(await accessedAtByKey()).toEqual({}) // precondition: no stamp remains

    const got = await c.get('sha1:path')
    expect(got?.data).toEqual({ tag: 'a' }) // the dump is still served despite the absent stamp

    const stamps = await accessedAtByKey()
    expect(typeof stamps['sha1:path']).toBe('number') // and its stamp was re-written (healed)
  })

  it('evicts the least-recently-used entry beyond the bound', async () => {
    const c = new IndexedDbDumpCache(3)
    await c.put('k0', dump('0'))
    await c.put('k1', dump('1'))
    await c.put('k2', dump('2'))
    await c.get('k0') // touch k0 so k1 becomes LRU
    await c.put('k3', dump('3')) // count 4 > 3 → evict 1
    expect(await c.get('k1')).toBeNull()
    expect(await c.get('k0')).not.toBeNull()
    expect(await c.get('k3')).not.toBeNull()
  })

  it('does NOT evict when the store is filled to EXACTLY the bound (count === cap is not over)', async () => {
    // Eviction is driven by `over = count - maxEntries`, and only trims when over > 0 (count STRICTLY
    // above the bound). Filling to exactly the cap leaves over === 0, so the put that reaches the cap must
    // keep every entry — the oldest included. An off-by-one that biased `over` up (e.g. `count - cap + 1`)
    // would wrongly drop the LRU row the instant the cache first fills, one entry earlier than intended.
    const c = new IndexedDbDumpCache(3)
    await c.put('k0', dump('0')) // oldest
    await c.put('k1', dump('1'))
    await c.put('k2', dump('2')) // count now EXACTLY 3 === cap
    expect(await count()).toBe(3)
    expect(await c.get('k0')).not.toBeNull() // the oldest survives at the cap — no premature eviction
    expect(await c.get('k1')).not.toBeNull()
    expect(await c.get('k2')).not.toBeNull()
  })

  it('re-putting an EXISTING key at the cap does not grow the count or evict anything', async () => {
    // eviction reads the LIVE stamp count and IndexedDB request ordering guarantees the count() observes
    // the queued put. Overwriting an existing key is a same-key `put` — it must NOT add a row, so the
    // count stays at the cap and `over` stays 0. A regression that counted an overwrite as a new row
    // (e.g. incrementing before the store dedupes on keyPath) would spuriously evict the true LRU entry.
    const c = new IndexedDbDumpCache(3)
    await c.put('k0', dump('0')) // oldest
    await c.put('k1', dump('1'))
    await c.put('k2', dump('2')) // count now EXACTLY 3 === cap
    await c.put('k0', dump('0-updated')) // OVERWRITE the oldest key — not a new row
    expect(await count()).toBe(3) // still at the cap, no growth
    expect((await c.get('k0'))?.data).toEqual({ tag: '0-updated' }) // overwrite took effect
    expect(await c.get('k1')).not.toBeNull() // nothing evicted
    expect(await c.get('k2')).not.toBeNull()
  })

  it('a put trims accumulated over-bound residue back to the bound in one pass', async () => {
    // The eviction comment promises the bound "self-corrects": if a prior put left the store over the
    // bound (its eviction cursor aborted, or a smaller bound now applies), the NEXT put recomputes
    // `over` from the LIVE count and trims ALL the excess at once — not just a single row. Simulate the
    // residue with a large-bound instance (no eviction), then enforce a small bound on the shared DB.
    const big = new IndexedDbDumpCache(100)
    for (let i = 0; i < 5; i++) await big.put(`k${i}`, dump(String(i)))
    const small = new IndexedDbDumpCache(3)
    await small.put('k5', dump('5')) // live count 6, over = 6 - 3 = 3 → trim three in this one put
    expect(await count()).toBe(3)
    // The three oldest (lowest LRU stamp) are the ones evicted; the three most-recent survive.
    expect(await small.get('k0')).toBeNull()
    expect(await small.get('k1')).toBeNull()
    expect(await small.get('k2')).toBeNull()
    expect(await small.get('k5')).not.toBeNull()
  })

  it('keeps the LRU stamp monotonic across a reload (fresh JS context)', async () => {
    // First "session": three entries, each touched so the oldest still carries
    // a stamp well above any counter that would restart from zero on reload.
    const c1 = new IndexedDbDumpCache(10)
    await c1.put('k0', dump('0'))
    await c1.put('k1', dump('1'))
    await c1.put('k2', dump('2'))
    await c1.get('k0')
    await c1.get('k1')
    await c1.get('k2')

    // "Reload": re-evaluate the module so any in-memory counter restarts from
    // zero, exactly as a fresh page load would, then reopen the SAME db.
    vi.resetModules()
    const { IndexedDbDumpCache: Reloaded } = await import('../src/cache')
    const c2 = new Reloaded(3)
    await c2.put('k3', dump('3')) // 4 entries > bound 3 → evict the oldest original

    // The freshly-computed entry must survive; the oldest original is evicted.
    // On the buggy code k3 gets a reset-low stamp and is wrongly evicted.
    expect(await c2.get('k3')).not.toBeNull()
    expect(await c2.get('k0')).toBeNull()
  })

  it('draws globally-monotonic LRU stamps across two instances sharing one DB', async () => {
    // The viewer and the macro editor construct SEPARATE IndexedDbDumpCache instances against the same
    // database on the same page. Interleaved writes must carry strictly increasing stamps in write order,
    // else instance B's stamp can collide with (or fall below) instance A's — breaking cross-instance LRU
    // ordering so a fresh entry looks as old as an earlier one. A per-instance counter collides here
    // (both seed 0 → both reach stamp 2); a shared module-level counter keeps them globally monotonic.
    const a = new IndexedDbDumpCache(50)
    const b = new IndexedDbDumpCache(50)
    await a.put('a1', dump('a1'))
    await b.put('b1', dump('b1'))
    await a.put('a2', dump('a2'))
    await b.put('b2', dump('b2'))
    const s = await accessedAtByKey()
    expect(s.a1).toBeLessThan(s.b1)
    expect(s.b1).toBeLessThan(s.a2)
    expect(s.a2).toBeLessThan(s.b2)
  })

  it('treats a malformed/partially-written row as a miss and removes it', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('good', dump('g')) // create the DB + store at the current version
    // Poison a row directly with the wrong shape (no errors/drifts arrays) — as a partial write or a
    // future incompatible shape would. The pipeline would crash on `errors.length` if this were served.
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onsuccess = () => {
        const db = req.result
        const tx = db.transaction(STORE, 'readwrite')
        tx.objectStore(STORE).put({ key: 'bad', data: { x: 1 }, accessedAt: 1 })
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onerror = () => reject(tx.error)
      }
      req.onerror = () => reject(req.error)
    })
    expect(await c.get('bad')).toBeNull() // malformed → miss (recompute), not a crash
    expect(await c.get('bad')).toBeNull() // and it was deleted, so it won't keep poisoning the diagram
    expect(await c.get('good')).not.toBeNull() // a valid row is unaffected
  })

  it('ignores a non-numeric persisted accessedAt when seeding the LRU counter (no NaN reseed)', async () => {
    // The seed cursor reads the highest indexed accessedAt on open. A foreign/older stamp row carrying a
    // NON-numeric accessedAt would make `Number(cur.key)` NaN, and Math.max(seq, NaN) === NaN — poisoning
    // EVERY subsequent stamp (NaN++ === NaN) and wrecking LRU ordering (every entry looks equally old, so
    // eviction becomes arbitrary). The Number.isFinite guard must ignore such a seed. Pin it: after opening
    // over a poisoned stamp, a fresh put must still carry a FINITE, positive stamp.
    const c0 = new IndexedDbDumpCache()
    await c0.put('real', dump('r')) // creates STORE + STAMP_STORE at the current DB_VERSION
    // Inject a stamp whose accessedAt is a string. In IndexedDB key ordering a string sorts ABOVE every
    // number, so the descending seed cursor returns THIS row first — exactly the poisoning case.
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onsuccess = () => {
        const db = req.result
        const tx = db.transaction(STAMP_STORE, 'readwrite')
        tx.objectStore(STAMP_STORE).put({ key: 'foreign', accessedAt: 'not-a-number' as unknown as number })
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onerror = () => reject(tx.error)
      }
      req.onerror = () => reject(req.error)
    })
    // Reload the module so the in-memory counter restarts from 0 and re-seeds from the (poisoned) store.
    vi.resetModules()
    const { IndexedDbDumpCache: Reloaded } = await import('../src/cache')
    const c = new Reloaded(10)
    await c.put('fresh', dump('f'))
    const stamps = await accessedAtByKey()
    expect(Number.isFinite(stamps.fresh), 'the fresh stamp must be finite, not NaN').toBe(true)
    expect(stamps.fresh).toBeGreaterThan(0) // the counter was not corrupted to NaN by the foreign row
    expect((await c.get('fresh'))?.data).toEqual({ tag: 'f' })
  })

  it('does not rewrite the dump blob on a read — only the lightweight LRU stamp', async () => {
    // A cache HIT bumps the LRU stamp. The dump `data` can be multi-MB for a large model, so the touch
    // must write ONLY the small {key, accessedAt} stamp record — never re-serialize + re-write the whole
    // dump (which would pay a large I/O cost on every hit and defeat much of the cache's latency benefit).
    const c = new IndexedDbDumpCache()
    await c.put('k', dump('big')) // warm the DB (this legitimately writes the dump + a stamp)
    const origPut = IDBObjectStore.prototype.put
    const putValues: Array<Record<string, unknown>> = []
    const spy = vi
      .spyOn(IDBObjectStore.prototype, 'put')
      .mockImplementation(function (this: IDBObjectStore, value: unknown, key?: IDBValidKey) {
        putValues.push(value as Record<string, unknown>)
        return origPut.call(this, value, key) // forward to the real put so behaviour is unchanged
      })
    try {
      const got = await c.get('k')
      expect(got?.data).toEqual({ tag: 'big' }) // the hit is still served
    } finally {
      spy.mockRestore()
    }
    // Every write performed DURING the read must be a bare stamp (no `data`/`errors`/`drifts` payload).
    expect(putValues.length).toBeGreaterThan(0)
    expect(putValues.every((v) => !('data' in v))).toBe(true)
    expect(putValues.every((v) => 'key' in v && 'accessedAt' in v)).toBe(true)
  })

  it('returns a cached hit even if the LRU stamp write fails (best-effort touch)', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('k', dump('v'))
    // Force the LRU touch (the store.put inside get) to throw, leaving the read result intact. A
    // failed stamp write (e.g. quota) must NOT throw away a perfectly serviceable cached dump.
    const spy = vi
      .spyOn(IDBObjectStore.prototype, 'put')
      .mockImplementationOnce(() => {
        throw new Error('quota exceeded')
      })
    const got = await c.get('k')
    expect(got?.data).toEqual({ tag: 'v' })
    spy.mockRestore()
  })

  it('resolves get() to null (a miss) when the row READ request itself errors, so the pipeline recomputes', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('k', dump('v'))
    // A genuine per-request read failure inside an otherwise-fine transaction (getReq.onerror) must be
    // treated as a MISS -> recompute, never a rejection that would surface as a pipeline error. Force the
    // dumps.get() request to error by returning a fake request whose onerror handler fires; the real
    // (request-less) transaction still completes and resolves the captured `result` (null).
    const spy = vi
      .spyOn(IDBObjectStore.prototype, 'get')
      .mockImplementationOnce(() => {
        const req: Record<string, unknown> = { result: undefined, onsuccess: null }
        let handler: ((e: unknown) => void) | null = null
        Object.defineProperty(req, 'onerror', {
          get: () => handler,
          set: (fn: (e: unknown) => void) => {
            handler = fn
            queueMicrotask(() => handler && handler(new Event('error')))
          },
        })
        return req as unknown as IDBRequest
      })
    try {
      await expect(c.get('k')).resolves.toBeNull()
    } finally {
      spy.mockRestore()
    }
  })

  it('rejects get() when IndexedDB is unavailable so the pipeline recomputes', async () => {
    const realIdb = globalThis.indexedDB
    // Private-mode / disabled storage: indexedDB.open throws. get() must surface that (the pipeline
    // catches it and degrades to recompute) rather than hanging.
    globalThis.indexedDB = { open: () => { throw new Error('IndexedDB disabled') } } as unknown as IDBFactory
    try {
      const c = new IndexedDbDumpCache()
      await expect(c.get('x')).rejects.toBeTruthy()
    } finally {
      globalThis.indexedDB = realIdb
    }
  })

  it('retries the db open after a transient failure instead of caching the rejection forever', async () => {
    const realIdb = globalThis.indexedDB
    let calls = 0
    // First open throws (a one-off: a transient quota error during the seed, a version-change block);
    // the retry succeeds. The cache must re-attempt rather than returning the same rejected promise for
    // the rest of the page — which would silently defeat the cache (every diagram recomputes forever).
    globalThis.indexedDB = {
      open: (...args: unknown[]) => {
        calls++
        if (calls === 1) throw new Error('transient open failure')
        // @ts-expect-error forward to the real fake-indexeddb factory on retry
        return realIdb.open(...args)
      },
    } as unknown as IDBFactory
    try {
      const c = new IndexedDbDumpCache()
      await expect(c.get('x')).rejects.toBeTruthy() // first attempt fails
      expect(await c.get('x')).toBeNull() // second attempt re-opens and succeeds (missing key → null)
      expect(calls).toBe(2)
    } finally {
      globalThis.indexedDB = realIdb
    }
  })

  it('rejects db open (no hang) when the seed step throws synchronously', async () => {
    // The seed step runs objectStore().index().openCursor() synchronously inside req.onsuccess. If any
    // of it throws (a corrupt DB, or the accessedAt index missing at open time — a shape the current
    // onupgradeneeded didn't produce), the open promise must REJECT — not let the exception escape the
    // handler and leave db() pending forever, hanging every later get/put on an open that never settles.
    // Force the seed's index() lookup (the first index() call, made only by the seed) to throw.
    const spy = vi
      .spyOn(IDBObjectStore.prototype, 'index')
      .mockImplementationOnce(() => {
        throw new Error('accessedAt index missing')
      })
    try {
      const c = new IndexedDbDumpCache()
      await expect(c.get('x')).rejects.toBeTruthy()
    } finally {
      spy.mockRestore()
    }
  })

  it('closes its connection on versionchange so a newer build can upgrade (no deadlock)', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('k', dump('v')) // opens + HOLDS the DB connection (with db.onversionchange wired)
    // A newer build opens the SAME database at a higher version, which needs an upgrade. Without
    // db.onversionchange=close on our held connection, that upgrade is blocked and its open never
    // completes — a deadlock. With it, our connection closes and the upgrade proceeds. If this resolves
    // (rather than timing out) the versionchange yield worked.
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION + 1)
      req.onsuccess = () => { req.result.close(); resolve() }
      req.onerror = () => reject(req.error)
    })
  })

  it('closes the opened connection when the seed step throws (no leaked connection)', async () => {
    // A synchronous seed failure rejects the open (degrading to recompute), but the connection that was
    // already opened must be CLOSED — otherwise it leaks and its stray open handle can onblocked a later
    // DB_VERSION upgrade. Force the seed's index() lookup to throw, then assert close() ran.
    const closeSpy = vi.spyOn(IDBDatabase.prototype, 'close')
    const indexSpy = vi
      .spyOn(IDBObjectStore.prototype, 'index')
      .mockImplementationOnce(() => { throw new Error('accessedAt index missing') })
    try {
      const c = new IndexedDbDumpCache()
      await expect(c.get('x')).rejects.toBeTruthy()
      expect(closeSpy).toHaveBeenCalled()
    } finally {
      indexSpy.mockRestore()
      closeSpy.mockRestore()
    }
  })

  it('recovers (reopens) after a versionchange closes our held connection — not permanently dead', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('k', dump('v')) // opens + HOLDS the connection (with db.onversionchange wired)
    // Another connection deletes the DB, firing versionchange on our held connection; our handler closes
    // it so the delete proceeds. Before the fix, this.dbp still resolved to that now-CLOSED connection,
    // so every later get/put threw InvalidStateError from db.transaction() forever (a permanently dead
    // cache — the open-time p.catch reset only fires when the OPEN rejects, not when a resolved
    // connection is closed after the fact). After the fix the closed connection is dropped so the next
    // call reopens.
    await new Promise<void>((resolve, reject) => {
      const del = indexedDB.deleteDatabase(DB_NAME)
      del.onsuccess = () => resolve()
      del.onerror = () => reject(del.error)
      del.onblocked = () => reject(new Error('delete blocked — our connection did not yield to versionchange'))
    })
    // A working cache reopens the (now-recreated) DB and reports a miss rather than rejecting from a
    // stale closed connection — and remains fully usable for writes/reads afterwards.
    await expect(c.get('k')).resolves.toBeNull()
    await c.put('k2', dump('v2'))
    expect((await c.get('k2'))?.data).toEqual({ tag: 'v2' })
  })

  it('rejects (does not hang) when the db open is blocked by an older connection', async () => {
    // An older tab holds this DB open at a LOWER version with no versionchange handler, so it will not
    // yield. The cache opens at the current (higher) DB_VERSION, which needs an upgrade and is therefore
    // BLOCKED. onblocked must reject so get() degrades to recompute rather than awaiting a never-settled
    // open. (DB_VERSION is >1, so version 1 is genuinely older here.)
    const older = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1)
      req.onupgradeneeded = () => {
        const store = req.result.createObjectStore(STORE, { keyPath: 'key' })
        store.createIndex('accessedAt', 'accessedAt')
      }
      req.onsuccess = () => resolve(req.result) // held open, no onversionchange → blocks the v2 upgrade
      req.onerror = () => reject(req.error)
    })
    try {
      const c = new IndexedDbDumpCache()
      await expect(c.get('x')).rejects.toBeTruthy()
    } finally {
      older.close()
    }
  })

  it('rejects put() when the transaction aborts without an error (no hang)', async () => {
    const c = new IndexedDbDumpCache()
    await c.get('warm') // open the DB first so the seed (readonly) tx is not the one we abort
    // An explicit tx.abort() (as quota pressure at commit, a versionchange, or db.close() would cause)
    // fires only the `abort` event, NOT `error`. Without a tx.onabort handler the put promise never
    // settles and `await cache.put(...)` in the pipeline hangs the render at "Loading…" forever.
    const origTx = IDBDatabase.prototype.transaction
    const spy = vi
      .spyOn(IDBDatabase.prototype, 'transaction')
      .mockImplementation(function (this: IDBDatabase, ...args: unknown[]) {
        // @ts-expect-error forward to the real transaction factory
        const tx = origTx.apply(this, args) as IDBTransaction
        if (args[1] === 'readwrite') queueMicrotask(() => tx.abort())
        return tx
      })
    try {
      await expect(c.put('k', dump('v'))).rejects.toBeTruthy()
    } finally {
      spy.mockRestore()
    }
  })

  it('scopes the database by the likec4/app version token (FE-I6)', () => {
    expect(DB_NAME).toContain(CACHE_VERSION)
    // The key length-prefixes the sha so (sha, path) is decoded unambiguously (version isolation is at
    // the DB level). "4:sha1:sub" = <sha length>:<sha>:<path>.
    expect(cacheKey('sha1', 'sub')).toBe('4:sha1:sub')
    expect(cacheKey('sha1', undefined)).toBe('4:sha1:')
  })

  it('never collides across different (sha, path) pairs that share a raw concatenation (FE cache-key)', () => {
    // Without the length prefix, ("a", "b:c") and ("a:b", "c") both concatenate to "a:b:c". A path may
    // legitimately contain a colon, so the key must remain injective. The length prefix pins the sha
    // boundary, so the two decode to distinct keys.
    expect(cacheKey('a', 'b:c')).not.toBe(cacheKey('a:b', 'c'))
  })

  it('purges incompatible rows when the DB version is bumped (FE-I6)', async () => {
    // Simulate an OLDER build's database: same name, an EARLIER version, holding a row whose
    // serialized shape predates the current likec4/app version.
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1)
      req.onupgradeneeded = () => {
        const db = req.result
        const store = db.createObjectStore(STORE, { keyPath: 'key' })
        store.createIndex('accessedAt', 'accessedAt')
        store.put({ key: 'stale', data: { old: true }, errors: [], drifts: [], accessedAt: 1 })
      }
      req.onsuccess = () => { req.result.close(); resolve() }
      req.onerror = () => reject(req.error)
    })
    // Opening through the cache (current DB_VERSION > 1) must run the upgrade that drops+recreates
    // the store, purging the now-incompatible row instead of leaving it readable.
    const c = new IndexedDbDumpCache()
    expect(await c.get('stale')).toBeNull()
  })
})
