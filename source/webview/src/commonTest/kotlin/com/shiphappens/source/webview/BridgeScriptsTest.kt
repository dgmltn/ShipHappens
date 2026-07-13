package com.shiphappens.source.webview

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
}
