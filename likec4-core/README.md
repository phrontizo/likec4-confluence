# LikeC4 Confluence — Source Core (Plan 3)

Atlassian-independent server-side core: fetch a filtered, path-safe LikeC4 source subtree from
GitLab; cache it (ref→sha TTL + source-bundle LRU/single-flight/stale-while-revalidate, disk-backed);
encrypt the service token (AES-GCM); validate input (allowlist/traversal); render the macro
placeholder HTML (escaped). Built/tested with the committed `./mvnw` wrapper (Maven 3.9.9 +
JDK 21, `release 21`).

## Commands
- `./mvnw -B -ntp -f likec4-core/pom.xml test` — full JUnit suite (**255 tests**, all green; offline: add `-o`).
- `./mvnw -B -ntp -f likec4-core/pom.xml install -DskipTests` — install the jar so the root
  Confluence plugin build (which depends on `likec4-source-core` from `~/.m2`) picks up changes.

## REST contract (served by the Confluence module, consumed by the browser bundle)
- `GET /rest/likec4/1.0/resolve?project&ref` → `{ "sha" }`
- `GET /rest/likec4/1.0/source?project&ref&path` → `{ "sha", "files": { relPath: content } }`

## Confluence wrapper status — proven live
The Confluence P2 plugin (root `pom.xml` + `src/main/java/com/phrontizo/confluence`) is **built and
proven live in Confluence Data Center 10.2.13** (Jakarta EE; `docker compose` stack + mock-GitLab, see `docker/README.md`):
the macro renders the diagram, the admin config page works, the macro-editor view-picker works, and
the REST endpoints (`/resolve`, `/source`, `/admin`) serve with the allowlist enforced. REST DI is
wired via `atlassian-plugin.xml` `<component>`/`<component-import>` + static-`INSTANCE` (JAX-RS
resources are instantiated by Jersey/HK2, which cannot resolve Spring/SAL beans directly).

## Plan 4 follow-ups — all done
The Plan 3 final review listed four hardening items; all are now implemented and covered by tests:
- **On-disk bundle cache is bounded.** `SourceBundleCache` evicts the disk tier LRU and bounds
  `lastGood` (spec §7's "long, LRU-bounded" disk tier), and caps each in-memory tier by aggregate
  weight so a flood of large repos cannot pin many GiB. Covered by `SourceBundleCacheTest` (30 tests).
- **Circuit-breaker/backoff.** `CircuitBreaker` (fail-fast + backoff) is wired into `SourceService`
  alongside SWR (spec §7); a 4xx client error (e.g. a typo'd ref) is classified as non-outage so one
  misconfigured macro can't trip the process-shared breaker. Covered by `CircuitBreakerTest` (21 tests).
- **Admin servlet.** `AdminServlet` serves `/plugins/servlet/likec4/admin` (admin-gated config form
  over the existing `/rest/likec4/1.0/admin`); verified live. The `web-item` link now resolves to a
  real `<servlet>` module.
- **Defense-in-depth `sanitizeProject`.** `InputValidation.sanitizeProject` runs `project` through
  the `..`/charset validator; called from `SourceService` before any fetch. Covered by
  `InputValidationTest`.

The REST module path is correct: `<rest version="1.0">` supplies the `1.0` segment, so
`SourceRestResource` uses `@Path("/")` → `/rest/likec4/1.0/{resolve,source}`.

A GitLab **contract test** (`GitLabContractTest`) replays **actually-recorded gitlab.com API bytes**
(a real public repo's commit JSON + `repository/archive.tar.gz`, in `src/test/resources/contract/`)
and asserts the source filter drops every non-LikeC4 file, so API-shape drift is caught offline
(`639cf9e`).

## Token-encryption key-at-rest (residual 8)
The GitLab service token is encrypted with AES-256-GCM (`TokenCipher`). The encryption KEY is no
longer co-located with the ciphertext. `FileTokenKeyStore` persists the key to
`<dir>/likec4-token.key` with owner-only **0600** permissions; the Confluence wrapper supplies the
**Confluence home directory** (via SAL `ApplicationProperties.getLocalHomeDirectory()`, falling back
to `getSharedHomeDirectory()`) as that `dir`, so the key lands at `<confluence-home>/likec4-token.key`. The ciphertext stays in PluginSettings (the
DB). On first use the wrapper **migrates** any legacy key found in PluginSettings into the file and
deletes it from PluginSettings, so already-encrypted tokens stay decryptable.

**Threat model.** Previously the AES key sat in PluginSettings right next to the ciphertext, so a
single DB/PluginSettings dump yielded both key and ciphertext → trivially decryptable. Now key-at-
rest lives on the Confluence-home filesystem (0600), separate from the ciphertext in the DB. A DB
dump **alone** no longer decrypts tokens — read access to the home filesystem is additionally
required. **Residual risk (stated honestly):** an attacker who obtains BOTH the DB and the home
filesystem can still decrypt; a KMS/HSM (key never leaves the security boundary) would raise the bar
further and is out of scope for v1. `FileTokenKeyStore` is Atlassian-independent (takes a plain
`Path`); covered by `FileTokenKeyStoreTest`.

## Build-flow gotcha (still open)
The root plugin build depends on `likec4-source-core` being installed to `~/.m2`. After changing
anything under `likec4-core/`, run `./mvnw -o -f likec4-core/pom.xml install -DskipTests`
before building/packaging the root plugin, or the root build picks up the stale installed jar.
