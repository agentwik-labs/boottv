package dev.boottv.jellyfin

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(nav: NavController, id: String, startTicks: Long) {
    var client by remember { mutableStateOf<JellyfinClient?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        client = JellyfinClient.current()
        missing = client == null
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val c = client
        when {
            missing -> CenteredMessage("Not signed in", "Connect to your media server in Settings first.") { fr ->
                JfButton("Back", null, primary = true, onClick = { nav.popBackStack() }, modifier = Modifier.focusRequester(fr))
            }
            c != null -> PlayerContent(nav, c, id, startTicks)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(nav: NavController, client: JellyfinClient, id: String, startTicks: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { PlayerController(context, client, scope) }

    DisposableEffect(Unit) {
        controller.load(id, startTicks)
        onDispose { controller.release() }
    }

    // Keep the screen awake while the player is open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Pause when the app goes to the background (e.g. Home pressed).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                controller.player.pause()
                controller.reportProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var overlay by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var showTracks by remember { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var nextDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(10) }

    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }

    fun poke() { overlay = true; lastInteraction = System.currentTimeMillis() }
    fun focusRoot() { controlsFocused = false; runCatching { rootFocus.requestFocus() } }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Position polling.
    LaunchedEffect(controller) {
        while (true) {
            position = controller.positionMs
            duration = controller.durationMs
            delay(500)
        }
    }
    // Progress report every 10s.
    LaunchedEffect(controller) {
        while (true) {
            delay(10_000)
            if (controller.isPlaying) controller.reportProgress()
        }
    }
    // Auto-hide overlay.
    LaunchedEffect(overlay, lastInteraction, controller.isPlaying, controlsFocused) {
        if (overlay && controller.isPlaying && !controlsFocused) {
            delay(5000)
            overlay = false
        }
    }
    // New item (next episode): reset per-item UI state.
    LaunchedEffect(controller.item?.id) {
        nextDismissed = false
        poke()
    }

    val next = controller.nextEpisode
    val remaining = duration - position
    val showNextCard = next != null && !nextDismissed && duration > 0 && (controller.ended || remaining in 1..20_000) && controller.error == null

    LaunchedEffect(showNextCard, next?.id) {
        if (showNextCard) {
            controlsFocused = false
            delay(100)
            runCatching { nextFocus.requestFocus() }
            countdown = 10
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            controller.playNext()
            focusRoot()
        }
    }
    // Finished with nothing queued: leave the player.
    LaunchedEffect(controller.ended, showNextCard) {
        if (controller.ended && !showNextCard) {
            delay(1500)
            if (controller.ended) nav.popBackStack()
        }
    }

    BackHandler {
        when {
            showTracks -> { showTracks = false; focusRoot() }
            showNextCard -> { nextDismissed = true; focusRoot() }
            else -> nav.popBackStack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (showTracks || controlsFocused || showNextCard) return@onPreviewKeyEvent false
                val native = ev.nativeKeyEvent
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        if (down && native.repeatCount == 0) {
                            if (controller.player.isPlaying) controller.player.pause() else controller.player.play()
                            poke()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) { controller.player.play(); poke() }; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) { controller.player.pause(); poke() }; true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val forward = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        if (down) {
                            val step = when {
                                native.repeatCount < 4 -> 10_000L
                                native.repeatCount < 12 -> 30_000L
                                else -> 60_000L
                            }
                            val base = seekTarget ?: controller.positionMs
                            val max = if (duration > 0) duration - 1000 else Long.MAX_VALUE
                            seekTarget = (base + if (forward) step else -step).coerceIn(0L, max.coerceAtLeast(0L))
                            poke()
                        } else if (up) {
                            seekTarget?.let { controller.player.seekTo(it); position = it }
                            seekTarget = null
                            controller.reportProgress()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down) poke(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) {
                            if (overlay) {
                                controlsFocused = true
                                poke()
                                runCatching { controlsFocus.requestFocus() }.onFailure { controlsFocused = false }
                            } else poke()
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                        if (down) showTracks = true
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && next != null) controller.playNext(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    player = controller.player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if ((controller.loading || controller.buffering) && controller.error == null) {
            Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text(if (controller.loading) "Loading…" else "Buffering…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        controller.error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
                CenteredMessage("Can't play this video", msg) { fr ->
                    JfButton("Try again", Icons.Filled.Replay, primary = true, modifier = Modifier.focusRequester(fr), onClick = {
                        val it = controller.item
                        controller.load(it?.id ?: id, controller.positionMs * TICKS_PER_MS, forceTranscode = true)
                        focusRoot()
                    })
                    JfButton("Back", null, onClick = { nav.popBackStack() })
                }
            }
        }

        AnimatedVisibility(overlay || seekTarget != null || controlsFocused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            ControlsOverlay(
                controller = controller,
                position = seekTarget ?: position,
                duration = duration,
                seeking = seekTarget != null,
                controlsFocus = controlsFocus,
                onUpFromControls = { focusRoot(); poke() },
                onInteract = { poke() },
                onTracks = { showTracks = true },
            )
        }

        if (showNextCard) next?.let { next ->
            NextUpCard(
                next = next,
                client = client,
                countdown = countdown,
                focusRequester = nextFocus,
                onPlay = { controller.playNext(); focusRoot() },
                onCancel = { nextDismissed = true; focusRoot() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = BootDimens.PagePaddingH, bottom = BootDimens.PagePaddingV + 120.dp),
            )
        }

        if (showTracks) {
            TracksDialog(
                controller = controller,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun ControlsOverlay(
    controller: PlayerController,
    position: Long,
    duration: Long,
    seeking: Boolean,
    controlsFocus: FocusRequester,
    onUpFromControls: () -> Unit,
    onInteract: () -> Unit,
    onTracks: () -> Unit,
) {
    val item = controller.item
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))

        Column(Modifier.align(Alignment.TopStart).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
            if (item != null) {
                Text(item.cardTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                if (item.isEpisode) {
                    Text(listOfNotNull(item.episodeLabel, item.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = BootColors.TextSecondary)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (controller.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(formatClock(position), color = if (seeking) BootColors.Accent else Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    val endsAt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(System.currentTimeMillis() + (duration - position).coerceAtLeast(0)))
                    Text("Ends at $endsAt   ", color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("-" + formatClock(duration - position), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp))) {
                val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(BootColors.Accent, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown) onInteract()
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                        onUpFromControls(); true
                    } else false
                },
            ) {
                JfButton(if (controller.isPlaying) "Pause" else "Play", if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    modifier = Modifier.focusRequester(controlsFocus),
                    onClick = { if (controller.player.isPlaying) controller.player.pause() else controller.player.play() })
                JfButton("Restart", Icons.Filled.Replay, onClick = { controller.player.seekTo(0); controller.reportProgress() })
                JfButton("Audio & Subtitles", Icons.Filled.Subtitles, onClick = onTracks)
                if (controller.nextEpisode != null) {
                    JfButton("Next Episode", Icons.Filled.SkipNext, onClick = { controller.playNext(); onUpFromControls() })
                }
            }
            Text("OK play/pause · ◀ ▶ seek · ▼ controls · Menu audio & subtitles", color = BootColors.TextSecondary.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun NextUpCard(
    next: BaseItem,
    client: JellyfinClient,
    countdown: Int,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.width(560.dp).background(Color(0xE6161624), RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        JfImage(client.wideUrl(next), Modifier.width(200.dp).aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text("Next episode in $countdown", color = BootColors.Accent, style = MaterialTheme.typography.labelLarge)
            Text(listOfNotNull(next.episodeLabel, next.name).joinToString(" · "), color = Color.White,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JfButton("Play now", Icons.Filled.SkipNext, primary = true, modifier = Modifier.focusRequester(focusRequester), onClick = onPlay)
                JfButton("Cancel", null, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun TracksDialog(controller: PlayerController, modifier: Modifier = Modifier) {
    // Re-read options whenever tracks change. Back closes the panel (handled by the screen's BackHandler).
    val version = controller.tracksVersion
    var refresh by remember { mutableIntStateOf(0) }
    val audio = remember(refresh, version, controller.source) { controller.audioOptions() }
    val subs = remember(refresh, version, controller.source) { controller.subtitleOptions() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { firstFocus.requestFocus() } }

    Row(
        modifier.fillMaxHeight().width(720.dp).background(Color(0xF00B0B14)).padding(horizontal = 24.dp, vertical = BootDimens.PagePaddingV),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TrackColumn("Audio", audio, Modifier.weight(1f), firstFocus.takeIf { audio.isNotEmpty() }) { opt ->
            controller.selectAudio(opt.index); refresh++
        }
        TrackColumn("Subtitles", subs, Modifier.weight(1f), firstFocus.takeIf { audio.isEmpty() }) { opt ->
            controller.selectSubtitle(opt.index); refresh++
        }
    }
}

@Composable
private fun TrackColumn(title: String, options: List<TrackOption>, modifier: Modifier, focus: FocusRequester?, onSelect: (TrackOption) -> Unit) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (options.isEmpty()) {
            Text("None available", color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        val selectedIdx = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(options.size) { i ->
                val opt = options[i]
                Surface(
                    onClick = { onSelect(opt) },
                    modifier = Modifier.fillMaxWidth().let { if (focus != null && i == selectedIdx) it.focusRequester(focus) else it },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (opt.selected) BootColors.AccentSoft else BootColors.Surface,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp)) {
                            if (opt.selected) Icon(Icons.Filled.Check, null)
                        }
                        Text(opt.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
