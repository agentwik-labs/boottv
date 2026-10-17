package dev.boottv.jellyfin

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One resolved stream for an item. */
internal data class PlaybackSource(
    val url: String,
    val hls: Boolean,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val playMethod: String,
    val streams: List<MediaStream>,
    val sideloadedSubs: List<SideSub>,
)

internal data class SideSub(val streamIndex: Int, val url: String, val mime: String, val language: String?, val label: String) {
    val trackId get() = "jfsub-$streamIndex"
}

/** A choice in the audio / subtitle dialog. index = Jellyfin stream index, -1 = off. */
internal data class TrackOption(val index: Int, val label: String, val selected: Boolean)

/**
 * Owns the ExoPlayer and the Jellyfin playback session for the player screen:
 * resolves direct play vs. HLS transcode, track selection and progress reporting.
 */
@OptIn(UnstableApi::class)
internal class PlayerController(
    context: Context,
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
) {
    val player: ExoPlayer

    var item by mutableStateOf<BaseItem?>(null); private set
    var source by mutableStateOf<PlaybackSource?>(null); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set
    var nextEpisode by mutableStateOf<BaseItem?>(null); private set
    var tracksVersion by mutableIntStateOf(0); private set

    /** Selected Jellyfin stream indexes. subtitle -1 = off. */
    var audioIndex: Int? = null; private set
    var subtitleIndex: Int = -1; private set

    private var transcodeFallbackTried = false
    private var pendingTrackApply = false
    private var loadJob: Job? = null

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BootTV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(mapOf("Authorization" to JfHttp.authHeader(client.session.deviceId, client.session.token)))
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                reportProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) loading = false
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (pendingTrackApply && !tracks.isEmpty) {
                    pendingTrackApply = false
                    applyTrackSelection()
                }
                tracksVersion++
            }

            override fun onPlayerError(e: PlaybackException) {
                val src = source
                val current = item
                if (src != null && current != null && src.playMethod != "Transcode" && !transcodeFallbackTried) {
                    // The device couldn't decode the file directly: ask the server to transcode it instead.
                    transcodeFallbackTried = true
                    load(current.id, player.currentPosition.coerceAtLeast(0) * TICKS_PER_MS, subtitle = subtitleIndex, forceTranscode = true)
                } else {
                    error = "Playback failed: ${e.errorCodeName}"
                    loading = false
                }
            }
        })
    }

    val positionMs get() = player.currentPosition.coerceAtLeast(0)
    val durationMs: Long
        get() {
            val d = player.duration
            return if (d == C.TIME_UNSET || d <= 0) (item?.runTimeTicks ?: 0) / TICKS_PER_MS else d
        }

    fun load(
        itemId: String,
        startTicks: Long,
        audio: Int? = audioIndex,
        subtitle: Int? = null,
        forceTranscode: Boolean = false,
        resetTracks: Boolean = false,
    ) {
        loadJob?.cancel()
        val previous = source
        val previousItem = item
        loadJob = scope.launch {
            loading = true; error = null; ended = false
            if (previous != null && previousItem != null && previousItem.id != itemId) {
                stopReport(previousItem.id, previous, positionMs * TICKS_PER_MS)
            }
            try {
                val it = if (previousItem?.id == itemId) previousItem else client.item(itemId)
                if (previousItem?.id != itemId) {
                    item = it
                    nextEpisode = null
                    transcodeFallbackTried = forceTranscode
                    if (resetTracks) { audioIndex = null; subtitleIndex = -1 }
                }
                val requestSub = subtitle?.takeIf { s -> s >= 0 }
                val info = client.playbackInfo(itemId, audio, requestSub, allowDirect = !forceTranscode)
                val ms = info.mediaSources.firstOrNull() ?: throw IllegalStateException("No playable media source")
                val src = buildSource(itemId, info, ms, forceTranscode)
                if (previous != null && previousItem?.id == itemId && previous.playMethod == "Transcode") {
                    // Switching streams of the same item: end the old transcode.
                    val old = previous
                    JfHttp.backgroundScope.launch { client.reportStopped(itemId, old.mediaSourceId, old.playSessionId, startTicks, true) }
                }
                source = src
                audioIndex = audio ?: ms.defaultAudioStreamIndex
                subtitleIndex = subtitle ?: (ms.defaultSubtitleStreamIndex
                    ?.takeIf { idx -> ms.mediaStreams.any { s -> s.index == idx && s.deliveryMethod != "Encode" } } ?: -1)

                val mediaItem = MediaItem.Builder()
                    .setUri(src.url)
                    .setMediaId(itemId)
                    .apply { if (src.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .setSubtitleConfigurations(src.sideloadedSubs.map { sub ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                            .setId(sub.trackId)
                            .setMimeType(sub.mime)
                            .setLanguage(sub.language)
                            .setLabel(sub.label)
                            .build()
                    })
                    .build()
                pendingTrackApply = true
                player.setMediaItem(mediaItem, startTicks / TICKS_PER_MS)
                player.prepare()
                player.playWhenReady = true
                client.reportStart(itemId, src.mediaSourceId, src.playSessionId, startTicks, src.playMethod, audioIndex, subtitleIndex)
                if (it.isEpisode && nextEpisode == null) {
                    nextEpisode = runCatching { client.nextEpisode(it) }.getOrNull()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly(client.server)
                loading = false
            }
        }
    }

    private fun buildSource(itemId: String, info: PlaybackInfoResponse, ms: MediaSourceInfo, forceTranscode: Boolean): PlaybackSource {
        val token = client.session.token
        val server = client.server
        val direct = !forceTranscode && (ms.supportsDirectPlay || ms.supportsDirectStream)
        val (url, hls, method) = if (direct || ms.transcodingUrl == null) {
            val q = JfHttp.query(
                "static" to true,
                "MediaSourceId" to ms.id,
                "PlaySessionId" to info.playSessionId,
                "DeviceId" to client.session.deviceId,
                "api_key" to token,
            )
            Triple("$server/Videos/$itemId/stream?$q", false, if (ms.supportsDirectPlay) "DirectPlay" else "DirectStream")
        } else {
            val t = ms.transcodingUrl
            val full = if (t.startsWith("http")) t else server + t
            val withKey = if ("api_key=" in full) full else full + (if ('?' in full) "&" else "?") + "api_key=$token"
            Triple(withKey, ms.transcodingSubProtocol.equals("hls", true) || ".m3u8" in t, "Transcode")
        }
        val subs = ms.mediaStreams.filter { it.type == "Subtitle" && it.deliveryMethod == "External" }.map { s ->
            val path = s.deliveryUrl ?: "/Videos/$itemId/${ms.id}/Subtitles/${s.index}/0/Stream.${subtitleExt(s.codec)}"
            val full = if (path.startsWith("http")) path else server + path
            val withKey = if ("api_key=" in full) full else full + (if ('?' in full) "&" else "?") + "api_key=$token"
            SideSub(s.index, withKey, subtitleMime(withKey, s.codec), s.language, streamLabel(s))
        }
        return PlaybackSource(url, hls, ms.id, info.playSessionId, method, ms.mediaStreams, subs)
    }

    private fun subtitleExt(codec: String?) = when (codec?.lowercase()) {
        "ass", "ssa" -> "ass"
        "vtt", "webvtt" -> "vtt"
        else -> "srt"
    }

    private fun subtitleMime(url: String, codec: String?): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            codec.equals("ass", true) || codec.equals("ssa", true) -> MimeTypes.TEXT_SSA
            codec.equals("vtt", true) || codec.equals("webvtt", true) -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    // ------------------------------------------------------------------ tracks

    fun audioOptions(): List<TrackOption> {
        val streams = source?.streams.orEmpty().filter { it.type == "Audio" }
        return streams.map { TrackOption(it.index, streamLabel(it), it.index == audioIndex) }
    }

    fun subtitleOptions(): List<TrackOption> {
        val streams = source?.streams.orEmpty().filter { it.type == "Subtitle" }
        return listOf(TrackOption(-1, "Off", subtitleIndex < 0)) +
            streams.map { TrackOption(it.index, streamLabel(it), it.index == subtitleIndex) }
    }

    fun selectAudio(index: Int) {
        val src = source ?: return
        val it = item ?: return
        if (index == audioIndex) return
        audioIndex = index
        if (src.playMethod == "Transcode") {
            load(it.id, positionMs * TICKS_PER_MS, audio = index, subtitle = subtitleIndex, forceTranscode = transcodeFallbackTried)
        } else {
            applyTrackSelection()
        }
        reportProgress()
    }

    fun selectSubtitle(index: Int) {
        val src = source ?: return
        val it = item ?: return
        if (index == subtitleIndex) return
        val wasBurnedIn = src.streams.firstOrNull { s -> s.index == subtitleIndex }?.deliveryMethod == "Encode"
        val needsBurnIn = index >= 0 && src.streams.firstOrNull { s -> s.index == index }?.let { s ->
            s.deliveryMethod == "Encode" || (src.playMethod == "Transcode" && src.sideloadedSubs.none { sub -> sub.streamIndex == index } && s.deliveryMethod != "Hls")
        } == true
        subtitleIndex = index
        if (needsBurnIn || wasBurnedIn) {
            // Image subtitles (PGS, DVD) are burned in by the server, which needs a new stream.
            load(it.id, positionMs * TICKS_PER_MS, audio = audioIndex, subtitle = index, forceTranscode = transcodeFallbackTried)
        } else {
            applyTrackSelection()
        }
        reportProgress()
    }

    private fun applyTrackSelection() {
        val src = source ?: return
        val tracks = player.currentTracks
        val builder = player.trackSelectionParameters.buildUpon()

        // Audio: in direct play the n-th Jellyfin audio stream maps to the n-th audio group in the file.
        if (src.playMethod != "Transcode") {
            val jfAudio = src.streams.filter { it.type == "Audio" && !it.isExternal }
            val pos = jfAudio.indexOfFirst { it.index == audioIndex }
            val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val group = groups.getOrNull(pos)
                ?: jfAudio.getOrNull(pos)?.language?.let { lang -> groups.firstOrNull { g -> g.getTrackFormat(0).language?.startsWith(lang.take(2)) == true } }
            if (group != null && group.length > 0) {
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }

        // Subtitles.
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (subtitleIndex < 0) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val side = src.sideloadedSubs.firstOrNull { it.streamIndex == subtitleIndex }
            var target = side?.let { s ->
                textGroups.firstOrNull { g -> (0 until g.length).any { i -> g.getTrackFormat(i).id?.contains(s.trackId) == true } }
            }
            if (target == null && src.playMethod != "Transcode") {
                // Embedded text subtitle in a directly played file: match by order among embedded streams.
                val embedded = src.streams.filter { it.type == "Subtitle" && !it.isExternal }
                val pos = embedded.indexOfFirst { it.index == subtitleIndex }
                val fileGroups = textGroups.filter { g -> g.getTrackFormat(0).id?.contains("jfsub-") != true }
                target = fileGroups.getOrNull(pos)
            }
            if (target == null && src.playMethod == "Transcode") {
                // HLS-delivered subtitle: pick the first non-sideloaded text track.
                target = textGroups.firstOrNull { g -> g.getTrackFormat(0).id?.contains("jfsub-") != true }
            }
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (target != null) {
                builder.setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
            } else {
                // Burned-in subtitle: nothing to render client-side.
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, src.streams.firstOrNull { it.index == subtitleIndex }?.deliveryMethod == "Encode")
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    // --------------------------------------------------------------- reporting

    fun reportProgress() {
        val it = item ?: return
        val src = source ?: return
        val pos = positionMs * TICKS_PER_MS
        val paused = !player.isPlaying
        val audio = audioIndex
        val sub = subtitleIndex
        scope.launch { client.reportProgress(it.id, src.mediaSourceId, src.playSessionId, pos, paused, src.playMethod, audio, sub) }
    }

    private fun stopReport(itemId: String, src: PlaybackSource, positionTicks: Long) {
        JfHttp.backgroundScope.launch {
            client.reportStopped(itemId, src.mediaSourceId, src.playSessionId, positionTicks, src.playMethod == "Transcode")
        }
    }

    /** Plays the next episode immediately. */
    fun playNext() {
        val next = nextEpisode ?: return
        load(next.id, 0, audio = null, subtitle = null, resetTracks = true)
    }

    fun release() {
        loadJob?.cancel()
        val it = item
        val src = source
        if (it != null && src != null) {
            val ticks = if (ended) (durationMs * TICKS_PER_MS) else positionMs * TICKS_PER_MS
            stopReport(it.id, src, ticks)
        }
        player.release()
    }
}

internal fun streamLabel(s: MediaStream): String {
    val base = s.displayTitle ?: listOfNotNull(s.language?.uppercase(), s.codec?.uppercase()).joinToString(" ").ifBlank { "Track ${s.index}" }
    val extras = listOfNotNull(
        if (s.isForced && "forced" !in base.lowercase()) "Forced" else null,
        if (s.isExternal && "external" !in base.lowercase()) "External" else null,
    )
    return if (extras.isEmpty()) base else "$base (${extras.joinToString(", ")})"
}
