package dev.boottv.core.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.jellyfin.CenteredMessage
import dev.boottv.jellyfin.JfButton
import dev.boottv.jellyfin.formatClock
import kotlinx.coroutines.delay

/**
 * Full-screen playback of a single video with the remote: OK play/pause, ◀ ▶ seek (faster when held), Back to exit.
 * The caller owns [player] (sets the media, releases it). [onCheckpoint] is called every 10 s while playing, to
 * remember where the viewer is.
 */
@Composable
fun VideoPlayer(
    player: ExoPlayer,
    title: String,
    error: String?,
    onExit: () -> Unit,
    onCheckpoint: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
) {
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var overlay by remember { mutableStateOf(true) }
    var poked by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    val root = remember { FocusRequester() }
    val checkpoint by rememberUpdatedState(onCheckpoint)
    val shownError = error ?: playerError

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(e: PlaybackException) { playerError = "This video couldn't be played (${e.errorCodeName})." }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition; duration = player.duration.coerceAtLeast(0L)
            // Remember the spot every 10 s while playing, so a power cut doesn't lose it.
            if (++tick % 20 == 0 && player.isPlaying) checkpoint(position, duration)
            delay(500)
        }
    }
    LaunchedEffect(overlay, poked, playing) { if (overlay && playing) { delay(4000); overlay = false } }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }

    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onExit)
    fun poke() { overlay = true; poked = System.currentTimeMillis() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root)
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                val down = ev.type == KeyEventType.KeyDown
                when (k.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_SPACE,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (down && k.repeatCount == 0) { if (player.isPlaying) player.pause() else player.play(); poke() }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val forward = k.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || k.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        if (down) {
                            val step = if (k.repeatCount < 4) 10_000L else if (k.repeatCount < 12) 30_000L else 60_000L
                            val max = if (duration > 0) duration - 1000 else Long.MAX_VALUE
                            seekTarget = ((seekTarget ?: player.currentPosition) + if (forward) step else -step).coerceIn(0L, max.coerceAtLeast(0L))
                            poke()
                        } else {
                            seekTarget?.let { player.seekTo(it); position = it }
                            seekTarget = null
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) poke(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering && shownError == null) {
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) { LoadingIndicator("Loading…") }
        }
        shownError?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
                CenteredMessage("Can't play this video", msg) { fr ->
                    JfButton("Back", Icons.AutoMirrored.Filled.ArrowBack, primary = true, modifier = Modifier.focusRequester(fr), onClick = onExit)
                }
            }
        }
        AnimatedVisibility(shownError == null && (overlay || seekTarget != null), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            val pos = seekTarget ?: position
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playing) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text(formatClock(pos), color = if (seekTarget != null) BootColors.Focused else Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (duration > 0) Text("-" + formatClock(duration - pos), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp))) {
                        val frac = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(BootColors.Accent, RoundedCornerShape(3.dp)))
                    }
                    Text("OK play/pause · ◀ ▶ seek · Back to exit", color = BootColors.TextSecondary.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}
