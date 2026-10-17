package dev.boottv.launcher

import dev.boottv.BootApp
import dev.boottv.core.SearchAction
import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult
import dev.boottv.settings.BootPrefs

/** Searches installed app labels (case-insensitive "contains"); prefix matches rank first. */
object AppsSearchProvider : SearchProvider {
    override val name = "Apps"

    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val context = BootApp.instance
        val prefs = BootPrefs.current(context)
        // PIN-locked apps are left out so search can't be used to bypass parental controls.
        val excluded = if (prefs.hasPin) prefs.locked else emptySet()
        val apps = AppRepository.loadApps(context)
        return apps
            .filter { it.packageName !in excluded && it.label.contains(q, ignoreCase = true) }
            .sortedWith(
                compareBy<AppEntry> { !it.label.startsWith(q, ignoreCase = true) }
                    .thenBy { it.label.lowercase() },
            )
            .map { app ->
                SearchResult(
                    id = "app:${app.packageName}",
                    title = app.label,
                    subtitle = "App",
                    imageUrl = null,
                    wide = true,
                    action = SearchAction.LaunchApp(app.packageName),
                )
            }
    }
}
