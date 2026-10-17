package dev.boottv.livetv

import dev.boottv.core.SearchAction
import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LiveTvSearchProvider : SearchProvider {
    override val name = "Live TV"
    private const val MAX_RESULTS = 40

    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return try {
            val state = withTimeoutOrNull(5_000) { LiveTvRepository.awaitLoaded() } ?: return emptyList()
            withContext(Dispatchers.Default) { searchIn(state, q) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun searchIn(state: LiveState, q: String): List<SearchResult> {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val results = ArrayList<SearchResult>()
        val seen = HashSet<String>()

        // Channel name matches first (prefix matches ranked above contains).
        state.channels
            .filter { it.name.lowercase().contains(q) || it.number == q }
            .sortedBy { if (it.name.lowercase().startsWith(q) || it.number == q) 0 else 1 }
            .forEach { ch ->
                if (results.size >= MAX_RESULTS || !seen.add(ch.id)) return@forEach
                val cur = state.nowAndNext(ch.id, now).first
                results.add(
                    SearchResult(
                        id = "live-ch-${ch.id}",
                        title = ch.name,
                        subtitle = listOfNotNull(ch.number?.let { "Ch $it" }, cur?.let { "Now: ${it.title}" }).joinToString(" · ").ifEmpty { "Live channel" },
                        imageUrl = ch.logo,
                        wide = true,
                        action = SearchAction.Navigate("live/play/${ch.id}"),
                    )
                )
            }

        // Current / upcoming programme title matches.
        val horizon = now + 24 * 60 * 60_000L
        for (ch in state.channels) {
            if (results.size >= MAX_RESULTS) break
            val p = state.programmesFor(ch.id).firstOrNull {
                it.stop > now && it.start < horizon && it.title.lowercase().contains(q)
            } ?: continue
            if (!seen.add(ch.id + "|p")) continue
            val live = p.start <= now
            results.add(
                SearchResult(
                    id = "live-p-${ch.id}-${p.start}",
                    title = p.title,
                    subtitle = (if (live) "On now" else "At ${fmt.format(Date(p.start))}") + " · ${ch.name}",
                    imageUrl = ch.logo,
                    wide = true,
                    action = SearchAction.Navigate("live/play/${ch.id}"),
                )
            )
        }
        return results
    }
}
