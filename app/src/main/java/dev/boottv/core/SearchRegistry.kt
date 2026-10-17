package dev.boottv.core

import dev.boottv.jellyfin.JellyfinSearchProvider
import dev.boottv.launcher.AppsSearchProvider
import dev.boottv.livetv.LiveTvSearchProvider

object SearchRegistry {
    val providers: List<SearchProvider> by lazy {
        listOf(AppsSearchProvider, JellyfinSearchProvider, LiveTvSearchProvider)
    }
}
