package dev.boottv.livetv

import android.util.Log
import dev.boottv.BootApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for Live TV: sources, parsed channels/guide (cached as JSON in filesDir),
 * favorites and last-channel memory. Refreshes stale sources (>6h) in the background.
 */
@OptIn(ExperimentalSerializationApi::class)
object LiveTvRepository {
    private const val TAG = "LiveTv"
    const val STALE_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_MS = 30 * 60 * 1000L
    private const val EPG_PAST_MS = 2 * 60 * 60 * 1000L
    private const val EPG_FUTURE_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configLock = Mutex()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val dir: File by lazy { File(BootApp.instance.filesDir, "livetv").apply { mkdirs() } }
    private val configFile get() = File(dir, "config.json")
    private fun cacheFile(id: String) = File(dir, "cache_$id.json")

    @Volatile private var config = LiveConfig()
    private val caches = ConcurrentHashMap<String, SourceCache>()

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var initJob: Job? = null

    /** Starts loading from disk (idempotent) and the background stale-refresh loop. */
    @Synchronized
    fun init() {
        if (initJob != null) return
        initJob = scope.launch {
            val firstRun = !configFile.exists()
            try {
                loadFromDisk()
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
            }
            // A fresh install starts with the free channel lists, so Live TV and Home's rows work out of the box.
            // Any of them can be turned off or removed in Settings.
            if (firstRun) {
                mutateConfig { c ->
                    c.copy(sources = SuggestedSources.map { sug ->
                        LiveSource(
                            id = UUID.randomUUID().toString().replace("-", "").take(10),
                            name = sug.name, kind = SourceKind.M3U, url = sug.m3u, epgUrl = sug.epg,
                        )
                    })
                }
            }
            publish()
            while (isActive) {
                refreshStale()
                delay(15 * 60 * 1000L)
            }
        }
    }

    /** Suspends until the on-disk cache has been loaded. */
    suspend fun awaitLoaded(): LiveState {
        init()
        return state.first { it.loaded }
    }

    private fun loadFromDisk() {
        val f = configFile
        if (f.exists()) {
            config = f.inputStream().use { json.decodeFromStream<LiveConfig>(it) }
        }
        val now = System.currentTimeMillis()
        config.sources.forEach { s ->
            val cf = cacheFile(s.id)
            if (cf.exists()) {
                try {
                    val c = cf.inputStream().buffered().use { json.decodeFromStream<SourceCache>(it) }
                    caches[s.id] = c.copy(programmes = c.programmes.mapValues { (_, l) -> l.filter { it.stop > now - EPG_PAST_MS } })
                } catch (e: Exception) {
                    Log.w(TAG, "cache for ${s.name} unreadable", e)
                }
            }
        }
    }

    private fun publish() {
        val cfg = config
        val channels = ArrayList<Channel>()
        val programmes = HashMap<String, List<Programme>>()
        cfg.sources.filter { it.enabled }.forEach { s ->
            val cache = caches[s.id] ?: return@forEach
            cache.channels.forEach { ch ->
                channels.add(ch)
                val key = ch.epgId
                if (key != null) cache.programmes[key]?.let { programmes[ch.id] = it }
            }
        }
        _state.update {
            it.copy(
                loaded = true,
                sources = cfg.sources,
                channels = channels,
                programmes = programmes,
                favorites = cfg.favorites,
                lastChannelId = cfg.lastChannelId,
                previousChannelId = cfg.previousChannelId,
                autoSkip = cfg.autoSkip,
            )
        }
    }

    private suspend fun mutateConfig(block: (LiveConfig) -> LiveConfig) {
        configLock.withLock {
            config = block(config)
            withContext(Dispatchers.IO) { writeAtomic(configFile) { json.encodeToStream(config, it) } }
        }
        publish()
    }

    private fun mutateAsync(block: (LiveConfig) -> LiveConfig) {
        init()
        scope.launch { mutateConfig(block) }
    }

    private inline fun writeAtomic(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().buffered().use(write)
        if (!tmp.renameTo(target)) {
            target.delete(); tmp.renameTo(target)
        }
    }

    // ---- Public mutations -------------------------------------------------------------------

    fun addSource(name: String, kind: SourceKind, url: String, epgUrl: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        val src = LiveSource(
            id = id,
            name = name.trim().ifEmpty { defaultName(kind, cleanUrl) },
            kind = kind,
            url = cleanUrl,
            epgUrl = epgUrl.trim(),
        )
        init()
        scope.launch {
            mutateConfig { c -> c.copy(sources = c.sources + src) }
            refreshSource(id)
        }
    }

    private fun defaultName(kind: SourceKind, url: String) = when (kind) {
        SourceKind.HDHOMERUN -> "HDHomeRun ($url)"
        SourceKind.M3U -> url.substringAfter("://").substringBefore('/').ifEmpty { "Playlist" }
    }

    fun hasSourceUrl(url: String) = config.sources.any { it.url.equals(url.trim(), ignoreCase = true) }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        init()
        scope.launch {
            mutateConfig { c -> c.copy(sources = c.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
            val s = config.sources.firstOrNull { it.id == id }
            if (enabled && s != null && (caches[id] == null || System.currentTimeMillis() - s.lastRefresh > STALE_MS)) refreshSource(id)
        }
    }

    fun removeSource(id: String) {
        init()
        scope.launch {
            mutateConfig { c -> c.copy(sources = c.sources.filterNot { it.id == id }) }
            caches.remove(id)
            cacheFile(id).delete()
            publish()
        }
    }

    fun toggleFavorite(channelId: String) = mutateAsync { c ->
        c.copy(favorites = if (channelId in c.favorites) c.favorites - channelId else c.favorites + channelId)
    }

    fun setAutoSkip(on: Boolean) = mutateAsync { it.copy(autoSkip = on) }

    fun markWatched(channelId: String) = mutateAsync { c ->
        if (c.lastChannelId == channelId) c
        else c.copy(previousChannelId = c.lastChannelId, lastChannelId = channelId)
    }

    fun refreshAll() {
        init()
        scope.launch { config.sources.filter { it.enabled }.forEach { launch { refreshSource(it.id) } } }
    }

    fun refresh(id: String) {
        init()
        scope.launch { refreshSource(id) }
    }

    private suspend fun refreshStale() {
        val now = System.currentTimeMillis()
        config.sources.filter { s ->
            s.enabled && (caches[s.id] == null || now - s.lastRefresh > STALE_MS) && now - s.lastAttempt > RETRY_MS
        }.forEach { refreshSource(it.id) }
    }

    // ---- Refresh ------------------------------------------------------------------------------

    private suspend fun refreshSource(id: String) {
        val lock = refreshLocks.getOrPut(id) { Mutex() }
        if (lock.isLocked) return
        lock.withLock {
            val src = config.sources.firstOrNull { it.id == id } ?: return
            _state.update { it.copy(refreshing = it.refreshing + id) }
            try {
                val (channels, headerEpg) = withContext(Dispatchers.IO) { loadChannels(src) }
                val epgUrl = src.epgUrl.ifBlank { headerEpg.orEmpty() }
                var epgError: String? = null
                var programmes: Map<String, List<Programme>> = caches[id]?.programmes.orEmpty()
                var resolved = channels
                if (epgUrl.isNotBlank()) {
                    try {
                        val now = System.currentTimeMillis()
                        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase() }.toHashSet()
                        val wantedNames = channels.map { XmltvParser.normalizeName(it.name) }.toHashSet()
                        val result = withContext(Dispatchers.IO) {
                            openStream(epgUrl).use { XmltvParser.parse(it, wantedIds, wantedNames, now - EPG_PAST_MS, now + EPG_FUTURE_MS) }
                        }
                        programmes = result.programmes
                        val byName = HashMap<String, String>()
                        result.channelNames.forEach { (xid, names) -> names.forEach { byName.putIfAbsent(it, xid) } }
                        resolved = channels.map { ch ->
                            val tid = ch.tvgId?.lowercase()
                            val epgId = when {
                                tid != null && (tid in programmes || tid in result.channelNames) -> tid
                                else -> byName[XmltvParser.normalizeName(ch.name)] ?: tid
                            }
                            ch.copy(epgId = epgId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "EPG failed for ${src.name}", e)
                        epgError = "Guide data failed: ${e.message ?: e.javaClass.simpleName}"
                        val old = caches[id]?.channels?.associateBy { it.id }.orEmpty()
                        resolved = channels.map { ch -> ch.copy(epgId = old[ch.id]?.epgId ?: ch.tvgId?.lowercase()) }
                    }
                } else {
                    resolved = channels.map { it.copy(epgId = it.tvgId?.lowercase()) }
                }
                val cache = SourceCache(resolved, programmes, System.currentTimeMillis())
                withContext(Dispatchers.IO) { writeAtomic(cacheFile(id)) { json.encodeToStream(cache, it) } }
                caches[id] = cache
                val now = System.currentTimeMillis()
                mutateConfig { c ->
                    c.copy(sources = c.sources.map {
                        if (it.id == id) it.copy(
                            lastRefresh = now, lastAttempt = now, channelCount = resolved.size,
                            programmeCount = programmes.values.sumOf { l -> l.size }, lastError = epgError,
                        ) else it
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed for ${src.name}", e)
                val now = System.currentTimeMillis()
                mutateConfig { c ->
                    c.copy(sources = c.sources.map {
                        if (it.id == id) it.copy(lastAttempt = now, lastError = e.message ?: e.javaClass.simpleName) else it
                    })
                }
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - id) }
            }
        }
    }

    private fun openStream(url: String): java.io.InputStream {
        val req = Request.Builder().url(url).header("User-Agent", "BootTV/1.0 (Android TV)").build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("HTTP ${resp.code} for ${url.substringAfter("://").substringBefore('?')}")
        }
        return resp.body?.byteStream() ?: run { resp.close(); throw IOException("Empty response") }
    }

    /** Returns channels plus an EPG URL announced in the playlist header (if any). */
    private fun loadChannels(src: LiveSource): Pair<List<Channel>, String?> = when (src.kind) {
        SourceKind.M3U -> {
            val mjh = MjhChannels.target(src.url)
            val playlist = try {
                openStream(src.url).use { M3uParser.parse(XmltvParser.maybeGunzip(it)) }
                    .also { if (it.entries.isEmpty()) throw IOException("No channels found in playlist") }
            } catch (e: IOException) {
                // i.mjh.nz retired its pre-built playlists; rebuild from its channel metadata.
                if (mjh == null) throw e
                M3uPlaylist(openStream(mjh.jsonUrl).use { MjhChannels.parse(it, mjh, json) }, emptyList())
            }
            toChannels(src.id, playlist.entries) to playlist.epgUrls.firstOrNull()
        }
        SourceKind.HDHOMERUN -> loadHdHomeRun(src) to null
    }

    private fun toChannels(sourceId: String, entries: List<M3uEntry>): List<Channel> {
        val used = HashSet<String>()
        val list = entries.map { e ->
            val key = e.tvgId ?: (e.name + "|" + e.url)
            var id = sourceId + "-" + shortHash(key)
            var n = 2
            while (!used.add(id)) id = sourceId + "-" + shortHash("$key#${n++}")
            Channel(
                id = id, sourceId = sourceId, name = e.name, number = e.number, logo = e.logo, groups = e.groups,
                url = e.url, tvgId = e.tvgId, userAgent = e.userAgent, headers = e.headers,
            )
        }
        return sortByNumber(list)
    }

    private fun sortByNumber(list: List<Channel>): List<Channel> {
        val withNum = list.withIndex().filter { it.value.number?.toDoubleOrNull() != null }
        if (withNum.isEmpty()) return list
        val sorted = withNum.sortedWith(compareBy({ it.value.number!!.toDouble() }, { it.index })).map { it.value }
        return sorted + list.filter { it.number?.toDoubleOrNull() == null }
    }

    private fun loadHdHomeRun(src: LiveSource): List<Channel> {
        val host = src.url.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val body = openStream("http://$host/lineup.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = json.parseToJsonElement(body).jsonArray
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            val url = o["URL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (o["DRM"]?.jsonPrimitive?.contentOrNull == "1") return@mapNotNull null
            val num = o["GuideNumber"]?.jsonPrimitive?.contentOrNull
            val name = o["GuideName"]?.jsonPrimitive?.contentOrNull ?: num ?: "Channel"
            Channel(
                id = src.id + "-" + shortHash(num ?: url), sourceId = src.id, name = name, number = num,
                groups = listOf("Antenna"), url = url, tvgId = num,
            )
        }.distinctBy { it.id }
        if (list.isEmpty()) throw IOException("Tuner returned no channels (run a channel scan on the HDHomeRun)")
        return sortByNumber(list)
    }
}
