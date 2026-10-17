package dev.boottv.launcher

import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult

object AppsSearchProvider : SearchProvider {
    override val name = "Apps"
    override suspend fun search(query: String): List<SearchResult> = emptyList()
}
