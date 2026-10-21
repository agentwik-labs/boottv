package dev.boottv.localmedia

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Your own videos in a bucket (Cloudflare R2, Amazon S3, Backblaze B2, any web host): BootTV reads one small index
 * file, boottv.json, written by tools/cloud-index.py from a folder that follows the same naming rules as Send to TV
 * (Show/Season 1/S01E03 - Title.mp4, poster.jpg), and streams the videos over HTTPS. No computer has to stay on.
 */

@Serializable
private data class CloudManifest(val version: Int = 1, val name: String = "Cloud library", val base: String? = null, val videos: List<CloudEntry> = emptyList())

@Serializable
private data class CloudEntry(
    val path: String,
    val duration: Long = 0,
    val size: Long = 0,
    val added: Long = 0,
    val poster: String? = null,
    val title: String? = null,
)

object CloudLibrary {
    private const val TAG = "CloudLibrary"
    private const val PREFS = "cloud_library"
    private const val KEY_URL = "url"
    private const val STALE_MS = 60 * 60 * 1000L

    var url by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("Cloud library")
        private set
    var videos by mutableStateOf<List<LocalVideo>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshedAt by mutableStateOf(0L)
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private fun cacheFile(context: Context) = File(context.filesDir, "cloud/manifest.json")

    /** Loads the saved address and the last index, then refreshes if it's old. */
    suspend fun start(context: Context) {
        val app = context.applicationContext
        if (url == null) url = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
        val u = url ?: return
        if (videos.isEmpty()) {
            val cached = cacheFile(app)
            if (cached.exists()) {
                runCatching { apply(u, cached.readText()) }.onFailure { Log.w(TAG, "cache", it) }
                refreshedAt = cached.lastModified()
            }
        }
        if (System.currentTimeMillis() - refreshedAt > STALE_MS) refresh(app)
    }

    /** Points BootTV at a new index (or null to forget it). Returns a message for the screen. */
    suspend fun set(context: Context, manifestUrl: String?): String {
        val app = context.applicationContext
        val u = manifestUrl?.trim()?.takeIf { it.isNotEmpty() }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (u == null) remove(KEY_URL) else putString(KEY_URL, u) }.apply()
        url = u
        videos = emptyList(); error = null; refreshedAt = 0
        cacheFile(app).delete()
        LocalLibrary.refreshSoon(app, 0)
        if (u == null) return "Cloud library removed."
        return refresh(app)
    }

    /** Downloads the index again. */
    suspend fun refresh(context: Context): String {
        val app = context.applicationContext
        val u = url ?: return "No cloud library set."
        val full = if (u.endsWith("/")) u + "boottv.json" else if (u.endsWith(".json")) u else "$u/boottv.json"
        return withContext(Dispatchers.IO) {
            try {
                val body = http.newCall(Request.Builder().url(full).header("Accept", "application/json").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("The index couldn't be read (HTTP ${r.code}). Is boottv.json public?")
                    r.body?.string() ?: ""
                }
                apply(full, body)
                cacheFile(app).apply { parentFile?.mkdirs(); writeText(body) }
                refreshedAt = System.currentTimeMillis()
                error = null
                LocalLibrary.refreshSoon(app, 0)
                "$name: ${videos.size} video${if (videos.size == 1) "" else "s"}."
            } catch (e: Exception) {
                Log.w(TAG, "refresh", e)
                error = e.message ?: "Couldn't reach the cloud library."
                error!!
            }
        }
    }

    private fun apply(manifestUrl: String, text: String) {
        val m = json.decodeFromString<CloudManifest>(text)
        name = m.name
        val base = (m.base ?: manifestUrl.substringBeforeLast('/') + "/").let { if (it.endsWith("/")) it else "$it/" }
        videos = m.videos.mapNotNull { e ->
            val path = e.path.trim('/')
            if (path.isEmpty()) return@mapNotNull null
            val parts = path.split('/')
            val file = parts.last()
            val folder = if (parts.size > 1) parts.first() else name
            val subfolder = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString("/") else ""
            LocalLibrary.fromPath(
                id = stableId(path),
                rawTitle = e.title,
                fileName = file,
                folder = folder,
                subfolder = subfolder,
                durationMs = e.duration,
                addedAt = if (e.added > 0) e.added * 1000 else 0,
                sizeBytes = e.size,
                poster = e.poster?.let { absolute(base, it) },
                remote = absolute(base, path),
            )
        }
    }

    /** Joins a bucket path onto the base, encoding spaces and such but keeping the slashes. */
    private fun absolute(base: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else base + path.trim('/').split('/').joinToString("/") { Uri.encode(it) }

    /** The same number for the same path every time (FNV-1a), so Continue watching and ✓ marks stick. */
    private fun stableId(path: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in path.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h or (1L shl 62) // keeps cloud ids clear of MediaStore's small positive ids
    }
}
