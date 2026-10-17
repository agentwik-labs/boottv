package dev.boottv.core

/** One source of results for the universal search screen (apps, Jellyfin, live channels, ...). */
interface SearchProvider {
    /** Row title on the search screen, e.g. "Apps". */
    val name: String
    suspend fun search(query: String): List<SearchResult>
}

data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /** Landscape (16:9) artwork when true, poster (2:3) otherwise. */
    val wide: Boolean = true,
    val action: SearchAction,
)

sealed interface SearchAction {
    data class LaunchApp(val packageName: String) : SearchAction
    /** Navigate inside BootTV, e.g. "media/detail/abc" or "live/play/12". */
    data class Navigate(val route: String) : SearchAction
    data class OpenUrl(val url: String) : SearchAction
}
