# big/ fixture — real LikeC4 example

Source: github.com/likec4/likec4 `examples/cloud-system` at tag **v1.58.0**
(matches the plugin's pinned likec4 1.58.0). Downloaded verbatim; only the
`.likec4rc` project-config (not needed by the browser `fromSources` path and not
fetched by the LikeC4 file filter) and `README.md` were omitted.

Used as the large/complex stress-test model: 27 model elements + 49
deployment-model elements (21 nested deployment nodes + 28 deployed instances)
across nested boundaries, 21 views (17 element, 2 deployment, 2 dynamic), incl.
3 curated `.likec4/*.likec4.snap` manual-layout snapshots. (Counts reproduce via
`likec4 export json`; the earlier "49 deployment instances" wording conflated the
49 total deployment elements with the 28 that are true `instanceOf` instances.)
