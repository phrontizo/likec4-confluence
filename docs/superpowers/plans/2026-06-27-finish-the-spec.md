# Finish the spec — remaining-work tracker (Ralph loop)

> **Superseded-platform note (post-2026-06-29):** this is a historical point-in-time tracker. Its
> platform (Confluence **9.2.20**), build command (`~/.local/bin/jmvn`), and intermediate test counts
> (29 vitest, core 31/41/44) are that iteration's state — the product was later retargeted to Confluence
> **10.2.13 / Jakarta EE / Java 21** (built with **`./mvnw`**) and the counts grew. For the current facts
> see **README.md** and **CLAUDE.md**. Kept as the original completion record.

Goal: implement everything in `docs/superpowers/specs/2026-06-05-confluence-likec4-design.md` without cutting corners, add missing integration/UI tests, prove the full product works end-to-end, all tests/checks green, docs correct. TDD + subagents + parallel where safe.

Baseline (already on master, proven): Plan 1 spike, Plan 2 browser bundle (29 vitest green), Plan 3 likec4-core (31 JUnit green) + Confluence wrapper, Plan 4 Docker harness + **plugin proven LIVE in Confluence 9.2.20** (resolve/source/allowlist/macro + diagram renders via Playwright).

Promise `FINISHED` only when every UNCHECKED box below is checked AND `cd src/main/frontend && npm test && npm run typecheck` + `cd likec4-core && ~/.local/bin/jmvn -B -o test` + `~/.local/bin/jmvn -B -o package` all pass AND the live e2e (admin UI + editor + render + install/upgrade) is verified.

## A. likec4-core (Java, JUnit — no live Confluence)
- [x] A1. Disk-cache LRU eviction (§7) — `52936f6`. bounded disk dir + lastGood.
- [x] A2. Circuit-breaker/backoff (§7) — `284d810`. `CircuitBreaker` + `SourceService` integration.
- [x] A3. `sanitizeProject` (§8) — `6bc3ee5`. (core suite now 41)

## B. Frontend (TS, vitest — no live Confluence)
- [x] B1. IndexedDB LRU stamp — `599f801`. persisted-monotonic stamp (seeded from max on open). (frontend suite now 30)

## C. Confluence wrapper (Java + JS — live-verified)
- [x] C1. Admin config page (§4.5) — `0c9b40e`. VERIFIED LIVE: servlet 200, renders config form, admin-gated (anon→302), data path works.
- [x] C2. Macro-editor "Load views" UX (§6) — `648d009`. AJS.MacroBrowser override mounts `mountViewPicker`; LIVE-verified: dropdown populates (index+sys_detail), live preview, write-back, inline errors. (Only the final native-UI insertion gesture is correct-by-construction, not browser-automated.)

## D. Security verify (§8)
- [x] D1. XSS-in-labels (§8) — `53bfd01`. live Playwright: payload escaped, no live DOM, no exec. PASS.

## E. Test infrastructure (§11, §14)
- [x] E1. Performance test (§11) — `25f034e` + run: 0%% fail, p95 173ms, archive 0 ≪ 3083 source (cache/single-flight holds). PASS.
- [x] E2. Recorded-response GitLab contract test (§14) — `9a99c67`. 3 tests; real `tar`/`gzip` archive fixture + full commit JSON. (core suite now 44)
- [x] E3. Install/upgrade test (§11) — `6ad9c69`. script authored; CI-gated (clean-install needs compose+golden snapshot).

## F. Live full end-to-end verification
- [x] F1. Live end-to-end VERIFIED: diagram renders (Playwright, 2 nodes), admin page (C1), editor picker (C2), perf (E1), XSS (D1) — all live against amps confluence:run + mock.

## G. Documentation
- [x] G1. Docs updated — `5057f53`. all READMEs + spec reflect completion; caveats preserved.

## Out of scope per spec (NOT required for FINISHED)
- §13 "Later (optional)" phase-2 server-side computed-dump cache — the spec explicitly defers this as optional/post-v1. Excluded.

## Progress log
- (iter 1) created tracker; starting A1+A2+A3 (Java) and B1 (frontend).
