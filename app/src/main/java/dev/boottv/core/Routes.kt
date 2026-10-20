package dev.boottv.core

/**
 * Top-level destinations shown in the side menu. Each feature owns everything
 * under its prefix (e.g. "media/detail/{id}") inside its own nav graph.
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIVE = "live"
    const val MEDIA = "media"
    const val FREE = "free"
    const val TOONS = "toons"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
}
