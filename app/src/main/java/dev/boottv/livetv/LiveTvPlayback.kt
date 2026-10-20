package dev.boottv.livetv

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** Creates a player tuned for live streams. */
@OptIn(UnstableApi::class)
fun createLivePlayer(context: Context, handleAudioFocus: Boolean): ExoPlayer =
    ExoPlayer.Builder(context)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                handleAudioFocus,
            )
            playWhenReady = true
        }

/** Tunes [player] to [channel], applying its custom HTTP headers / user agent. */
@OptIn(UnstableApi::class)
fun ExoPlayer.tune(channel: Channel, forceHls: Boolean = false) {
    val ds = DefaultHttpDataSource.Factory()
        .setUserAgent(channel.userAgent ?: DEFAULT_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setDefaultRequestProperties(channel.headers)
    val lower = channel.url.lowercase().substringBefore('?')
    val builder = MediaItem.Builder().setUri(channel.url).setMediaId(channel.id)
    when {
        forceHls || lower.contains("://jmp2.uk/") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.contains("/hls/") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    val source = DefaultMediaSourceFactory(ds).createMediaSource(builder.build())
    setMediaSource(source)
    prepare()
    playWhenReady = true
}

/** Pauses on ON_STOP and resumes live (jumping to the live edge) on ON_START. */
@Composable
fun PlayerLifecycle(player: ExoPlayer) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) {
                    player.seekToDefaultPosition(); player.prepare(); player.play()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** Non-focusable video surface (never steals D-pad focus from Compose). */
@OptIn(UnstableApi::class)
@Composable
fun LiveVideoSurface(player: ExoPlayer, modifier: Modifier = Modifier, fill: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // [fill] crops to the frame (Home's preview); otherwise the whole picture, letterboxed.
                resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(false)
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

@Composable
fun rememberTimeFormat(): SimpleDateFormat {
    val ctx = LocalContext.current
    return remember(ctx) {
        val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
        SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
    }
}

fun SimpleDateFormat.t(ms: Long): String = format(Date(ms))

fun SimpleDateFormat.range(p: Programme): String = "${t(p.start)} – ${t(p.stop)}"

fun minutesLeft(p: Programme, now: Long): String {
    val m = ((p.stop - now) / 60_000L).coerceAtLeast(0)
    return when {
        now < p.start -> "Starts in ${((p.start - now) / 60_000L).coerceAtLeast(1)} min"
        m >= 60 -> "${m / 60}h ${m % 60}m left"
        else -> "$m min left"
    }
}

fun relativeAgo(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0) return "never"
    val m = (now - ts) / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}
