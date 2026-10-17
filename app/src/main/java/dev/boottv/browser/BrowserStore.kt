package dev.boottv.browser

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Bookmark(val title: String, val url: String)

@Serializable
data class HistoryEntry(val title: String, val url: String, val time: Long)

data class BrowserSettings(
    val adBlock: Boolean = true,
    val desktopMode: Boolean = false,
    val cursorMode: Boolean = true,
)

private val Context.browserDataStore: DataStore<Preferences> by preferencesDataStore(name = "browser_prefs")

/** Persists bookmarks, history and browser toggles as JSON inside a Preferences DataStore. */
class BrowserStore(context: Context) {
    private val store = context.applicationContext.browserDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val bookmarkSer = ListSerializer(Bookmark.serializer())
    private val historySer = ListSerializer(HistoryEntry.serializer())

    val bookmarks: Flow<List<Bookmark>> = store.data.map { p ->
        p[KEY_BOOKMARKS]?.let { runCatching { json.decodeFromString(bookmarkSer, it) }.getOrNull() }
            ?: DEFAULT_BOOKMARKS
    }

    val history: Flow<List<HistoryEntry>> = store.data.map { p ->
        p[KEY_HISTORY]?.let { runCatching { json.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
    }

    val settings: Flow<BrowserSettings> = store.data.map { p ->
        BrowserSettings(
            adBlock = p[KEY_ADBLOCK] ?: true,
            desktopMode = p[KEY_DESKTOP] ?: false,
            cursorMode = p[KEY_CURSOR] ?: true,
        )
    }

    private fun Preferences.currentBookmarks(): List<Bookmark> =
        this[KEY_BOOKMARKS]?.let { runCatching { json.decodeFromString(bookmarkSer, it) }.getOrNull() }
            ?: DEFAULT_BOOKMARKS

    suspend fun addBookmark(b: Bookmark) = store.edit { p ->
        val list = p.currentBookmarks().filterNot { it.url == b.url } + b
        p[KEY_BOOKMARKS] = json.encodeToString(bookmarkSer, list)
    }

    suspend fun removeBookmark(url: String) = store.edit { p ->
        p[KEY_BOOKMARKS] = json.encodeToString(bookmarkSer, p.currentBookmarks().filterNot { it.url == url })
    }

    suspend fun addHistory(title: String, url: String) = store.edit { p ->
        val current = p[KEY_HISTORY]?.let { runCatching { json.decodeFromString(historySer, it) }.getOrNull() }
            ?: emptyList()
        val entry = HistoryEntry(title.ifBlank { url }, url, System.currentTimeMillis())
        val list = (listOf(entry) + current.filterNot { it.url == url }).take(MAX_HISTORY)
        p[KEY_HISTORY] = json.encodeToString(historySer, list)
    }

    suspend fun clearHistory() = store.edit { it.remove(KEY_HISTORY) }

    suspend fun setAdBlock(v: Boolean) = store.edit { it[KEY_ADBLOCK] = v }
    suspend fun setDesktopMode(v: Boolean) = store.edit { it[KEY_DESKTOP] = v }
    suspend fun setCursorMode(v: Boolean) = store.edit { it[KEY_CURSOR] = v }

    companion object {
        private const val MAX_HISTORY = 200
        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks")
        private val KEY_HISTORY = stringPreferencesKey("history")
        private val KEY_ADBLOCK = booleanPreferencesKey("adblock")
        private val KEY_DESKTOP = booleanPreferencesKey("desktop_mode")
        private val KEY_CURSOR = booleanPreferencesKey("cursor_mode")

        val DEFAULT_BOOKMARKS = listOf(
            Bookmark("YouTube TV", "https://www.youtube.com/tv"),
            Bookmark("Tubi", "https://tubitv.com"),
            Bookmark("Pluto TV", "https://pluto.tv"),
            Bookmark("Crunchyroll", "https://www.crunchyroll.com"),
            Bookmark("DuckDuckGo", "https://duckduckgo.com"),
        )
    }
}
