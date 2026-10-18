package dev.boottv.browser

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A website pinned to Home as an app tile; opens full screen in the built-in browser. */
@Serializable
data class WebApp(val name: String, val url: String) {
    val host: String get() = hostOf(url)
}

private val Context.webAppsDataStore: DataStore<Preferences> by preferencesDataStore(name = "web_apps")

/** Persists the user's web apps (in Home order) as JSON inside a Preferences DataStore. */
object WebApps {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(WebApp.serializer())
    private val KEY = stringPreferencesKey("apps")

    private fun Preferences.current(): List<WebApp> =
        this[KEY]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    fun flow(context: Context): Flow<List<WebApp>> = context.applicationContext.webAppsDataStore.data.map { it.current() }

    /** Adds [app], or renames it if that address is already pinned. */
    suspend fun add(context: Context, app: WebApp) {
        context.applicationContext.webAppsDataStore.edit { p ->
            val list = p.current()
            val i = list.indexOfFirst { it.url == app.url }
            p[KEY] = json.encodeToString(ser, if (i >= 0) list.toMutableList().also { it[i] = app } else list + app)
        }
    }

    suspend fun remove(context: Context, url: String) {
        context.applicationContext.webAppsDataStore.edit { p ->
            p[KEY] = json.encodeToString(ser, p.current().filterNot { it.url == url })
        }
    }

    /** Turns what the user typed into an address ("example.com" -> "https://example.com"), or null if it isn't one. */
    fun normalize(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        val host = hostOf(withScheme)
        return if (host.contains('.') || host == "localhost") withScheme else null
    }
}
