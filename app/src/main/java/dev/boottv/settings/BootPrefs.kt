package dev.boottv.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.bootPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "boottv_settings")

/** Launcher + settings preferences shared by the home screen, settings and theme. */
data class BootSettings(
    /** Package names in the user's chosen home order (packages not listed go at the end). */
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val locked: Set<String> = emptySet(),
    val favorites: List<String> = emptyList(),
    val gridColumns: Int = 5,
    val showFavorites: Boolean = true,
    val accentId: String = "purple",
    /** SHA-256 of the parental PIN, or null when no PIN is set. */
    val pinHash: String? = null,
    val pinForSettings: Boolean = false,
) {
    val hasPin: Boolean get() = pinHash != null
}

object BootPrefs {
    private val ORDER = stringPreferencesKey("app_order")
    private val HIDDEN = stringSetPreferencesKey("hidden_apps")
    private val LOCKED = stringSetPreferencesKey("locked_apps")
    private val FAVORITES = stringPreferencesKey("favorite_apps")
    private val COLUMNS = intPreferencesKey("grid_columns")
    private val SHOW_FAVORITES = booleanPreferencesKey("show_favorites")
    private val ACCENT = stringPreferencesKey("accent")
    private val PIN = stringPreferencesKey("pin_hash")
    private val PIN_SETTINGS = booleanPreferencesKey("pin_for_settings")

    private fun store(context: Context) = context.applicationContext.bootPrefsStore

    private fun Preferences.toSettings() = BootSettings(
        order = this[ORDER].splitList(),
        hidden = this[HIDDEN] ?: emptySet(),
        locked = this[LOCKED] ?: emptySet(),
        favorites = this[FAVORITES].splitList(),
        gridColumns = (this[COLUMNS] ?: 5).coerceIn(4, 5),
        showFavorites = this[SHOW_FAVORITES] ?: true,
        accentId = this[ACCENT] ?: "purple",
        pinHash = this[PIN],
        pinForSettings = this[PIN_SETTINGS] ?: false,
    )

    private fun String?.splitList(): List<String> =
        this?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun flow(context: Context): Flow<BootSettings> = store(context).data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { it.toSettings() }
        .distinctUntilChanged()

    fun accentIdFlow(context: Context): Flow<String> = flow(context).map { it.accentId }.distinctUntilChanged()

    suspend fun current(context: Context): BootSettings = flow(context).first()

    private suspend fun edit(context: Context, block: (MutablePreferences) -> Unit) {
        store(context).edit { block(it) }
    }

    suspend fun setOrder(context: Context, order: List<String>) =
        edit(context) { it[ORDER] = order.joinToString("\n") }

    suspend fun setHidden(context: Context, pkg: String, hidden: Boolean) = edit(context) {
        val cur = it[HIDDEN] ?: emptySet()
        it[HIDDEN] = if (hidden) cur + pkg else cur - pkg
    }

    suspend fun setLocked(context: Context, pkg: String, locked: Boolean) = edit(context) {
        val cur = it[LOCKED] ?: emptySet()
        it[LOCKED] = if (locked) cur + pkg else cur - pkg
    }

    suspend fun setFavorite(context: Context, pkg: String, favorite: Boolean) = edit(context) {
        val cur = it[FAVORITES].splitList()
        val next = if (favorite) (cur - pkg) + pkg else cur - pkg
        it[FAVORITES] = next.joinToString("\n")
    }

    suspend fun setGridColumns(context: Context, columns: Int) =
        edit(context) { it[COLUMNS] = columns.coerceIn(4, 5) }

    suspend fun setShowFavorites(context: Context, show: Boolean) =
        edit(context) { it[SHOW_FAVORITES] = show }

    suspend fun setAccent(context: Context, id: String) = edit(context) { it[ACCENT] = id }

    /** Sets a new PIN, or clears it (and all locks that depend on it) when [pin] is null. */
    suspend fun setPin(context: Context, pin: String?) = edit(context) {
        if (pin == null) {
            it.remove(PIN)
            it[PIN_SETTINGS] = false
            it[LOCKED] = emptySet()
        } else {
            it[PIN] = hashPin(pin)
        }
    }

    suspend fun setPinForSettings(context: Context, enabled: Boolean) =
        edit(context) { it[PIN_SETTINGS] = enabled }

    fun checkPin(settings: BootSettings, pin: String): Boolean =
        settings.pinHash != null && settings.pinHash == hashPin(pin)

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("boottv:$pin".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
