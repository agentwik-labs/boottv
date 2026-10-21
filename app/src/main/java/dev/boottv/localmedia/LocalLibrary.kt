package dev.boottv.localmedia

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A video on the TV's storage or a USB drive. */
data class LocalVideo(
    val id: Long,
    val title: String,
    /** The folder it's in ("Movies", "Pokémon", "LocalSend"...). Folders are the library's shelves. */
    val folder: String,
    val durationMs: Long,
    val addedAt: Long,
    val sizeBytes: Long,
    /** A poster sent along with it (or next to it on a USB drive), if any. */
    val poster: String?,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    /** The poster, or else a frame from the video itself. */
    val image: String get() = poster ?: uri.toString()
    val ref: String get() = "local:$id"
}

/**
 * Your own videos on the TV (sent from a phone or computer, copied over, or on a USB drive), found through Android's
 * media index. Updates by itself when videos are added or removed.
 */
object LocalLibrary {
    private const val TAG = "LocalLibrary"
    /** Where videos sent with BootTV's upload page go: Movies/BootTV/<folder>. */
    const val UPLOAD_DIR = "Movies/BootTV"

    var videos by mutableStateOf<List<LocalVideo>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var permitted by mutableStateOf(true)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** Loads the library and keeps it current. Safe to call from every screen that shows it. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (observer == null) {
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refreshSoon(app)
            }.also { app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it) }
        }
        refreshSoon(app, 0)
    }

    /** Rescans after things settle (a copy in progress fires many changes). */
    fun refreshSoon(context: Context, waitMs: Long = 1500) {
        pending?.cancel()
        pending = scope.launch { delay(waitMs); refresh(context) }
    }

    suspend fun refresh(context: Context) {
        permitted = hasPermission(context)
        videos = runCatching { scan(context) }.onFailure { Log.w(TAG, "scan", it) }.getOrDefault(videos)
        loaded = true
    }

    fun byId(id: Long): LocalVideo? = videos.firstOrNull { it.id == id }

    /** Folders with their videos, A–Z; each folder newest first. */
    fun folders(): List<Pair<String, List<LocalVideo>>> =
        videos.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (f, v) -> f to v.sortedByDescending { it.addedAt } }

    fun recentlyAdded(limit: Int = 20): List<LocalVideo> = videos.sortedByDescending { it.addedAt }.take(limit)

    /** Every video Android has indexed. Without the permission, only the ones BootTV saved itself. */
    suspend fun scan(context: Context): List<LocalVideo> = withContext(Dispatchers.IO) {
        val posters = posters(context)
        val out = ArrayList<LocalVideo>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_ID,
        )
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val dur = c.getLong(3)
                if (dur in 1 until 5_000) continue // skip tiny clips
                val file = c.getString(2).orEmpty()
                val base = file.substringBeforeLast('.')
                val folder = c.getString(4)?.takeIf { it.isNotBlank() } ?: "Videos"
                val dir = safeName(folder)
                out.add(
                    LocalVideo(
                        id = c.getLong(0),
                        title = cleanTitle(c.getString(1), base),
                        folder = folder,
                        durationMs = dur,
                        addedAt = c.getLong(5) * 1000,
                        sizeBytes = c.getLong(6),
                        poster = posters["${c.getLong(7)}/${base.lowercase()}"] ?: posters["$dir/${base.lowercase()}"]
                            ?: posters["${c.getLong(7)}/"] ?: posters["$dir/"],
                    )
                )
            }
        }
        out
    }

    /** "Title - http://..." (some files carry a web address in their title) or a file name, made readable. */
    fun cleanTitle(title: String?, fileBase: String): String {
        val t = title?.takeIf { it.isNotBlank() && it != fileBase } ?: fileBase.replace('_', ' ').replace('.', ' ')
        return t.replace(Regex("""\s+-\s+https?://\S+$"""), "").trim().ifEmpty { "Video" }
    }

    // ---- Posters ----------------------------------------------------------------------------------

    private val posterNames = setOf("poster", "folder", "cover")

    /** Private posters (sent with the upload page): files/posters/<folder>/<video name>.jpg. */
    fun posterDir(context: Context, folder: String) = File(context.filesDir, "posters/${safeName(folder)}")

    /**
     * Pictures that go with videos, keyed "<folder or bucket id>/<video name>" (or "<folder>/" for a whole folder's
     * picture: poster.jpg, folder.jpg, cover.jpg).
     */
    private fun posters(context: Context): Map<String, String> {
        val map = HashMap<String, String>()
        File(context.filesDir, "posters").listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val base = f.nameWithoutExtension.lowercase()
                map[if (base in posterNames) "${dir.name}/" else "${dir.name}/$base"] = Uri.fromFile(f).toString()
            }
        }
        // Pictures sitting next to the videos (a USB drive with movie.mkv + movie.jpg). Needs the photos permission.
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_ID)
            runCatching {
                context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val base = c.getString(1).orEmpty().substringBeforeLast('.').lowercase()
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        map.putIfAbsent(if (base in posterNames) "${c.getLong(2)}/" else "${c.getLong(2)}/$base", uri)
                    }
                }
            }
        }
        return map
    }

    /** A folder or file name that's safe on any storage. */
    fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("""\s+"""), " ").trim().trimStart('.').take(80).ifEmpty { "Videos" }
}

/** Where you stopped in your own videos, for Resume and Continue watching. */
object LocalProgress {
    private const val PREFS = "local_progress"
    private lateinit var app: Context

    data class Entry(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
        val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    /** By video id. */
    val entries = mutableStateMapOf<Long, Entry>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val p = (v as? String)?.split('|')?.mapNotNull { it.toLongOrNull() } ?: return@forEach
            val id = k.toLongOrNull() ?: return@forEach
            if (p.size == 3) entries[id] = Entry(p[0], p[1], p[2])
        }
    }

    /** Near the start or the end counts as not started / finished. */
    fun save(id: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs < 30_000 || positionMs > durationMs - 60_000) { clear(id); return }
        val e = Entry(positionMs, durationMs, System.currentTimeMillis())
        entries[id] = e
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$id", "${e.positionMs}|${e.durationMs}|${e.updatedAt}").apply()
    }

    fun clear(id: Long) {
        entries.remove(id)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$id").apply()
    }
}
