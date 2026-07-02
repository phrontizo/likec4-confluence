/*
 * Classic-script loader + macro-editor override for the LikeC4 diagram macro (spec §6).
 *
 * Confluence loads this as a plain <script> in the editor context (web-resource <context>editor</context>).
 * Two jobs:
 *   1. Register a Macro Browser JS override for `likec4-diagram` so that inserting/editing the macro
 *      opens our custom editor instead of the default parameter form.
 *   2. Lazily inject the ESM editor bundle (likec4-web/assets/editor-confluence.js) the first time the
 *      author opens the macro editor — it publishes window.LikeC4Editor.{openMacroEditor,createDeps}.
 *
 * The override's opener owns the whole flow; on Insert it writes the chosen params back into the
 * editor via tinymce.confluence.MacroUtils.insertMacro (the same API the Macro Browser itself uses).
 */
(function () {
  // In the editor context Confluence concatenates this file into a combined JS *batch*, so
  // document.currentScript.src is the batch URL (not editor-loader.js). We therefore cannot derive
  // the bundle path from our own src like the (individually-served) viewer boot-loader does. Build
  // the stable WRM download URL from the plugin module key instead; when this file happens to be
  // served individually we still honour our own src.
  var me = document.currentScript;
  // <${atlassian.plugin.key}>:likec4-editor (see atlassian-plugin.xml). This file is copied VERBATIM by
  // Vite (no Maven resource filtering), so the key can't be templated here — keep this literal in
  // lockstep with the plugin key if it is ever renamed, or the editor bundle URL 404s.
  var MODULE_KEY = 'com.phrontizo.confluence.likec4-confluence:likec4-editor';
  var MACRO = 'likec4-diagram';
  var bundlePromise = null;
  // Bounded readiness polls. Budgets: bundle-global ~ MAX_TRIES*GLOBAL_POLL_MS (5s);
  // macro-browser register ~ MAX_TRIES*REGISTER_POLL_MS (10s).
  var MAX_TRIES = 100;
  var GLOBAL_POLL_MS = 50;
  var REGISTER_POLL_MS = 100;

  function entryUrl() {
    if (me && me.src && /\/editor-loader\.js(\?|#|$)/.test(me.src)) {
      return me.src.slice(0, me.src.lastIndexOf('/') + 1) + 'likec4-web/assets/editor-confluence.js';
    }
    // A genuinely root-deployed Confluence returns '' here, which is correct; but if AJS.contextPath
    // is entirely UNAVAILABLE the '' fallback yields an origin-relative URL that breaks under a
    // non-root context path. Warn in that case (parity with boot-loader) so a misconfigured editor
    // deploy is diagnosable rather than silently failing to author the macro.
    var hasCtx = !!(window.AJS && AJS.contextPath);
    var ctx = (hasCtx && AJS.contextPath()) || '';
    if (!hasCtx && window.console && console.warn) {
      console.warn('likec4: AJS.contextPath() unavailable; building the editor bundle URL origin-relative — this breaks under a non-root context path');
    }
    // The likec4-web/ download directory is served under the web-resource module key; the ESM
    // bundle's own relative imports + module-worker URL then resolve under the same prefix.
    return ctx + '/download/resources/' + MODULE_KEY + '/likec4-web/assets/editor-confluence.js';
  }

  // --- Inject the editor entry STYLESHEET (mirror of boot-loader.js injectEntryCss) ------------------
  // The editor's panel styles (.likec4-error / .likec4-loading / .likec4-editor / .likec4-viewer) live
  // in the CSS sidecar of a STATICALLY-imported shared chunk (_runtime-*.js). Because we inject the ESM
  // entry directly as <script type="module"> (bypassing Vite's generated HTML), that static-import CSS is
  // NOT injected on its own — only __vitePreload of the *dynamic* lazy <Diagram> chunk pulls it in. So
  // until (and unless) a diagram preview renders, the "Loading…" / ErrorPanel states render UNSTYLED
  // (Confluence defaults). Fix: read the Vite manifest and inject a <link> for the editor entry's
  // transitive (static-import) CSS, so the picker chrome is styled regardless of which lazy chunks load.
  function injectEntryCss() {
    if (document.getElementById('likec4-editor-entry-css')) return;
    // likec4-web/ base of the bundle (strip the entry's own assets/editor-confluence.js tail).
    var resBase = entryUrl().replace(/assets\/editor-confluence\.js$/, '');
    var marker = document.createElement('meta');
    marker.id = 'likec4-editor-entry-css';
    document.head.appendChild(marker); // idempotency sentinel (set before the async fetch resolves)

    fetch(resBase + '.vite/manifest.json', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (manifest) {
        // A transient manifest miss (404/blip) must not PERMANENTLY suppress styling: drop the
        // idempotency marker so a later openMacroEditor on the same page can retry the injection.
        if (!manifest) { marker.remove(); return; }
        // Walk the editor entry + its transitive STATIC imports, collecting every `css` sidecar. (We skip
        // `dynamicImports` so the heavy lazy Diagram CSS stays lazy — only the entry's own panel styles
        // load here.) The entry key is the Vite input id (rollupOptions.input['editor-confluence']).
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
        })('src/editor-confluence.tsx');

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
        // Non-fatal: a rendered diagram preview still self-styles via the lazy Diagram chunk's CSS
        // sidecar. But the loading/error panels (which never load that chunk) would render unstyled, so
        // drop the idempotency marker so a later open can retry rather than stay unstyled forever.
        marker.remove();
        if (window.console && console.debug) console.debug('likec4: editor entry-CSS manifest fetch failed', e);
      });
  }

  function ensureBundle() {
    // Inject the entry stylesheet alongside the (lazily-loaded) editor bundle so the picker's
    // Loading…/ErrorPanel states are styled as soon as the macro editor opens. Idempotent (marker-guarded).
    injectEntryCss();
    if (window.LikeC4Editor) return Promise.resolve(window.LikeC4Editor);
    if (bundlePromise) return bundlePromise;
    var p = new Promise(function (resolve, reject) {
      var existing = document.getElementById('likec4-editor-esm-entry');
      var s = existing || document.createElement('script');
      function waitForGlobal() {
        var tries = 0;
        (function check() {
          if (window.LikeC4Editor) return resolve(window.LikeC4Editor);
          if (tries++ >= MAX_TRIES) {
            // Loaded but never published its global (e.g. a runtime error during the module's
            // top-level execution after onload). Remove the dead element — same cleanup as onerror —
            // so the next attempt injects a FRESH script instead of taking the `existing` path above
            // and re-polling a bundle that will never publish, stalling the full budget every time.
            if (s.parentNode) s.parentNode.removeChild(s);
            return reject(new Error('LikeC4 editor bundle did not initialise'));
          }
          setTimeout(check, GLOBAL_POLL_MS);
        })();
      }
      if (existing) { waitForGlobal(); return; }
      var entry = entryUrl();
      s.type = 'module';
      s.id = 'likec4-editor-esm-entry';
      s.src = entry;
      s.onload = waitForGlobal;
      s.onerror = function () {
        // Remove the dead element so a retry injects a FRESH script rather than taking the `existing`
        // path above and polling a bundle that will never publish its global.
        if (s.parentNode) s.parentNode.removeChild(s);
        reject(new Error('Failed to load LikeC4 editor bundle: ' + entry));
      };
      document.head.appendChild(s);
    });
    // A transient bundle-load failure (a 404 blip, a network hiccup) must not permanently disable macro
    // authoring for the page session: null the cached promise on rejection so the next openMacroEditor
    // retries, instead of every subsequent open re-returning the same rejected promise.
    p.catch(function () { if (bundlePromise === p) bundlePromise = null; });
    bundlePromise = p;
    return p;
  }

  // The id of the page being edited — required by MacroUtils.insertMacro so it can render the macro
  // placeholder (POST /rest/tinymce/1/macro/placeholder needs {macro, contentId}).
  function contentId() {
    var A = window.AJS;
    if (A) {
      if (A.Editor && A.Editor.getContentId) { var c = A.Editor.getContentId(); if (c) return c; }
      if (A.Meta && A.Meta.get) { var m = A.Meta.get('page-id') || A.Meta.get('content-id'); if (m) return m; }
      if (A.params && A.params.pageId) return A.params.pageId;
    }
    // Prefer the inputs (which carry the id in .value); #content-id is often a <meta>/<div> with the id
    // in a `content` attribute and no `.value`, so fall back to getAttribute('content') for it. Reading
    // .value off a meta would otherwise silently yield undefined and POST a placeholder with no contentId.
    var el = document.querySelector('input[name="pageId"], input[name="entityId"], #content-id');
    if (!el) return undefined;
    return el.value || el.getAttribute('content') || undefined;
  }

  function insertMacro(macro) {
    var tm = window.tinymce;
    if (tm && tm.confluence && tm.confluence.MacroUtils && tm.confluence.MacroUtils.insertMacro) {
      // Confluence's MacroUtils.insertMacro expects {macro:{name,params,...}, contentId}; it then
      // renders + inserts the placeholder. Passing the bare {name,params} throws "illegal argument".
      tm.confluence.MacroUtils.insertMacro({ macro: macro, contentId: contentId() });
      return true;
    }
    return false;
  }

  function notifyError(e) {
    var msg = (e && e.message) || String(e);
    if (window.AJS && AJS.flag) {
      AJS.flag({ type: 'error', title: 'LikeC4 diagram', body: msg });
    } else if (window.console) {
      console.error('[LikeC4 editor]', e);
    }
  }

  // The Macro Browser calls this instead of rendering the default parameter form.
  function opener(macro) {
    var params = (macro && macro.params) || {};
    ensureBundle()
      .then(function (api) {
        api.openMacroEditor({
          params: params,
          createDeps: api.createDeps,
          macroName: MACRO,
          onInsert: function (updated) {
            // insertMacro returns false when tinymce is absent; but when tinymce IS present it (and the
            // placeholder POST it triggers) can also throw synchronously — the dialog has already torn
            // down by then, so an uncaught throw here would escape as a console-only error and the author
            // would see the insert silently fail. Route both paths through the visible notifyError (flag).
            try {
              if (!insertMacro(updated)) notifyError(new Error('Could not insert macro (tinymce unavailable)'));
            } catch (e) {
              notifyError(e);
            }
          },
        });
      })
      .catch(notifyError);
  }

  function register() {
    var mb = window.AJS && AJS.MacroBrowser;
    if (!mb || !mb.setMacroJsOverride) return false;
    mb.setMacroJsOverride(MACRO, { opener: opener });
    window.__likec4EditorOverrideRegistered = true;
    return true;
  }

  // Shared across init() calls: init is wired to init.rte (fires on every editor (re)initialisation)
  // AND toInit, so without a guard each not-yet-ready invocation would stack its OWN polling interval.
  var pollTimer = null;
  function init() {
    if (register()) {
      if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
      return;
    }
    if (pollTimer) return; // a poll is already running; don't stack a second interval
    // AJS.MacroBrowser may not be ready the instant our resource runs; poll briefly.
    var tries = 0;
    pollTimer = setInterval(function () {
      if (register() || tries++ >= MAX_TRIES) { clearInterval(pollTimer); pollTimer = null; }
    }, REGISTER_POLL_MS);
  }

  // Register on RTE init (fires each time the editor initialises — the recommended hook) plus
  // toInit / DOM-ready as fallbacks.
  if (window.AJS && AJS.bind) AJS.bind('init.rte', init);
  if (window.AJS && AJS.toInit) AJS.toInit(init);
  else if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
