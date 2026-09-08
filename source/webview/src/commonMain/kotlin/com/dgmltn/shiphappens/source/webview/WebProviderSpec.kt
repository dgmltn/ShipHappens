package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How a carrier's site is signed in to, for carriers whose tracking benefits from a session. */
class LoginRecipe(
    val url: String,
    /** Expression evaluating to a boolean in page context: is the session signed in? */
    val isLoggedInJs: String,
)

/**
 * Bot-defense wordings shared by every carrier: the two phrases that cannot appear in ordinary
 * shipping copy. Carriers add their own, carrier-specific wordings via
 * [WebProviderSpec.extraChallengeMarkers].
 */
val DEFAULT_CHALLENGE_MARKERS: List<String> = listOf("Access Denied", "verify you are a human")

/** The probe for a carrier with no login: never signed in. */
const val NEVER_LOGGED_IN_JS = "(function() { return false; })()"

/**
 * Everything provider-specific about scraping one carrier's website. Adding a new provider
 * means writing one of these and registering it with `webSourceModule` — no new scraping
 * machinery.
 *
 * JS fields are small, versioned-in-code scripts:
 *  - [login]: if non-null, its `isLoggedInJs` is an expression evaluating to a boolean in page
 *    context; a null [login] means the carrier's tracking never needs a session, and
 *    [isLoggedInJs] falls back to [NEVER_LOGGED_IN_JS].
 *  - [extractionJs]: a JS *function expression* `function(){...}` returning
 *    `{page: 'raw'|'goto'|'notFound'|'loginWall'|'challenge'|'empty', url?, raw?: <DomRaw>}`.
 *    Prefer `'raw'` + [parseRaw] over classifying in JS: commonTest has no JS
 *    engine, so decisions made in the blob can only be checked by scraping on a device.
 *  - [apiUrlPatterns]: JS-compatible regex source strings matched against fetch/XHR URLs.
 */
class WebProviderSpec(
    val carrier: Carrier,
    val cookieDomain: String,
    val trackingUrl: (trackingNumber: String) -> String,
    val extractionJs: String,
    val login: LoginRecipe? = null,
    val apiUrlPatterns: List<String> = emptyList(),
    /** Carrier-specific bot-defense wordings, added on top of [DEFAULT_CHALLENGE_MARKERS]. */
    val extraChallengeMarkers: List<String> = emptyList(),
    val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null },
    /**
     * Quiescence delay between onPageFinished and the extraction run. The default suits pages
     * that render server-side or hydrate quickly; a heavy SPA that client-routes after load
     * (fedex.com takes ~10s to land on its tracking view) needs more, or the extractor reads an
     * app shell and reports an empty page.
     */
    val settle: Duration = 3.seconds,
    /**
     * Turns a `page:'raw'` extraction's verbatim page text into an outcome, so status
     * vocabulary and card-selection rules live in unit-testable Kotlin instead of
     * [extractionJs] (see [DomRaw]). Returning null, or [PageOutcome.Empty], routes to
     * Unparsed.
     */
    val parseRaw: (DomRaw) -> PageOutcome? = { null },
    val sourceId: String = carrier.code,
) {
    /** The full set of bot-defense wordings this carrier's page may show. */
    val challengeMarkers: List<String> get() = DEFAULT_CHALLENGE_MARKERS + extraChallengeMarkers

    /** Expression evaluating to a boolean in page context: is the session signed in? */
    val isLoggedInJs: String get() = login?.isLoggedInJs ?: NEVER_LOGGED_IN_JS

    /** Origin rules for androidx.webkit's WebMessageListener / document-start script APIs. */
    fun allowedOriginRules(): List<String> = listOf("https://*.$cookieDomain", "https://$cookieDomain")
}
