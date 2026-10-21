package dev.boottv.livetv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import dev.boottv.BootApp
import dev.boottv.jellyfin.JellyfinClient
import dev.boottv.jellyfin.JfHttp
import dev.boottv.localmedia.LocalLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** What a personal channel plays. */
@Serializable
enum class MyContentKind { JELLYFIN_MOVIES, JELLYFIN_EPISODES, JELLYFIN_LIBRARY, DEVICE_VIDEOS, DEVICE_FOLDER }

@Serializable
data class MyContent(
    val kind: MyContentKind,
    val label: String,
    /** The Jellyfin library, or for [MyContentKind.DEVICE_FOLDER] the folder's name. */
    val libraryId: String? = null,
)

/** One title in a personal channel's line-up. [ref] is "jf:<item id>" or "local:<MediaStore id>". */
@Serializable
data class MyItem(
    val ref: String,
    val title: String,
    val subtitle: String? = null,
    val desc: String? = null,
    val durationMs: Long,
    /** Its picture: a poster or Jellyfin artwork, or the video itself (a frame is shown). */
    val image: String? = null,
)

/**
 * A channel made from your own movies and shows. It runs on a fixed loop that started at [epoch], like broadcast TV:
 * tuning in joins whatever is on, part-way through.
 */
@Serializable
data class MyChannel(
    val id: String,
    val name: String,
    val number: String,
    val content: MyContent,
    val shuffle: Boolean = true,
    val epoch: Long,
    val items: List<MyItem> = emptyList(),
    val refreshedAt: Long = 0,
)

/** A slot in a channel's schedule. */
data class MySlot(val item: MyItem, val start: Long) {
    val end get() = start + item.durationMs
}

object MyChannels {
    private const val TAG = "MyChannels"
    const val URL_PREFIX = "mychannel:"
    const val ID_PREFIX = "my:"
    const val GROUP = "My Channels"
    /** Programmes shown in the guide around now. */
    private const val PAST_MS = 3 * 60 * 60_000L
    private const val AHEAD_MS = 26 * 60 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(BootApp.instance.filesDir, "livetv/my_channels.json")
    private val lock = Mutex()
    private val _channels = MutableStateFlow<List<MyChannel>>(emptyList())
    val channels: StateFlow<List<MyChannel>> = _channels.asStateFlow()

    fun load() {
        _channels.value = runCatching { json.decodeFromString<List<MyChannel>>(file.readText()) }.getOrDefault(emptyList())
    }

    private suspend fun save(list: List<MyChannel>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        tmp.renameTo(file)
        _channels.value = list
    }

    /** Creates a channel and fills its line-up. Throws with a readable message when there's nothing to play. */
    suspend fun create(context: Context, content: MyContent): MyChannel {
        val items = fetch(context, content)
        if (items.isEmpty()) throw IllegalStateException("Nothing to play in ${content.label} yet.")
        return lock.withLock {
            val list = _channels.value
            val number = (1..99).first { n -> list.none { it.number == "$n" } }.toString()
            val ch = MyChannel(
                id = UUID.randomUUID().toString().replace("-", "").take(10),
                name = content.label,
                number = number,
                content = content,
                epoch = System.currentTimeMillis(),
                items = items,
                refreshedAt = System.currentTimeMillis(),
            )
            save(list + ch)
            ch
        }
    }

    suspend fun remove(id: String) = lock.withLock { save(_channels.value.filterNot { it.id == id }) }

    suspend fun rename(id: String, name: String) = lock.withLock {
        save(_channels.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    /** Picks up titles added to (or removed from) the library since the channel was made. Keeps the schedule's start. */
    suspend fun refresh(context: Context, id: String) {
        val ch = _channels.value.firstOrNull { it.id == id } ?: return
        val items = fetch(context, ch.content)
        if (items.isEmpty()) return
        lock.withLock { save(_channels.value.map { if (it.id == id) it.copy(items = items, refreshedAt = System.currentTimeMillis()) else it }) }
    }

    suspend fun refreshAll(context: Context) {
        _channels.value.forEach { runCatching { refresh(context, it.id) }.onFailure { e -> Log.w(TAG, "refresh ${it.name}", e) } }
    }

    // ---- Schedule -----------------------------------------------------------------------------

    /** The loop's order: shuffled once per channel (the same every day), or as listed. */
    private fun order(ch: MyChannel): List<MyItem> =
        if (ch.shuffle) ch.items.shuffled(Random(ch.epoch)) else ch.items

    /** Slots covering [from, to). */
    fun schedule(ch: MyChannel, from: Long, to: Long): List<MySlot> {
        val items = order(ch).filter { it.durationMs > 0 }
        val loop = items.sumOf { it.durationMs }
        if (loop <= 0) return emptyList()
        // Start of the loop pass that contains `from`.
        var t = ch.epoch + Math.floorDiv(from - ch.epoch, loop) * loop
        val out = ArrayList<MySlot>()
        var i = 0
        while (t < to) {
            val item = items[i % items.size]
            if (t + item.durationMs > from) out.add(MySlot(item, t))
            t += item.durationMs
            i++
        }
        return out
    }

    fun nowPlaying(ch: MyChannel, now: Long = System.currentTimeMillis()): MySlot? = schedule(ch, now, now + 1).firstOrNull()

    fun channel(id: String): MyChannel? = _channels.value.firstOrNull { "$ID_PREFIX${it.id}" == id || it.id == id }

    /** As live channels for the guide, with their schedule as programmes. */
    fun asLive(now: Long = System.currentTimeMillis()): Pair<List<Channel>, Map<String, List<Programme>>> {
        val chans = _channels.value
        val live = chans.map { ch ->
            Channel(id = "$ID_PREFIX${ch.id}", sourceId = "my", name = ch.name, number = ch.number, groups = listOf(GROUP), url = "$URL_PREFIX${ch.id}")
        }
        val programmes = chans.associate { ch ->
            "$ID_PREFIX${ch.id}" to schedule(ch, now - PAST_MS, now + AHEAD_MS).map { s ->
                Programme(start = s.start, stop = s.end, title = s.item.title, subtitle = s.item.subtitle, desc = s.item.desc, image = s.item.image)
            }
        }
        return live to programmes
    }

    // ---- Playback -----------------------------------------------------------------------------

    /**
     * Tunes [player] to what's on now on [channel], joined part-way through. When it ends, the caller tunes again and
     * gets the next title. The media id carries the slot's start so a paused channel can catch up to "live".
     */
    suspend fun tune(player: ExoPlayer, channel: Channel) {
        val ch = channel(channel.id) ?: throw IllegalStateException("This channel was removed.")
        val now = System.currentTimeMillis()
        val slot = nowPlaying(ch, now) ?: throw IllegalStateException("${ch.name} has nothing to play.")
        val (uri, hls) = streamFor(slot.item)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$ID_PREFIX${ch.id}@${slot.start}")
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, (now - slot.start).coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    /** For a personal channel's media id: where "live" is now, in the current title. Null for other media. */
    fun livePosition(mediaId: String?, now: Long = System.currentTimeMillis()): Long? {
        if (mediaId == null || !mediaId.startsWith(ID_PREFIX)) return null
        val start = mediaId.substringAfterLast('@').toLongOrNull() ?: return null
        return (now - start).coerceAtLeast(0)
    }

    private suspend fun streamFor(item: MyItem): Pair<Uri, Boolean> = when {
        item.ref.startsWith("local:") -> {
            val id = item.ref.removePrefix("local:").toLong()
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id) to false
        }
        item.ref.startsWith("jf:") -> {
            val client = JellyfinClient.current() ?: throw IllegalStateException("Sign in to My Media to watch this channel.")
            val id = item.ref.removePrefix("jf:")
            val info = client.playbackInfo(id, null, null, allowDirect = true)
            val ms = info.mediaSources.firstOrNull() ?: throw IllegalStateException("${item.title} can't be played.")
            val token = client.session.token
            if ((ms.supportsDirectPlay || ms.supportsDirectStream) || ms.transcodingUrl == null) {
                val q = JfHttp.query(
                    "static" to true, "MediaSourceId" to ms.id, "PlaySessionId" to info.playSessionId,
                    "DeviceId" to client.session.deviceId, "api_key" to token,
                )
                Uri.parse("${client.server}/Videos/$id/stream?$q") to false
            } else {
                val t = ms.transcodingUrl
                val full = if (t.startsWith("http")) t else client.server + t
                val withKey = if ("api_key=" in full) full else full + (if ('?' in full) "&" else "?") + "api_key=$token"
                Uri.parse(withKey) to (ms.transcodingSubProtocol.equals("hls", true) || ".m3u8" in t)
            }
        }
        else -> throw IllegalStateException("Unknown title")
    }

    // ---- Line-ups -----------------------------------------------------------------------------

    private suspend fun fetch(context: Context, content: MyContent): List<MyItem> = when (content.kind) {
        MyContentKind.DEVICE_VIDEOS -> deviceVideos(context, null)
        MyContentKind.DEVICE_FOLDER -> deviceVideos(context, content.libraryId)
        else -> jellyfinItems(content)
    }

    private suspend fun jellyfinItems(content: MyContent): List<MyItem> {
        val client = JellyfinClient.current() ?: throw IllegalStateException("Sign in to My Media first (Settings › My Media).")
        val types = when (content.kind) {
            MyContentKind.JELLYFIN_MOVIES -> "Movie"
            MyContentKind.JELLYFIN_EPISODES -> "Episode"
            else -> "Movie,Episode,Video,MusicVideo"
        }
        val result = client.items(
            "IncludeItemTypes" to types,
            "Recursive" to true,
            "ParentId" to content.libraryId,
            "SortBy" to "SortName",
            "Limit" to 2000,
        )
        return result.items.mapNotNull { it ->
            val ms = (it.runTimeTicks ?: 0L) / 10_000
            if (ms < 60_000) return@mapNotNull null
            val episode = it.isEpisode && it.seriesName != null
            MyItem(
                ref = "jf:${it.id}",
                title = if (episode) it.seriesName!! else it.name ?: "Untitled",
                subtitle = if (episode) listOfNotNull(
                    it.parentIndexNumber?.let { s -> "S$s" }?.plus(it.indexNumber?.let { e -> "E$e" } ?: ""),
                    it.name,
                ).joinToString(" · ") else it.productionYear?.toString(),
                desc = it.overview,
                durationMs = ms,
                image = client.wideUrl(it) ?: client.posterUrl(it),
            )
        }
    }

    /** Videos on the TV's storage and any USB drive plugged in (all of them, or one [folder]). */
    private suspend fun deviceVideos(context: Context, folder: String?): List<MyItem> =
        LocalLibrary.scan(context)
            .filter { it.durationMs >= 60_000 && (folder == null || it.folder == folder) } // skip clips and samples
            .sortedBy { it.title.lowercase() }
            .map { MyItem(ref = it.ref, title = it.title, durationMs = it.durationMs, image = it.image) }

    /** The channel already made from [folder], if any. */
    fun forFolder(folder: String): MyChannel? =
        _channels.value.firstOrNull { it.content.kind == MyContentKind.DEVICE_FOLDER && it.content.libraryId == folder }
}
