package com.dgmltn.shiphappens.ui.navigation

/** Where a tapped notification wants the app to land. */
sealed interface DeepLink {
    data class Parcel(val parcelId: String) : DeepLink
    data class SignIn(val sourceId: String) : DeepLink
}

private const val SCHEME = "shiphappens://"

/**
 * Parses `shiphappens://parcel/{id}` and `shiphappens://signin/{sourceId}`.
 *
 * Hand-rolled rather than android.net.Uri because this lives in commonMain — the shapes are
 * fixed and produced by our own notifier, so there is nothing to be liberal about.
 */
fun parseDeepLink(uri: String?): DeepLink? {
    val rest = uri?.removePrefix(SCHEME)?.takeIf { it != uri } ?: return null
    val host = rest.substringBefore('/')
    val arg = rest.substringAfter('/', "").takeIf { it.isNotBlank() } ?: return null
    return when (host) {
        "parcel" -> DeepLink.Parcel(arg)
        "signin" -> DeepLink.SignIn(arg)
        else -> null
    }
}
