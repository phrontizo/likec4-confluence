// k6 performance driver — spec §11 ("Performance").
//
// Runs inside the `grafana/k6` container (k6's own JS runtime, NOT Node). N concurrent VUs hammer
// the plugin's /resolve + /source REST endpoints under one (project, ref, path). The goal is to
// prove that the server-side cache + single-flight coalesce the load: the GitLab archive endpoint
// gets hit at most once per (project, sha, path) no matter how many /source requests arrive. That
// archive-count assertion lives in run.sh (it reads the mock's /__count); here we just generate the
// concurrent load and gate on liveness/latency thresholds.
//
// Env vars:
//   BASE      Confluence base incl. any context path. run.sh always sets this from
//             resolve-confluence-base.sh (the docker-compose stack, :8090 by default); the literal
//             fallback here is only used if k6 is run directly. Override to the retired amps
//             `confluence:run` backend with BASE=http://localhost:1990/confluence.
//   AUTH      base64 of admin:admin for HTTP Basic (default YWRtaW46YWRtaW4=)
//   VUS       concurrent virtual users (default 50)
//   DURATION  test duration (default 20s)
//   PROJECT   GitLab project (default acme/architecture)
//   REF       git ref (default main)
//   SRC_PATH  subtree path (default ok)  — NOT named PATH: that collides with the OS $PATH env var.
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:8090' // run.sh sets this from the compose stack; see header
const AUTH = __ENV.AUTH || 'YWRtaW46YWRtaW4=' // base64("admin:admin")
const PROJECT = __ENV.PROJECT || 'acme/architecture'
const REF = __ENV.REF || 'main'
const SRC_PATH = __ENV.SRC_PATH || 'ok'

// Custom counters so run.sh can read the exact number of REST calls k6 issued (via the exported
// end-of-test summary) and compare /source calls against the mock's archive count.
export const resolveReqs = new Counter('likec4_resolve_reqs')
export const sourceReqs = new Counter('likec4_source_reqs')
// Count SUCCESSFUL /source responses separately so run.sh can tell a 0-archive result that means
// "coalesced / cache already warm" (legit) apart from "every /source errored and never reached GitLab"
// (a real failure that also shows archive=0). The `source 200` check below is non-failing.
export const sourceOk = new Counter('likec4_source_ok')
// The exact analogue for /resolve: a 0-commits result must mean "ref→sha cache coalesced / already warm"
// (legit), NOT "every /resolve errored and never reached GitLab" (which ALSO yields commits=0). run.sh's
// (C') assertion requires this to be ≥1 so an all-resolve-failure run can't pass the (C) commits bound.
export const resolveOk = new Counter('likec4_resolve_ok')

const REST = `${BASE}/rest/likec4/1.0`
const params = {
  headers: { Authorization: `Basic ${AUTH}`, Accept: 'application/json' },
  tags: { name: 'likec4-rest' },
}

export const options = {
  scenarios: {
    hammer: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '20s',
    },
  },
  thresholds: {
    // <1% of requests may fail (non-2xx/3xx or transport error).
    http_req_failed: ['rate<0.01'],
    // Generous p95 bound: cache hits should be milliseconds; 2s leaves slack for cold start + CI.
    http_req_duration: ['p(95)<2000'],
  },
}

const q = encodeURIComponent

export default function () {
  // /resolve — ref→sha. RefShaCache should coalesce these onto a single mock `commits` hit.
  const r = http.get(`${REST}/resolve?project=${q(PROJECT)}&ref=${q(REF)}`, params)
  resolveReqs.add(1)
  if (r.status === 200) resolveOk.add(1)
  check(r, { 'resolve 200': (res) => res.status === 200 })

  // /source — subtree bundle. SourceBundleCache + single-flight should coalesce these onto a single
  // mock `archive` hit per (project, sha, path), regardless of VU count.
  const s = http.get(`${REST}/source?project=${q(PROJECT)}&ref=${q(REF)}&path=${q(SRC_PATH)}`, params)
  sourceReqs.add(1)
  if (s.status === 200) sourceOk.add(1)
  check(s, { 'source 200': (res) => res.status === 200 })

  sleep(0.1)
}

// Print a compact, parseable end-of-test digest to STDOUT (request counts + threshold metrics).
// `--summary-export` is deprecated/ignored on current k6 images, and an unprivileged k6 container
// usually can't write a summary file into a host-owned bind mount — so run.sh reads these stdout
// lines instead of a file. (With handleSummary() defined, custom-counter counts live at
// metrics.<name>.values.count.)
export function handleSummary(data) {
  const m = data.metrics || {}
  const vals = (n) => (m[n] && m[n].values) || {}
  const cnt = (n) => vals(n).count || 0
  const dur = vals('http_req_duration')
  const failedRate = (vals('http_req_failed').rate || 0) * 100
  const lines = [
    '',
    '  === k6 summary (handleSummary) ===',
    `  http_reqs:           ${cnt('http_reqs')}`,
    `  iterations:          ${cnt('iterations')}`,
    `  likec4_resolve_reqs: ${cnt('likec4_resolve_reqs')}`,
    `  likec4_resolve_ok:   ${cnt('likec4_resolve_ok')}`,
    `  likec4_source_reqs:  ${cnt('likec4_source_reqs')}`,
    `  likec4_source_ok:    ${cnt('likec4_source_ok')}`,
    `  http_req_failed:     ${failedRate.toFixed(2)}%   (threshold: <1%)`,
    `  http_req_duration:   avg=${(dur.avg || 0).toFixed(1)}ms  p95=${(dur['p(95)'] || 0).toFixed(1)}ms  max=${(dur.max || 0).toFixed(1)}ms   (threshold p95<2000ms)`,
    '',
  ]
  return { stdout: lines.join('\n') }
}
