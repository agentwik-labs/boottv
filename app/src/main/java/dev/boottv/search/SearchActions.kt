package dev.boottv.search

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.navigation.NavController
import dev.boottv.browser.browserRoute
import dev.boottv.browser.webSearchUrl
import dev.boottv.core.SearchAction

/** YouTube clients that accept search intents, in order of preference. */
private val YOUTUBE_PACKAGES = listOf(
    "com.google.android.youtube.tv",
    "com.teamsmart.videomanager.tv",
    "org.smartteam.smarttube.stable",
    "org.smartteam.smarttube.beta",
    "com.liskovsoft.smarttubetv.beta",
    "com.liskovsoft.smarttubetv",
    "com.google.android.youtube.googletv",
    "com.google.android.youtube",
)

internal fun launchApp(context: Context, packageName: String): Boolean {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
        ?: pm.getLaunchIntentForPackage(packageName)
        ?: return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

internal fun performSearchAction(context: Context, nav: NavController, action: SearchAction) {
    when (action) {
        is SearchAction.LaunchApp -> if (!launchApp(context, action.packageName)) {
            Toast.makeText(context, "Couldn't open app", Toast.LENGTH_SHORT).show()
        }
        is SearchAction.Navigate -> runCatching { nav.navigate(action.route) }.onFailure {
            Toast.makeText(context, "Couldn't open ${action.route}", Toast.LENGTH_SHORT).show()
        }
        is SearchAction.OpenUrl -> nav.navigate(browserRoute(action.url))
    }
}

private fun tryStart(context: Context, intent: Intent): Boolean {
    if (intent.resolveActivity(context.packageManager) == null) return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

private fun isInstalled(context: Context, pkg: String): Boolean =
    try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: Exception) {
        false
    }

/** Searches YouTube in the best installed client; falls back to the web results page in the browser. */
internal fun searchYouTube(context: Context, nav: NavController, query: String) {
    val webUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query.trim())
    for (pkg in YOUTUBE_PACKAGES) {
        if (!isInstalled(context, pkg)) continue
        val search = Intent(Intent.ACTION_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra("query", query)
        if (tryStart(context, search)) return
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).setPackage(pkg)
        if (tryStart(context, view)) return
    }
    nav.navigate(browserRoute(webUrl))
}

internal fun searchWeb(nav: NavController, query: String) {
    nav.navigate(browserRoute(webSearchUrl(query)))
}
