package dev.boottv.jellyfin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.boottv.BootApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.jellyfinDataStore: DataStore<Preferences> by preferencesDataStore(name = "jellyfin")

/** A signed-in Jellyfin user on one server. */
data class JellyfinSession(
    val server: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val token: String,
    val deviceId: String,
)

/** Persists server URL, token and user in DataStore. */
object JellyfinStore {
    private val SERVER = stringPreferencesKey("server")
    private val SERVER_NAME = stringPreferencesKey("server_name")
    private val USER_ID = stringPreferencesKey("user_id")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val TOKEN = stringPreferencesKey("token")
    private val DEVICE_ID = stringPreferencesKey("device_id")

    private val ds get() = BootApp.instance.jellyfinDataStore

    val sessionFlow: Flow<JellyfinSession?>
        get() = ds.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { p ->
                val server = p[SERVER]
                val token = p[TOKEN]
                val user = p[USER_ID]
                val device = p[DEVICE_ID]
                if (server.isNullOrBlank() || token.isNullOrBlank() || user.isNullOrBlank() || device.isNullOrBlank()) null
                else JellyfinSession(
                    server = server,
                    serverName = p[SERVER_NAME] ?: server,
                    userId = user,
                    userName = p[USER_NAME] ?: "",
                    token = token,
                    deviceId = device,
                )
            }

    /** Last server URL entered, even if not signed in. */
    val lastServerFlow: Flow<String?>
        get() = ds.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.map { it[SERVER] }

    suspend fun session(): JellyfinSession? = runCatching { sessionFlow.first() }.getOrNull()

    suspend fun deviceId(): String {
        val existing = runCatching { ds.data.first()[DEVICE_ID] }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        ds.edit { if (it[DEVICE_ID].isNullOrBlank()) it[DEVICE_ID] = id }
        return ds.data.first()[DEVICE_ID] ?: id
    }

    suspend fun saveServer(url: String, name: String?) {
        ds.edit {
            it[SERVER] = url
            if (name != null) it[SERVER_NAME] = name
        }
    }

    suspend fun saveLogin(server: String, serverName: String, userId: String, userName: String, token: String) {
        ds.edit {
            it[SERVER] = server
            it[SERVER_NAME] = serverName
            it[USER_ID] = userId
            it[USER_NAME] = userName
            it[TOKEN] = token
        }
    }

    suspend fun signOut() {
        ds.edit {
            it.remove(TOKEN)
            it.remove(USER_ID)
            it.remove(USER_NAME)
        }
    }
}
