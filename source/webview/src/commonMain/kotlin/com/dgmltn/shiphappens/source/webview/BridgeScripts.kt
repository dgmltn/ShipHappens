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
     */
    fun extractionRunner(spec: WebProviderSpec): String {
        val markerArray = spec.challengeMarkers.joinToString(",") { jsString(it.lowercase()) }
        return """
(function() {
  function post(body) {
    try { $BRIDGE_NAME.postMessage(JSON.stringify({kind: 'dom', body: JSON.stringify(body)})); } catch (e) {}
  }
  try {
    var text = ((document.body && document.body.innerText) || '').toLowerCase();
    var markers = [$markerArray];
    for (var i = 0; i < markers.length; i++) {
      if (text.indexOf(markers[i]) !== -1) { post({page: 'challenge'}); return; }
    }
    var extractor = (${spec.extractionJs});
    post(extractor() || {page: 'empty'});
  } catch (e) {
    // A thrown extractor and a genuinely empty page both route to Unparsed; without 'why' the
    // trace log can't tell them apart, and a selector hunt would start from the wrong premise.
    post({page: 'empty', why: 'extractorThrew', error: '' + (e && e.message ? e.message : e)});
  }
})();
""".trimIndent()
    }
}
