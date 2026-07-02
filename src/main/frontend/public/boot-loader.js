/*
 * Classic-script shim for loading the LikeC4 ESM bundle inside a Confluence web-resource.
 *
 * Atlassian web-resources inject a plain <script> (no type="module"), but the bundle is ESM
 * (dynamic-import code-splitting + a module Web Worker). This loader is the plain <script>
 * Confluence injects; it injects the real ESM entry as <script type="module">, which the browser
 * defers until the DOM is parsed — so boot() in main.js finds the macro's `.likec4-diagram` divs.
 *
 * The ESM entry + its lazy chunks + the module worker live in the `likec4-web/` *download* resource
 * and MUST be served from the /download/resources/<module-key>/ endpoint. In production Confluence
 * batches THIS loader into a /download/batch/.../_/... super-batch URL — and the batch endpoint
 * serves the loader's own JS for ANY sub-path (it ignores the suffix), so deriving the entry URL
 * from the loader's own (possibly batched) src is wrong (you get the loader back, not the bundle).
 * Instead we reconstruct the absolute /download/resources/ URL: the context-root is the part of our
 * own src before the WRM `/s/` super-batch prefix (batched) or before `/download/` (unbatched dev),
 * and the module-complete-key is the `<plugin-key>:<web-resource-key>` path segment of our src.
 */
(function () {
  var me = document.currentScript;
  if (!me || !me.src) return;
  var src = me.src;

  // context-root = origin (+ context path), i.e. everything before the WRM super-batch `/s/` prefix
  // (production) or before `/download/` (unbatched dev/amps).
  var cut = src.indexOf('/s/');
  if (cut === -1) cut = src.indexOf('/download/');
  var root;
  if (cut !== -1) {
    root = src.slice(0, cut);
  } else {
    // Neither the WRM `/s/` batch prefix nor `/download/` was found in our own src (an unexpected
    // serving path — e.g. a reverse proxy that rewrites /download/). Fall back to AJS.contextPath()
    // so asset URLs still resolve, and warn so a misconfigured deploy is diagnosable rather than
    // silently rendering a blank/unstyled diagram.
    root = (window.AJS && AJS.contextPath && AJS.contextPath()) || '';
    if (window.console && console.warn) {
      console.warn('likec4: could not derive the resource root from "' + src + '"; falling back to AJS.contextPath()');
    }
  }

  // module-complete-key, e.g. com.phrontizo.confluence.likec4-confluence:likec4-web
  // The literal fallback below is <${atlassian.plugin.key}>:likec4-web (see atlassian-plugin.xml). This
  // file is copied VERBATIM by Vite (no Maven resource filtering), so the key can't be templated here —
  // keep this literal in lockstep with the plugin key if it is ever renamed, or the fallback path 404s.
  var m = src.match(/\/download\/(?:batch|resources)\/([^\/]+:[^\/]+)\//);
  var moduleKey = m ? m[1] : 'com.phrontizo.confluence.likec4-confluence:likec4-web';

  // Absolute /download/resources/ base for the bundle directory (works batched + unbatched, the
  // same reconstruction used for the ESM entry below).
  var resBase = root + '/download/resources/' + moduleKey + '/likec4-web/';
  var entry = resBase + 'assets/main.js';

  // --- Inject the entry STYLESHEET unconditionally --------------------------------------------
  // Our styles.css (.likec4-error / .likec4-loading / .likec4-viewer / ...) is imported by the
  // boot entry, but Vite's CSS code-splitting bundles it into a *statically-imported* shared
  // chunk's CSS sidecar (assets/preload-helper-*.css). Because we inject the ESM entry directly
  // (we bypass Vite's generated index.html), a plain `import` of that chunk does NOT inject its
  // CSS — only `__vitePreload` of the *dynamic* lazy `Diagram` import does. So the error/loading
  // path (which never loads the Diagram chunk) used to render UNSTYLED (Confluence defaults).
  // Fix: read the Vite manifest and inject a <link rel="stylesheet"> for the entry's transitive
  // (static-import) CSS, so the panels are styled regardless of which lazy chunks load.
  function injectEntryCss() {
    if (document.getElementById('likec4-entry-css')) return;
    var marker = document.createElement('meta');
    marker.id = 'likec4-entry-css';
    document.head.appendChild(marker); // idempotency sentinel (set before the async fetch resolves)

    fetch(resBase + '.vite/manifest.json', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (manifest) {
        // A transient manifest miss (404/blip) must not PERMANENTLY suppress styling: drop the
        // idempotency marker so a later boot() on the same page can retry the injection.
        if (!manifest) { marker.remove(); return; }
        // Walk the entry (index.html) + its transitive STATIC imports, collecting every `css`
        // sidecar. (We deliberately skip `dynamicImports` so the heavy lazy Diagram CSS stays
        // lazy — only the entry's own styles, which include our .likec4-error panel, load here.)
        var files = [];
        var seenFile = {};
        var seenKey = {};
        (function collect(key) {
          if (!key || seenKey[key]) return;
          seenKey[key] = true;
          var node = manifest[key];
          if (!node) return;
          (node.css || []).forEach(function (f) { if (!seenFile[f]) { seenFile[f] = true; files.push(f); } });
          (node.imports || []).forEach(collect);
        })('index.html');

        files.forEach(function (f) {
          var href = resBase + f;
          // Idempotency: skip if this stylesheet href is already present. Iterate + compare the raw href
          // attribute rather than build a `link[href="..."]` selector by string concatenation — a `"` in
          // href (never produced by our own Vite manifest today, but robust regardless) would make that
          // selector a SyntaxError that aborts injectEntryCss and leaves the panels unstyled.
          var links = document.querySelectorAll('link[rel="stylesheet"]');
          for (var i = 0; i < links.length; i++) { if (links[i].getAttribute('href') === href) return; }
          var link = document.createElement('link');
          link.rel = 'stylesheet';
          link.href = href;
          link.setAttribute('data-likec4-entry-css', '');
          document.head.appendChild(link);
        });
      })
      .catch(function (e) {
        // Non-fatal: diagram pages still self-style via the lazy Diagram chunk's CSS sidecar. But the
        // error/loading panels (which never load that chunk) would render unstyled, so leave a trace
        // and drop the idempotency marker so a later boot() can retry rather than stay unstyled forever.
        marker.remove();
        if (window.console && console.debug) console.debug('likec4: entry-CSS manifest fetch failed', e);
      });
  }

  function load() {
    if (document.getElementById('likec4-esm-entry')) return;
    var s = document.createElement('script');
    s.type = 'module';
    s.id = 'likec4-esm-entry';
    s.src = entry;
    document.head.appendChild(s);
  }

  // Kick off CSS injection immediately (parallel with the ESM entry) so the stylesheet is present
  // before the React panels paint. `document.head` exists — this loader runs from inside <head>.
  injectEntryCss();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();
