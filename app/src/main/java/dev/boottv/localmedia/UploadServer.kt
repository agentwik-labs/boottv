package dev.boottv.localmedia

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import dev.boottv.livetv.MyChannels
import dev.boottv.livetv.MyContent
import dev.boottv.livetv.MyContentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/** One file arriving from a phone or computer. */
data class Upload(val name: String, val folder: String, val received: Long, val total: Long, val done: Boolean = false, val error: String? = null)

data class UploadState(
    val running: Boolean = false,
    /** Address to open on the phone or computer, e.g. http://192.168.1.20:8090 */
    val address: String? = null,
    /** Four digits shown on the TV that the sender types in (only people who can see the TV can send). */
    val code: String = "",
    val uploads: List<Upload> = emptyList(),
    val error: String? = null,
)

/**
 * A small web page on the TV for sending videos from any phone or computer on the same Wi-Fi: open the address in a
 * browser, type the code from the TV, pick files. Videos go to Movies/BootTV/<folder> (so they're in My Media and can
 * become a channel); pictures become posters. It only runs while the Send to TV screen is open.
 */
object UploadServer {
    private const val TAG = "UploadServer"
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "3gp", "mpg", "mpeg", "wmv", "flv")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_BAD_CODES = 8

    private val _state = MutableStateFlow(UploadState())
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(4)
    private var badCodes = 0
    private lateinit var app: Context

    fun start(context: Context) {
        if (server != null) return
        app = context.applicationContext
        val ip = localAddress()
        if (ip == null) {
            _state.value = UploadState(error = "The TV isn't on a Wi-Fi or wired network.")
            return
        }
        val socket = (8090..8099).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        }
        if (socket == null) {
            _state.value = UploadState(error = "Couldn't open a port for sending.")
            return
        }
        server = socket
        if ((pool as java.util.concurrent.ExecutorService).isShutdown) pool = Executors.newFixedThreadPool(4)
        badCodes = 0
        _state.value = UploadState(running = true, address = "http://$ip:${socket.localPort}", code = newCode())
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) }.onFailure { Log.w(TAG, "request", it) }; runCatching { client.close() } }
            }
        }, "BootTV-upload").start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
        _state.update { it.copy(running = false, address = null) }
    }

    /** Forgets finished uploads in the list on the TV. */
    fun clearFinished() = _state.update { s -> s.copy(uploads = s.uploads.filter { !it.done && it.error == null }) }

    private fun newCode() = "%04d".format(SecureRandom().nextInt(10_000))

    /** The TV's IPv4 address on the local network (Wi-Fi or Ethernet). */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    // ---- HTTP -------------------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val out = socket.getOutputStream()
        val req = readRequest(input) ?: return
        when {
            req.method == "GET" && req.path == "/" -> respond(out, 200, "text/html; charset=utf-8", UploadPage.html.toByteArray())
            req.method == "GET" && req.path == "/folders" -> guarded(req, out) {
                LocalLibrary.folders().map { it.first }.joinToString(",", "[", "]") { json(it) }
            }
            req.method == "POST" && req.path == "/upload" -> guarded(req, out) { receive(req) }
            req.method == "POST" && req.path == "/done" -> guarded(req, out) { finish(req) }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    /** Checks the code from the TV; too many wrong tries and the TV shows a new one. */
    private fun guarded(req: Request, out: OutputStream, block: () -> String) {
        val code = _state.value.code
        val given = req.headers["x-code"].orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) {
            if (++badCodes >= MAX_BAD_CODES) { badCodes = 0; _state.update { it.copy(code = newCode()) } }
            respond(out, 403, "application/json", """{"error":"Wrong code. Type the 4 digits shown on the TV."}""".toByteArray())
            return
        }
        val body = try {
            block()
        } catch (e: UploadException) {
            respond(out, e.status, "application/json", """{"error":${json(e.message ?: "Failed")}}""".toByteArray())
            return
        }
        respond(out, 200, "application/json", body.toByteArray())
    }

    private class UploadException(val status: Int, message: String) : Exception(message)

    private fun receive(req: Request): String {
        val name = LocalLibrary.safeName(req.query["name"].orEmpty())
        val folder = LocalLibrary.safeName(req.query["folder"].orEmpty().ifBlank { "Videos" })
        // Folders inside the one being sent ("Season 1") are kept, so a show's seasons stay together.
        val sub = req.query["path"].orEmpty().split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/") { LocalLibrary.safeName(it) }
        val ext = name.substringAfterLast('.', "").lowercase()
        val total = req.headers["content-length"]?.toLongOrNull() ?: throw UploadException(411, "Missing file size.")
        if (ext !in VIDEO && ext !in IMAGE) throw UploadException(415, "$name isn't a video or a picture BootTV can use.")
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (total > free - 200L * 1024 * 1024) throw UploadException(507, "Not enough space on the TV for $name.")

        val entry = Upload(name, folder, 0, total)
        _state.update { it.copy(uploads = it.uploads.filterNot { u -> u.name == name && u.folder == folder } + entry) }
        fun progress(n: Long, done: Boolean = false, error: String? = null) = _state.update { s ->
            s.copy(uploads = s.uploads.map { if (it.name == name && it.folder == folder) it.copy(received = n, done = done, error = error) else it })
        }
        try {
            if (ext in IMAGE) {
                // Posters stay private to BootTV: movie.jpg goes with movie.mp4, poster.jpg with the whole folder.
                val dir = LocalLibrary.posterDir(app, folder).apply { mkdirs() }
                File(dir, name).outputStream().use { copy(req.body, it, total) { n -> progress(n) } }
            } else {
                saveVideo(name, if (sub.isEmpty()) folder else "$folder/$sub", ext, req.body, total) { n -> progress(n) }
            }
            progress(total, done = true)
            LocalLibrary.refreshSoon(app, 500)
            return """{"ok":true}"""
        } catch (e: Exception) {
            progress(0, error = "Stopped")
            throw if (e is UploadException) e else UploadException(500, "$name didn't arrive completely. Try again.")
        }
    }

    /** Into Android's media index, so the video shows up everywhere (My Media, channels, other video apps). */
    private fun saveVideo(name: String, folder: String, ext: String, body: InputStream, total: Long, onProgress: (Long) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeFor(ext))
                put(MediaStore.Video.Media.RELATIVE_PATH, "${LocalLibrary.UPLOAD_DIR}/$folder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw UploadException(500, "Couldn't save $name.")
            try {
                resolver.openOutputStream(uri)!!.use { copy(body, it, total, onProgress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BootTV/$folder").apply { mkdirs() }
            val file = File(dir, name)
            try {
                file.outputStream().use { copy(body, it, total, onProgress) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            MediaScannerConnection.scanFile(app, arrayOf(file.path), null, null)
        }
    }

    /** After a batch: optionally makes the folder a channel. */
    private fun finish(req: Request): String {
        val folder = LocalLibrary.safeName(req.query["folder"].orEmpty().ifBlank { "Videos" })
        if (req.query["channel"] != "1") return """{"ok":true}"""
        return runBlocking {
            LocalLibrary.refresh(app)
            val existing = MyChannels.forFolder(folder)
            val show = LocalLibrary.isShow(folder)
            if (existing != null) {
                MyChannels.refresh(app, existing.id)
                """{"ok":true,"message":${json("Channel ${existing.number} · ${existing.name} now includes them.")}}"""
            } else {
                val ch = runCatching { MyChannels.create(app, MyContent(MyContentKind.DEVICE_FOLDER, folder, folder), shuffle = !show) }
                    .getOrElse { throw UploadException(422, "Saved, but no channel yet: ${it.message} (Videos shorter than a minute aren't used.)") }
                """{"ok":true,"message":${json("Made channel ${ch.number} · ${ch.name}. It's in Live TV and on Home.")}}"""
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var reported = 0L
        while (done < total) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), total - done).toInt())
            if (n < 0) throw java.io.EOFException("Upload cut off")
            output.write(buf, 0, n)
            done += n
            if (done - reported > 2 * 1024 * 1024) { reported = done; onProgress(done) }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        // Headers end at a blank line; read byte by byte so the body stays in the stream.
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.length > 16 * 1024) return null
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val target = parts[1]
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0], target.substringBefore('?'), query, headers, input)
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mimeFor(ext: String) = when (ext) {
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"; "3gp" -> "video/3gpp"; "mpg", "mpeg" -> "video/mpeg"; "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"; else -> "video/mp4"
    }

    private fun json(s: String) = buildString {
        append('"')
        s.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); else -> if (c < ' ') append(' ') else append(c) } }
        append('"')
    }
}
