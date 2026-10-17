package dev.boottv.jellyfin

import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult

object JellyfinSearchProvider : SearchProvider {
    override val name = "My Media"
    override suspend fun search(query: String): List<SearchResult> = emptyList()
}
