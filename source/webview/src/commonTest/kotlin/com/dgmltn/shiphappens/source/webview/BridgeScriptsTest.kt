package com.dgmltn.shiphappens.source.webview

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeScriptsTest {
    @Test fun capture_script_embeds_patterns_and_bridge() {
        val js = BridgeScripts.captureScript(listOf(""".*ups\.com/track/api/Track/GetStatus.*"""))
        assertContains(js, BridgeScripts.BRIDGE_NAME)
        assertContains(js, """ups\\.com/track/api/Track/GetStatus""")  // regex source JSON-escaped for JS string literal
        assertContains(js, "window.fetch")
        assertContains(js, "XMLHttpRequest")
        assertTrue(js.contains("'api'") || js.contains("\"api\""))
    }

    @Test fun capture_script_is_idempotent_guarded() {
        assertContains(BridgeScripts.captureScript(emptyList()), "__shipCaptureInstalled")
    }

    @Test fun extraction_runner_embeds_markers_and_extractor() {
        val js = BridgeScripts.extractionRunner(testSpec())
        assertContains(js, "verify you are a human")            // challenge marker
        assertContains(js, "function(){return {page:'empty'}}") // spec extractionJs verbatim
        assertContains(js, BridgeScripts.BRIDGE_NAME)
        assertContains(js, "challenge")
        assertFalse(js.contains("\${"))                          // no unresolved Kotlin templates
    }

    @Test fun extraction_runner_passes_page_helpers_then_finish() {
        val js = BridgeScripts.extractionRunner(testSpec())
        assertContains(js, "var page = {")
        assertContains(js, "extractor(page, finish)")
        assertContains(js, "if (finished) return")   // post-once guard
        assertContains(js, "asyncTimeout")           // backstop outcome is diagnosable in traces
        assertContains(js, "!== undefined")          // sync return path preserved
    }

    @Test fun page_helpers_expose_the_documented_surface() {
        val js = BridgeScripts.extractionRunner(testSpec())
        for (member in listOf("bodyText:", "pageText:", "todayIso:", "clean: function", "text: function", "count: function", "probe: function", "raw: function")) {
            assertContains(js, member)
        }
        assertContains(js, "slice(0, 400)")                 // pageText length is the shared contract
        assertContains(js, "raw = {kind: kind, pageText: page.pageText, todayIso: page.todayIso}")
    }

    @Test fun logged_in_probe_embeds_selectors_and_text_pattern() {
        val js = BridgeScripts.loggedInProbe(listOf("a[href*=\"logout\"]", "[class*=\"sign-out\"]"), "sign out|welcome,")
        assertTrue(js.trimStart().startsWith("(function()"))
        assertTrue(js.contains("""document.querySelector("a[href*=\"logout\"], [class*=\"sign-out\"]")"""))
        assertTrue(js.contains("""new RegExp("sign out|welcome,", 'i')"""))
        assertTrue(js.contains("catch (e) { return false; }"))
    }
}
