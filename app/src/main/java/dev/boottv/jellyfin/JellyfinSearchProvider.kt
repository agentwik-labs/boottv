package dev.boottv.jellyfin

import dev.boottv.core.SearchAction
import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult
import kotlinx.coroutines.CancellationException

object JellyfinSearchProvider : SearchProvider {
    override val name = "My Media"

    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val client = JellyfinClient.current() ?: return emptyList()
            client.search(q, 20).map { item ->
                SearchResult(
                    id = "jellyfin:${item.id}",
                    title = item.cardTitle.ifBlank { item.name.orEmpty() },
                    subtitle = when {
                        item.isEpisode -> item.cardSubtitle
                        item.isSeries -> listOfNotNull("Series", item.productionYear?.toString()).joinToString(" · ")
                        else -> listOfNotNull("Movie", item.productionYear?.toString()).joinToString(" · ")
                    },
                    imageUrl = client.posterUrl(item),
                    wide = false,
                    action = SearchAction.Navigate(detailRoute(item.id)),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
