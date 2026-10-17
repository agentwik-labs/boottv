package dev.boottv.livetv

import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult

object LiveTvSearchProvider : SearchProvider {
    override val name = "Live TV"
    override suspend fun search(query: String): List<SearchResult> = emptyList()
}
