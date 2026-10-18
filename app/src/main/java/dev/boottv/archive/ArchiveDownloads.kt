package dev.boottv.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A film saved (or being saved) to the device for offline viewing. */
@Serializable
data class ArchiveDownload(
    val id: String,
    val title: String,
    val year: String? = null,
    /** Path of the video inside the torrent, relative to the save folder (e.g. "<id>/film.mp4"). */
    val path: String = "",
    val infoHash: String = "",
    /** Direct archive.org address and MD5 of the same file, to finish and verify a stalled download. */
    val url: String = "",
    val md5: String? = null,
    val done: Boolean = false,
)

sealed interface DownloadState {
    data class Running(val progress: Float, val bytesPerSec: Long, val peers: Int, val checking: Boolean) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Offline copies of Free Movies, fetched over BitTorrent.
 *
 * Deliberately narrow: the only torrents it ever loads are the Internet Archive's own .torrent files for items in
 * the curated collections (taken from the item's metadata, host checked), never a magnet link, a search, or a
 * user-supplied URL. Archive torrents list archive.org itself as a web seed, so they finish even with no peers.
 * Only the chosen video file is downloaded, and seeding stops when it's done.
 */
object ArchiveDownloads {
    private const val TAG = "ArchiveDownloads"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Saved and in-progress downloads, keyed by archive id. */
    val items = mutableStateMapOf<String, ArchiveDownload>()
    val states = mutableStateMapOf<String, DownloadState>()

    private lateinit var app: Context
    private var session: SessionManager? = null
    private val handles = mutableMapOf<String, TorrentHandle>()
    private val deleting = mutableMapOf<String, Job>()

    // App-private internal storage: on Android 11+ shared storage goes through a FUSE daemon that can fall over when
    // libtorrent opens a file as it's being deleted, taking every app using it down too.
    private val baseDir get() = File(app.filesDir, "free_movies").apply { mkdirs() }
    private val indexFile get() = File(app.filesDir, "free_downloads.json")
    /** libtorrent's scratch file for pieces shared with files we skipped. */
    private fun partsFile(d: ArchiveDownload) = File(baseDir, ".${d.infoHash}.parts")
    private fun torrentFile(id: String) = File(app.filesDir, "free_torrents/$id.torrent")

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        runCatching { json.decodeFromString<List<ArchiveDownload>>(indexFile.readText()) }.getOrNull().orEmpty().forEach { d ->
            if (d.done && !File(baseDir, d.path).isFile) return@forEach // deleted outside the app
            items[d.id] = d
            if (d.done) states[d.id] = DownloadState.Done
        }
        // Pick unfinished downloads back up.
        items.values.filter { !it.done }.forEach { d -> scope.launch { runCatching { start(d, torrentFile(d.id).readBytes()) }.onFailure { fail(d.id, it) } } }
    }

    /** The finished local file for [id], or null. */
    fun localFile(id: String): File? = items[id]?.takeIf { it.done }?.let { File(baseDir, it.path) }?.takeIf { it.isFile }

    fun download(details: ArchiveDetails) {
        val id = details.item.id
        val torrentUrl = details.torrentUrl ?: return fail(id, IOException("This title has no download."))
        val video = details.downloadUrl ?: return fail(id, IOException("No video to download."))
        states[id] = DownloadState.Running(0f, 0, 0, checking = true)
        scope.launch {
            try {
                deleting[id]?.join() // a quick re-download waits for the old copy to be gone
                val bytes = fetchTorrent(torrentUrl)
                val wanted = Uri.decode(Uri.parse(video).path.orEmpty().substringAfter("/download/$id/"))
                val d = ArchiveDownload(id, details.item.title, details.item.year, path = "$id/$wanted", url = video, md5 = details.downloadMd5)
                torrentFile(id).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                start(d, bytes)
            } catch (e: Throwable) {
                fail(id, e)
            }
        }
    }

    fun cancelOrDelete(id: String) {
        val d = items.remove(id)
        states.remove(id)
        deleting[id] = scope.launch {
            val running = synchronized(this@ArchiveDownloads) { handles.remove(id)?.also { session?.remove(it) } }
            if (running != null) delay(1000) // let libtorrent close the file first
            if (d != null) { File(baseDir, d.id).deleteRecursively(); partsFile(d).delete() }
            torrentFile(id).delete()
            save()
            stopIfIdle()
        }
    }

    /** Only ever the Archive's own torrent files. */
    private suspend fun fetchTorrent(url: String): ByteArray = withContext(Dispatchers.IO) {
        val host = Uri.parse(url).host.orEmpty()
        require(Uri.parse(url).scheme == "https" && (host == "archive.org" || host.endsWith(".archive.org")) && url.endsWith("_archive.torrent")) {
            "Downloads only come from archive.org."
        }
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("archive.org returned ${resp.code}")
            resp.body?.bytes() ?: throw IOException("Empty torrent file")
        }
    }

    private suspend fun start(d: ArchiveDownload, bytes: ByteArray) {
        val ti = TorrentInfo(bytes)
        val files = ti.files()
        val index = (0 until files.numFiles()).firstOrNull { files.filePath(it).replace('\\', '/') == d.path }
            ?: (0 until files.numFiles()).firstOrNull { files.filePath(it).endsWith("/" + d.path.substringAfterLast('/')) }
            ?: throw IOException("The video isn't in this title's torrent.")
        val path = files.filePath(index).replace('\\', '/')
        val fileSize = files.fileSize(index)
        val record = d.copy(path = path, infoHash = ti.infoHash().toHex())
        items[d.id] = record
        save()

        val priorities = Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE }
        val handle = synchronized(this) {
            val s = session ?: SessionManager(false).also {
                it.start(SessionParams(SettingsPack().apply {
                    activeDownloads(2)
                    connectionsLimit(60)
                    uploadRateLimit(256 * 1024)
                }))
                session = it
            }
            s.download(ti, baseDir, null, priorities, null, torrent_flags_t())
            s.find(Sha1Hash.parseHex(record.infoHash)) ?: throw IOException("Couldn't start the download.")
        }
        // libtorrent4j drops the file's trackers and web seeds on load; put back the Archive's own.
        val (trackers, webSeeds) = trackersAndSeeds(bytes)
        trackers.forEach { handle.addTracker(AnnounceEntry(it)) }
        webSeeds.forEach { handle.addUrlSeed(it) }
        handles[d.id] = handle

        // Poll progress for the one file we want.
        var last = -1L
        var lastChange = System.currentTimeMillis()
        while (scope.isActive && items.containsKey(d.id)) {
            val h = synchronized(this) { handles[d.id] } ?: return
            val st = h.status()
            val done = h.fileProgress().getOrNull(index) ?: 0L
            val checking = st.state().name.startsWith("CHECKING") || st.state().name == "DOWNLOADING_METADATA"
            if (done != last) { last = done; lastChange = System.currentTimeMillis() }
            // Archive torrents go stale when the item's small metadata files change after the torrent was made:
            // the pieces our video shares with them never verify. Fetch just those bytes over HTTP instead.
            val stalledAtEdges = !checking && fileSize > 0 && System.currentTimeMillis() - lastChange > 20_000 &&
                missingRanges(ti, h, index).let { it.isNotEmpty() && it.sumOf { r -> r.last - r.first + 1 } <= 4L * ti.pieceLength() }
            if (stalledAtEdges) {
                val ranges = missingRanges(ti, h, index)
                synchronized(this) { handles.remove(d.id)?.let { session?.remove(it) } }
                states[d.id] = DownloadState.Running(done.toFloat() / fileSize, 0, 0, checking = true)
                delay(1000) // let libtorrent flush and close the file
                patch(File(baseDir, path), record.url, ranges)
                if (record.md5 != null && md5(File(baseDir, path)) != record.md5.lowercase()) throw IOException("The downloaded file didn't check out. Try again.")
            }
            if (stalledAtEdges || (fileSize > 0 && done >= fileSize)) {
                synchronized(this) { handles.remove(d.id)?.let { session?.remove(it) } } // stop seeding; keep the file
                items[d.id] = record.copy(done = true)
                states[d.id] = DownloadState.Done
                torrentFile(d.id).delete()
                partsFile(record).delete()
                save()
                stopIfIdle()
                return
            }
            st.errorCode()?.takeIf { it.isError }?.let { throw IOException(it.message) }
            states[d.id] = DownloadState.Running(if (fileSize > 0) done.toFloat() / fileSize else 0f, st.downloadPayloadRate().toLong(), st.numPeers(), checking)
            delay(1000)
        }
    }

    /** Byte ranges of file [index] (relative to the file) in pieces we don't have yet. */
    private fun missingRanges(ti: TorrentInfo, h: TorrentHandle, index: Int): List<LongRange> {
        val files = ti.files()
        val pieceLen = ti.pieceLength().toLong()
        val start = files.fileOffset(index)
        val end = start + files.fileSize(index)
        if (end <= start) return emptyList()
        return ((start / pieceLen)..((end - 1) / pieceLen)).filter { !h.havePiece(it.toInt()) }.map { p ->
            maxOf(p * pieceLen, start) - start..minOf((p + 1) * pieceLen, end) - start - 1
        }
    }

    private fun patch(file: File, url: String, ranges: List<LongRange>) {
        val host = Uri.parse(url).host.orEmpty()
        require(host == "archive.org" || host.endsWith(".archive.org")) { "Downloads only come from archive.org." }
        java.io.RandomAccessFile(file, "rw").use { out ->
            for (r in ranges) {
                val req = Request.Builder().url(url).header("Range", "bytes=${r.first}-${r.last}").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code != 206) throw IOException("archive.org returned ${resp.code}")
                    val body = resp.body?.bytes() ?: throw IOException("Empty response")
                    if (body.size.toLong() != r.last - r.first + 1) throw IOException("Short read from archive.org")
                    out.seek(r.first)
                    out.write(body)
                }
            }
        }
    }

    private fun md5(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** "announce"/"announce-list" and "url-list" from the top level of a .torrent file, archive.org hosts only. */
    private fun trackersAndSeeds(bytes: ByteArray): Pair<List<String>, List<String>> {
        var i = 0
        fun next(c: Char, from: Int) = (from until bytes.size).first { bytes[it] == c.code.toByte() }
        fun parse(): Any? = when (val c = bytes[i].toInt().toChar()) {
            'i' -> { val end = next('e', i); i = end + 1; null }
            'l' -> { i++; buildList { while (bytes[i] != 'e'.code.toByte()) add(parse()) }.also { i++ } }
            'd' -> { i++; buildMap { while (bytes[i] != 'e'.code.toByte()) { val k = parse() as String; put(k, parse()) } }.also { i++ } }
            in '0'..'9' -> {
                val colon = next(':', i)
                val len = String(bytes, i, colon - i).toInt()
                String(bytes, colon + 1, len, Charsets.ISO_8859_1).also { i = colon + 1 + len }
            }
            else -> throw IOException("Bad torrent file ($c)")
        }
        val root = parse() as? Map<*, *> ?: return emptyList<String>() to emptyList()
        fun strings(v: Any?): List<String> = when (v) { is String -> listOf(v); is List<*> -> v.flatMap { strings(it) }; else -> emptyList() }
        fun archive(u: String) = Uri.parse(u).host.orEmpty().let { it == "archive.org" || it.endsWith(".archive.org") }
        val trackers = (strings(root["announce"]) + strings(root["announce-list"])).distinct().filter(::archive)
        return trackers to strings(root["url-list"]).distinct().filter(::archive)
    }

    private fun fail(id: String, e: Throwable) {
        Log.w(TAG, "download $id failed", e)
        states[id] = DownloadState.Failed(e.message ?: "Download failed")
        synchronized(this) { handles.remove(id)?.let { session?.remove(it) } }
        if (items[id]?.done != true) items.remove(id)
        scope.launch { save(); stopIfIdle() }
    }

    private fun stopIfIdle() = synchronized(this) {
        if (handles.isEmpty()) { session?.stop(); session = null }
    }

    private fun save() = synchronized(this) {
        runCatching { indexFile.writeText(json.encodeToString(items.values.toList())) }
    }
}
