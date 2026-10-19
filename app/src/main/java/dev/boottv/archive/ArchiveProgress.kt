package dev.boottv.archive

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where you stopped in a Free Movies title, for Resume and the Continue watching row. */
@Serializable
data class ArchiveResume(
    val id: String,
    val title: String,
    val year: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

object ArchiveProgress {
    private const val PREFS = "free_movies_progress"
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var app: Context

    /** Keyed by archive id. */
    val entries = mutableStateMapOf<String, ArchiveResume>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.values.forEach { v ->
            runCatching { json.decodeFromString<ArchiveResume>(v as String) }.getOrNull()?.let { entries[it.id] = it }
        }
    }

    /** Most recent first. */
    fun continueWatching(): List<ArchiveResume> = entries.values.sortedByDescending { it.updatedAt }

    /** Saves the position; near the start or the end counts as not started / finished and clears it. */
    fun save(item: ArchiveItem, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs < 30_000 || positionMs > durationMs - 90_000) { clear(item.id); return }
        val r = ArchiveResume(item.id, item.title, item.year, positionMs, durationMs, System.currentTimeMillis())
        entries[item.id] = r
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(item.id, json.encodeToString(r)).apply()
    }

    fun clear(id: String) {
        entries.remove(id)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }
}
