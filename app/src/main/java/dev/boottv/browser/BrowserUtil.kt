package dev.boottv.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Patterns
import dev.boottv.core.Routes

/** Route that opens the browser, optionally at [url]. Use with nav.navigate(...). */
fun browserRoute(url: String? = null): String =
    if (url.isNullOrBlank()) Routes.BROWSER else "${Routes.BROWSER}?url=${Uri.encode(url)}"

const val SEARCH_ENGINE_URL = "https://duckduckgo.com/?q="
const val BROWSER_HOME_URL = "https://duckduckgo.com"

fun webSearchUrl(query: String): String = SEARCH_ENGINE_URL + Uri.encode(query.trim())

/** Turns whatever the user typed into a URL: real addresses are loaded, anything else is searched. */
fun urlFromInput(input: String): String {
    val text = input.trim()
    if (text.isEmpty()) return BROWSER_HOME_URL
    val lower = text.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) return text
    if (text.contains(' ')) return webSearchUrl(text)
    val hostPart = text.substringBefore('/').substringBefore('?')
    val looksLikeHost = hostPart.equals("localhost", true) ||
        hostPart.substringBefore(':').equals("localhost", true) ||
        Patterns.IP_ADDRESS.matcher(hostPart.substringBefore(':')).matches() ||
        (hostPart.contains('.') && !hostPart.startsWith('.') && !hostPart.endsWith('.') &&
            hostPart.substringAfterLast('.').substringBefore(':').let { tld -> tld.length >= 2 && tld.all { it.isLetter() } })
    return if (looksLikeHost) {
        val isLocal = hostPart.startsWith("localhost") || Patterns.IP_ADDRESS.matcher(hostPart.substringBefore(':')).matches()
        (if (isLocal) "http://" else "https://") + text
    } else {
        webSearchUrl(text)
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun isYouTubeTv(url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return (host == "youtube.com" || host.endsWith(".youtube.com")) && (uri.path ?: "").startsWith("/tv")
}
