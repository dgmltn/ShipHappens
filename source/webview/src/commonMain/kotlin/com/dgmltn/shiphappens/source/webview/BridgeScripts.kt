package com.dgmltn.shiphappens.source.webview

/**
 * Builds the two JS programs injected into provider pages. Pure string assembly — kept in
 * commonMain so tests lock the JS surface without a browser. The bridge object (name
 * [BRIDGE_NAME]) is injected from Kotlin and exposes `postMessage(string)`.
 */
object BridgeScripts {
    const val BRIDGE_NAME = "shipBridge"

    private fun jsString(s: String): String = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "") + "\""

    /**
     * Installed at document start: taps window.fetch and XMLHttpRequest; response bodies whose
     * request URL matches any pattern are posted as {kind:'api', url, body} bridge payloads.
     */
    fun captureScript(apiUrlPatterns: List<String>): String {
        val patternArray = apiUrlPatterns.joinToString(",") { "new RegExp(${jsString(it)})" }
        return """
(function() {
  if (window.__shipCaptureInstalled) return;
  window.__shipCaptureInstalled = true;
  var patterns = [$patternArray];
  function matches(url) { try { return patterns.some(function(p) { return p.test(url); }); } catch (e) { return false; } }
  function post(kind, url, body) {
    try { $BRIDGE_NAME.postMessage(JSON.stringify({kind: kind, url: url, body: body})); } catch (e) {}
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var p = origFetch.apply(this, arguments);
      if (matches(url)) {
        p.then(function(resp) { try { resp.clone().text().then(function(t) { post('api', url, t); }); } catch (e) {} });
      }
      return p;
    };
  }
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) { this.__shipUrl = '' + url; return origOpen.apply(this, arguments); };
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function() {
    var xhr = this;
    if (matches(xhr.__shipUrl)) {
      xhr.addEventListener('load', function() { try { post('api', xhr.__shipUrl, xhr.responseText); } catch (e) {} });
    }
    return origSend.apply(this, arguments);
  };
})();
""".trimIndent()
    }

    /**
     * Run after page quiescence: checks challenge markers against visible text, then runs the
     * provider's extractor and posts its result as a {kind:'dom', body} payload. Always posts
     * exactly one payload (page:'empty' on any error) so callers can treat 'dom' as end-of-scrape.
     *
     * The extractor receives the [page] helper object, then a `finish(result)` callback: a plain
     * `function(page){...}` finishes synchronously by returning a value — every extractor that
     * ignores its arguments keeps working untouched. `function(page, finish){...}` may return
     * undefined and defer the post to a later `finish(...)` call, for choreography that must wait
     * on the page (click a control, let the SPA re-render, then read — see FedexWebSpec). A
     * backstop timer finishes `{page:'empty', why:'asyncTimeout'}` if the deferred call never
     * comes, well inside the scraper's own 30s timeout, and the `finished` guard keeps it to one
     * post.
     */
    fun extractionRunner(spec: WebProviderSpec): String {
        val markerArray = spec.challengeMarkers.joinToString(",") { jsString(it.lowercase()) }
        return """
(function() {
  function post(body) {
    try { $BRIDGE_NAME.postMessage(JSON.stringify({kind: 'dom', body: JSON.stringify(body)})); } catch (e) {}
  }
  var finished = false;
  var backstop;
  function finish(r) {
    if (finished) return;
    finished = true;
    if (backstop) clearTimeout(backstop);
    post(r || {page: 'empty'});
  }
  try {
    var bodyText = (document.body && document.body.innerText) || '';
    var page = {
      bodyText: bodyText,
      pageText: bodyText.replace(/\s+/g, ' ').slice(0, 400),
      todayIso: (function() {
        var d = new Date();
        return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
      })(),
      clean: function(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; },
      text: function() {
        for (var i = 0; i < arguments.length; i++) {
          var el = null;
          try { el = document.querySelector(arguments[i]); } catch (e) {}
          var t = page.clean(el);
          if (t) return t;
        }
        return null;
      },
      count: function(sel) { try { return document.querySelectorAll(sel).length; } catch (e) { return -1; } },
      probe: function() {
        var out = {};
        for (var i = 0; i < arguments.length; i++) out[arguments[i]] = page.count(arguments[i]);
        return out;
      },
      raw: function(kind, fields) {
        var raw = {kind: kind, pageText: page.pageText, todayIso: page.todayIso};
        for (var k in fields) if (fields[k] !== undefined && fields[k] !== null) raw[k] = fields[k];
        return {page: 'raw', raw: raw};
      }
    };
    var text = bodyText.toLowerCase();
    var markers = [$markerArray];
    for (var i = 0; i < markers.length; i++) {
      if (text.indexOf(markers[i]) !== -1) { finish({page: 'challenge'}); return; }
    }
    backstop = setTimeout(function() { finish({page: 'empty', why: 'asyncTimeout'}); }, $ASYNC_BACKSTOP_MS);
    var extractor = (${spec.extractionJs});
    var r = extractor(page, finish);
    if (r !== undefined) finish(r);
  } catch (e) {
    // A thrown extractor and a genuinely empty page both route to Unparsed; without 'why' the
    // trace log can't tell them apart, and a selector hunt would start from the wrong premise.
    finish({page: 'empty', why: 'extractorThrew', error: '' + (e && e.message ? e.message : e)});
  }
})();
""".trimIndent()
    }

    /** How long an async extractor may defer before the runner reports empty. */
    private const val ASYNC_BACKSTOP_MS = 8_000

    /**
     * The common "is this session signed in?" probe: any of [selectors] present (a logout link,
     * an avatar), or [textPattern] (a case-insensitive JS regex source) found in the page text.
     * Carriers whose chrome needs more than that (Amazon's account menu) write their own.
     */
    fun loggedInProbe(selectors: List<String>, textPattern: String): String = """
(function() {
  try {
    if (document.querySelector(${jsString(selectors.joinToString(", "))})) return true;
    return new RegExp(${jsString(textPattern)}, 'i').test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()
}
