package com.reasonmachines.android

import android.net.Uri

object Hosts {
    private val reasonDomains = listOf("reasonmachines.com", "reasonmachines.ai", "reason-preview.com")

    /** Identity providers that must stay inside the app so the sign-in cookie lands in the WebView. */
    private val authDomains = listOf(
        "accounts.google.com", "accounts.youtube.com", "google.com",
        "github.com", "gitlab.com",
        "login.microsoftonline.com", "login.live.com",
        "appleid.apple.com",
        "clerk.com", "clerk.accounts.dev", "accounts.dev",
        "auth0.com", "workos.com", "authkit.app", "stytch.com", "slack.com",
        "stripe.com", "checkout.stripe.com",
    )

    val TRUSTED_ORIGIN_RULES: Set<String> = setOf(
        "https://reasonmachines.com", "https://*.reasonmachines.com",
        "https://reasonmachines.ai", "https://*.reasonmachines.ai",
    )

    private fun matches(host: String?, domains: List<String>): Boolean {
        val h = host?.lowercase() ?: return false
        return domains.any { h == it || h.endsWith(".$it") }
    }

    fun isReason(uri: Uri?): Boolean = uri?.scheme == "https" && matches(uri.host, reasonDomains)

    fun isAuth(uri: Uri?): Boolean = uri?.scheme == "https" && matches(uri.host, authDomains)
}
