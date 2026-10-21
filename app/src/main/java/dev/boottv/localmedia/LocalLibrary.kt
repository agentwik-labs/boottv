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
    /** Folders below [folder] ("Season 1"), when the video came through Send to TV with its folders kept. */
    val subfolder: String = "",
    /** Season and episode numbers read from the file name ("S01E03", "1x03", "Episode 3") or a "Season 2" folder. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Set for a video streamed from the cloud library; null for one on the TV. */
    val remote: String? = null,
) {
    val uri: Uri get() = remote?.let(Uri::parse) ?: ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    val isRemote: Boolean get() = remote != null
    val isEpisode: Boolean get() = episode != null
    /** "S1 E3" (or "E3" when there is no season). */
    val episodeLabel: String get() = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")
    /** The poster, or else a frame from the video itself (not for a cloud video: that would download it). */
    val image: String? get() = poster ?: if (remote == null) uri.toString() else null
    val ref: String get() = if (remote != null) "url:$remote" else "local:$id"
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
        runCatching { CloudLibrary.start(context) }.onFailure { Log.w(TAG, "cloud", it) }
        val local = runCatching { scan(context) }.onFailure { Log.w(TAG, "scan", it) }.getOrDefault(videos.filter { !it.isRemote })
        videos = local + CloudLibrary.videos
        loaded = true
    }

    fun byId(id: Long): LocalVideo? = videos.firstOrNull { it.id == id }

    /** Folders with their videos, A–Z; each folder newest first. */
    fun folders(): List<Pair<String, List<LocalVideo>>> =
        videos.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (f, v) -> f to if (v.any { it.isEpisode }) v.sortedWith(episodeOrder) else v.sortedByDescending { it.addedAt } }

    fun recentlyAdded(limit: Int = 20): List<LocalVideo> = videos.sortedByDescending { it.addedAt }.take(limit)

    /** Videos on the TV plus the cloud library, for channels. */
    suspend fun all(context: Context): List<LocalVideo> {
        runCatching { CloudLibrary.start(context) }
        return scan(context) + CloudLibrary.videos
    }

    /** One entry from its path and facts, applying the naming rules (used for videos on the TV and in the cloud). */
    fun fromPath(id: Long, rawTitle: String?, fileName: String, folder: String, subfolder: String, durationMs: Long, addedAt: Long, sizeBytes: Long, poster: String?, remote: String? = null): LocalVideo {
        val base = fileName.substringBeforeLast('.')
        val parsed = parseEpisode(base, subfolder)
        return LocalVideo(
            id = id, title = parsed?.title ?: cleanTitle(rawTitle, base), folder = folder, subfolder = subfolder,
            season = parsed?.season, episode = parsed?.episode, durationMs = durationMs, addedAt = addedAt, sizeBytes = sizeBytes,
            poster = poster, remote = remote,
        )
    }

    /** Every video Android has indexed. Without the permission, only the ones BootTV saved itself. */
    suspend fun scan(context: Context): List<LocalVideo> = withContext(Dispatchers.IO) {
        val posters = posters(context)
        val out = ArrayList<LocalVideo>()
        @Suppress("DEPRECATION")
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_ID, pathCol,
        )
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val dur = c.getLong(3)
                if (dur in 1 until 5_000) continue // skip tiny clips
                val file = c.getString(2).orEmpty()
                val base = file.substringBeforeLast('.')
                // Under Movies/BootTV the first folder is the collection (a show or a set of films) and anything
                // deeper ("Season 1") stays with it; elsewhere the video's own folder is the collection.
                val rel = c.getString(8).orEmpty().replace('\\', '/').trimEnd('/').substringBeforeLast('/', "").trimEnd('/')
                val under = rel.substringAfter("$UPLOAD_DIR/", "")
                val folder = under.substringBefore('/').takeIf { under.isNotBlank() } ?: c.getString(4)?.takeIf { it.isNotBlank() } ?: "Videos"
                val subfolder = if (under.isNotBlank()) under.substringAfter('/', "") else ""
                val dir = safeName(folder)
                out.add(
                    fromPath(
                        id = c.getLong(0), rawTitle = c.getString(1), fileName = file, folder = folder, subfolder = subfolder,
                        durationMs = dur, addedAt = c.getLong(5) * 1000, sizeBytes = c.getLong(6),
                        poster = posters["${c.getLong(7)}/${base.lowercase()}"] ?: posters["$dir/${base.lowercase()}"]
                            ?: posters["${c.getLong(7)}/"] ?: posters["$dir/"],
                    )
                )
            }
        }
        out
    }

    private class Episode(val season: Int?, val episode: Int, val title: String)
    private val sxxExx = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})\b""")
    private val nXn = Regex("""(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)""")
    private val epWord = Regex("""(?i)\b(?:episode|ep)\.?\s*(\d{1,3})\b""")
    private val seasonDir = Regex("""(?i)^(?:season|series|s)\s*0*(\d{1,2})$""")
    private val trailingNumber = Regex("""(?:^|[ ._-])0*(\d{1,3})$""")

    /** Season/episode from a file name like "Show - S01E03 - Title", "1x03", "Episode 3", or "03" inside a Season folder. */
    private fun parseEpisode(base: String, subfolder: String): Episode? {
        val folderSeason = subfolder.split('/').lastOrNull()?.trim()?.let { seasonDir.find(it)?.groupValues?.get(1)?.toInt() }
        val m = sxxExx.find(base) ?: nXn.find(base)
        val (season, episode, range) = when {
            m != null -> Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.range)
            else -> {
                val e = epWord.find(base) ?: (if (folderSeason != null) trailingNumber.find(base) else null) ?: return null
                Triple(folderSeason, e.groupValues[1].toInt(), e.range)
            }
        }
        val rest = base.removeRange(range).replace('_', ' ').replace('.', ' ')
        // What's left after the show name: "Show - S01E03 - Title" keeps "Title"; "S01E03" alone becomes "Episode 3".
        val title = rest.split(Regex("""\s+[-–]\s+""")).map { it.trim(' ', '-', '–') }.filter { it.isNotEmpty() }.drop(if (range.first > 0 && rest.contains(Regex("""\s[-–]\s"""))) 1 else 0)
            .lastOrNull()?.takeIf { it.length > 1 } ?: "Episode $episode"
        return Episode(season ?: folderSeason, episode, title)
    }

    /** True when a collection has numbered episodes: it's a show, kept in order rather than newest first. */
    fun isShow(folder: String) = videos.any { it.folder == folder && it.isEpisode }

    private val episodeOrder = compareBy<LocalVideo>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.title.lowercase() })

    /** A show's episodes in order; for a plain folder, newest first. */
    fun episodes(folder: String): List<LocalVideo> =
        videos.filter { it.folder == folder }.let { l -> if (l.any { it.isEpisode }) l.sortedWith(episodeOrder) else l.sortedByDescending { it.addedAt } }

    /** The episode after [id] in its show, or null (end of the show, or not a show). */
    fun nextEpisode(id: Long): LocalVideo? {
        val v = byId(id) ?: return null
        if (!v.isEpisode) return null
        val eps = episodes(v.folder)
        return eps.getOrNull(eps.indexOfFirst { it.id == id } + 1)
    }

    /** Where to pick a show up: the episode part-way through, else the first one not yet watched. */
    fun upNext(folder: String): LocalVideo? {
        val eps = episodes(folder)
        return eps.filter { LocalProgress.entries.containsKey(it.id) }.maxByOrNull { LocalProgress.entries.getValue(it.id).updatedAt }
            ?: eps.firstOrNull { !LocalProgress.isWatched(it.id) }
    }

    /** Partly watched videos, newest first, plus the next episode of every show that's been started. */
    fun continueWatching(): List<LocalVideo> {
        val partial = LocalProgress.entries.entries.sortedByDescending { it.value.updatedAt }.mapNotNull { byId(it.key) }
        val shows = videos.filter { it.isEpisode }.map { it.folder }.distinct()
            .filter { f -> videos.any { it.folder == f && LocalProgress.isWatched(it.id) } }
            .mapNotNull { upNext(it) }
        return (partial + shows).distinctBy { it.id }
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
    private const val WATCHED = "local_watched"
    private lateinit var app: Context

    data class Entry(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
        val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    /** By video id. */
    val entries = mutableStateMapOf<Long, Entry>()
    /** Videos played to the end (the ✓ in the Dex, and what "next episode" skips). */
    private val watched = mutableStateMapOf<Long, Boolean>()

    fun isWatched(id: Long) = watched.containsKey(id)

    fun markWatched(id: Long) {
        if (watched.containsKey(id)) return
        watched[id] = true
        app.getSharedPreferences(WATCHED, Context.MODE_PRIVATE).edit().putBoolean("$id", true).apply()
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        app.getSharedPreferences(WATCHED, Context.MODE_PRIVATE).all.keys.forEach { k -> k.toLongOrNull()?.let { watched[it] = true } }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val p = (v as? String)?.split('|')?.mapNotNull { it.toLongOrNull() } ?: return@forEach
            val id = k.toLongOrNull() ?: return@forEach
            if (p.size == 3) entries[id] = Entry(p[0], p[1], p[2])
        }
    }

    /** Near the start or the end counts as not started / finished. */
    fun save(id: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // The last minute counts as finished (credits), but never more than the last tenth of a short video.
        val tail = minOf(60_000L, durationMs / 10)
        if (positionMs > durationMs - tail) markWatched(id)
        if (positionMs < minOf(30_000L, durationMs / 10) || positionMs > durationMs - tail) { clear(id); return }
        val e = Entry(positionMs, durationMs, System.currentTimeMillis())
        entries[id] = e
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$id", "${e.positionMs}|${e.durationMs}|${e.updatedAt}").apply()
    }

    fun clear(id: Long) {
        entries.remove(id)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$id").apply()
    }
}
