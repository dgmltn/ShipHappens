package com.shiphappens.source.webview

import android.webkit.CookieManager

/**
 * CookieManager is app-global and persists to disk on its own schedule; [flush] forces
 * persistence (call after login). Android has no per-domain clear API, so [clearForDomain]
 * expires every cookie readable for the domain by rewriting it with an epoch expiry.
 */
class AndroidWebCookieJar : WebCookieJar {
    override fun flush() = CookieManager.getInstance().flush()

    override fun clearForDomain(domain: String) {
        val manager = CookieManager.getInstance()
        listOf("https://$domain", "https://www.$domain").forEach { url ->
            manager.getCookie(url)?.split(";")?.forEach { cookie ->
                val name = cookie.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(url, "$name=; Domain=$domain; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
            }
        }
        manager.flush()
    }
}
