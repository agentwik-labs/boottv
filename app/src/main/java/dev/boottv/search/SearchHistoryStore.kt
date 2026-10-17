package dev.boottv.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.searchDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_prefs")

/** Recent search queries, newest first. */
class SearchHistoryStore(context: Context) {
    private val store = context.applicationContext.searchDataStore
    private val ser = ListSerializer(String.serializer())

    private fun decode(raw: String?): List<String> =
        raw?.let { runCatching { Json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    val recent: Flow<List<String>> = store.data.map { decode(it[KEY]) }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.edit { p ->
            val list = (listOf(q) + decode(p[KEY]).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
            p[KEY] = Json.encodeToString(ser, list)
        }
    }

    suspend fun remove(query: String) = store.edit { p ->
        p[KEY] = Json.encodeToString(ser, decode(p[KEY]).filterNot { it == query })
    }

    suspend fun clear() = store.edit { it.remove(KEY) }

    private companion object {
        const val MAX = 20
        val KEY = stringPreferencesKey("recent_searches")
    }
}
