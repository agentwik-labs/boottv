package dev.boottv.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.delay

private const val MAX_AUTO_SKIPS = 8

@Composable
fun LivePlayerScreen(nav: NavController, channelId: String) {
    LaunchedEffect(Unit) { LiveTvRepository.init() }
    val state by LiveTvRepository.state.collectAsState()
    val context = LocalContext.current
    val fmt = rememberTimeFormat()

    var currentId by rememberSaveable { mutableStateOf(channelId) }
    val channels = state.channels
    val channel = state.channel(currentId)

    val player = remember { createLivePlayer(context, handleAudioFocus = true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoSkips by remember { mutableIntStateOf(0) }
    var lastDir by remember { mutableIntStateOf(1) }
    var retryTick by remember { mutableIntStateOf(0) }

    var hlsRetried by remember { mutableStateOf<String?>(null) }
    val currentChannelForRetry by androidx.compose.runtime.rememberUpdatedState(channel)

    // Keep the screen awake while watching.
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING || s == Player.STATE_IDLE
                if (s == Player.STATE_READY) { error = null; autoSkips = 0 }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare()
                    return
                }
                // Extension-less URLs are tried as progressive first; retry once as HLS.
                val cur = currentChannelForRetry
                if (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && cur != null && hlsRetried != cur.id) {
                    hlsRetried = cur.id
                    player.tune(cur, forceHls = true)
                    return
                }
                buffering = false
                error = friendlyError(e)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    PlayerLifecycle(player)

    var bannerVisible by remember { mutableStateOf(true) }
    var bannerTick by remember { mutableIntStateOf(0) }
    fun showBanner() { bannerVisible = true; bannerTick++ }
    LaunchedEffect(bannerTick, bannerVisible) {
        if (bannerVisible) { delay(5000); bannerVisible = false }
    }

    LaunchedEffect(channel?.id, retryTick) {
        val ch = channel ?: return@LaunchedEffect
        error = null; buffering = true; hlsRetried = null
        runCatching { player.tune(ch) }.onFailure { error = it.message ?: "Can't play this channel" }
        LiveTvRepository.markWatched(ch.id)
        showBanner()
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.id == currentId }
        val next = if (idx < 0) channels.first() else channels[Math.floorMod(idx + delta, channels.size)]
        lastDir = if (delta >= 0) 1 else -1
        currentId = next.id
    }

    fun recall() {
        val prev = state.previousChannelId
        if (prev != null && prev != currentId && state.channel(prev) != null) currentId = prev
        else showBanner()
    }

    // Auto-skip countdown on errors.
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(error, state.autoSkip) {
        countdown = 0
        if (error == null || !state.autoSkip || autoSkips >= MAX_AUTO_SKIPS || channels.size < 2) return@LaunchedEffect
        for (i in 5 downTo 1) { countdown = i; delay(1000) }
        countdown = 0
        autoSkips++
        zap(lastDir)
    }

    var listOpen by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(listOpen) { if (!listOpen) runCatching { rootFocus.requestFocus() } }
    BackHandler(enabled = listOpen) { listOpen = false }

    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        channels.firstOrNull { it.number == digits }?.let { currentId = it.id }
        digits = ""
    }

    var okDown by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (listOpen) return@onKeyEvent false
                val k = ev.key
                if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                    if (ev.type == KeyEventType.KeyDown) {
                        if (ev.nativeKeyEvent.repeatCount == 0) { okDown = true; longFired = false }
                        else if (okDown && !longFired) {
                            longFired = true
                            channel?.let { LiveTvRepository.toggleFavorite(it.id) }; showBanner()
                        }
                    } else if (ev.type == KeyEventType.KeyUp) {
                        if (okDown && !longFired) { if (bannerVisible) bannerVisible = false else showBanner() }
                        okDown = false; longFired = false
                    }
                    return@onKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val errorShown = error != null
                when (k) {
                    Key.DirectionUp, Key.ChannelUp -> { zap(1); true }
                    Key.DirectionDown, Key.ChannelDown -> { zap(-1); true }
                    Key.DirectionLeft -> if (errorShown) false else { listOpen = true; true }
                    Key.DirectionRight -> if (errorShown) false else { recall(); true }
                    Key.LastChannel, Key.MediaPrevious -> { recall(); true }
                    Key.Info, Key.Guide -> { showBanner(); true }
                    Key.Menu -> { channel?.let { LiveTvRepository.toggleFavorite(it.id) }; showBanner(); true }
                    else -> {
                        val c = ev.nativeKeyEvent.unicodeChar.toChar()
                        if (c in '0'..'9' && digits.length < 5) { digits += c; true } else false
                    }
                }
            }
            .focusable()
    ) {
        LiveVideoSurface(player, Modifier.fillMaxSize())

        when {
            !state.loaded -> CenterNote("Loading…")
            channel == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This channel is no longer available.", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(12.dp))
                val f = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { f.requestFocus() } }
                Button(onClick = { nav.popBackStack() }, modifier = Modifier.focusRequester(f)) { Text("Back to guide") }
            }
            else -> {
                if (buffering && error == null) CenterNote("Tuning ${channel.name}…")
                error?.let { msg ->
                    ErrorPanel(
                        channelName = channel.name,
                        message = msg,
                        countdown = countdown,
                        autoSkip = state.autoSkip,
                        modifier = Modifier.align(Alignment.Center),
                        onRetry = { retryTick++ },
                        onNext = { autoSkips = 0; zap(lastDir) },
                        onToggleAutoSkip = { LiveTvRepository.setAutoSkip(!state.autoSkip) },
                        onGuide = { nav.popBackStack() },
                    )
                }
                if (bannerVisible) {
                    InfoBanner(channel, state, fmt, Modifier.align(Alignment.BottomCenter))
                }
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits, style = MaterialTheme.typography.displayMedium, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(BootDimens.PagePaddingH)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        if (listOpen) {
            ChannelListOverlay(
                state = state,
                currentId = currentId,
                fmt = fmt,
                onPick = { listOpen = false; if (it.id != currentId) { lastDir = 1; currentId = it.id } },
                onClose = { listOpen = false },
            )
        }
    }
}

private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream server refused the request (it may be offline or region-locked)."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't connect to the stream. Check your internet connection."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream's format isn't supported."
    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "This channel is DRM-protected and can't be played here."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode the stream."
    else -> "Playback failed (${e.errorCodeName})."
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text, color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ErrorPanel(
    channelName: String,
    message: String,
    countdown: Int,
    autoSkip: Boolean,
    modifier: Modifier,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onToggleAutoSkip: () -> Unit,
    onGuide: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { first.requestFocus() } }
    Column(
        modifier.width(620.dp).background(BootColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(12.dp)).padding(24.dp),
    ) {
        Text("Can't play $channelName", style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
        if (countdown > 0) {
            Spacer(Modifier.height(6.dp))
            Text("Skipping to the next channel in $countdown…", style = MaterialTheme.typography.bodyMedium, color = BootColors.Accent)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(first)) { Text("Try again") }
            Button(onClick = onNext) { Text("Next channel") }
            Button(onClick = onToggleAutoSkip) { Text(if (autoSkip) "Auto-skip: On" else "Auto-skip: Off") }
            Button(onClick = onGuide) { Text("Guide") }
        }
    }
}

@Composable
private fun InfoBanner(channel: Channel, state: LiveState, fmt: java.text.SimpleDateFormat, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    val (cur, next) = state.nowAndNext(channel.id, now)
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)
            .padding(top = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 120.dp, height = 68.dp).background(BootColors.Surface, RoundedCornerShape(8.dp)).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) AsyncImage(channel.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(channel.number ?: channel.name.take(3), color = BootColors.TextPrimary)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(channel.number, channel.name).joinToString("  "),
                        style = MaterialTheme.typography.titleMedium, color = BootColors.TextSecondary, maxLines = 1,
                    )
                    if (channel.id in state.favorites) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Star, "Favorite", tint = Color(0xFFFFC94D), modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    cur?.title ?: channel.name, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (cur != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.t(cur.start), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        val frac = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(BootColors.Accent, RoundedCornerShape(2.dp)))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(fmt.t(cur.stop), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
                    }
                    cur.desc?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = BootColors.TextPrimary.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("No program information", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
                }
                next?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Next  ${fmt.t(it.start)}  ${it.title}", style = MaterialTheme.typography.bodyMedium,
                        color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "▲▼ change channel   ◀ channel list   ▶ last channel   OK info   Hold OK / Menu: favorite",
                    style = MaterialTheme.typography.labelSmall, color = BootColors.TextSecondary.copy(alpha = 0.7f), maxLines = 1,
                )
            }
            Spacer(Modifier.width(20.dp))
            Text(fmt.t(now), style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.align(Alignment.Top))
        }
    }
}

@Composable
private fun ChannelListOverlay(
    state: LiveState,
    currentId: String,
    fmt: java.text.SimpleDateFormat,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    val channels = state.channels
    val startIdx = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { currentFocus.requestFocus() }
    }
    val now = remember { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))) {
        Column(
            Modifier.width(400.dp).fillMaxHeight()
                .background(BootColors.Surface.copy(alpha = 0.96f))
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown -> { onClose(); true }
                        // Handle Back here: otherwise Compose's Back-key focus exit eats the first press.
                        ev.key == Key.Back || ev.key == Key.Escape -> {
                            if (ev.type == KeyEventType.KeyUp) onClose()
                            true
                        }
                        else -> false
                    }
                }
                .padding(vertical = BootDimens.PagePaddingV)
        ) {
            Text(
                "Channels", style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "OK: watch  ·  Hold OK / Menu: favorite  ·  ▶ / Back: close",
                style = MaterialTheme.typography.labelSmall, color = BootColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                    val (cur, _) = state.nowAndNext(ch.id, now)
                    Surface(
                        onClick = { onPick(ch) },
                        onLongClick = { LiveTvRepository.toggleFavorite(ch.id) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i == startIdx) Modifier.focusRequester(currentFocus) else Modifier)
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Menu) {
                                    LiveTvRepository.toggleFavorite(ch.id); true
                                } else false
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (ch.id == currentId) BootColors.SurfaceRaised else Color.Transparent,
                            contentColor = BootColors.TextPrimary,
                            focusedContainerColor = BootColors.Focused,
                            focusedContentColor = BootColors.OnFocused,
                        ),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ch.number.orEmpty(), style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(44.dp), maxLines = 1,
                            )
                            Box(Modifier.size(width = 48.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                                if (ch.logo != null) AsyncImage(ch.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    cur?.let { "${it.title} · until ${fmt.t(it.stop)}" } ?: "No program information",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (ch.id in state.favorites) {
                                Icon(Icons.Filled.Star, "Favorite", tint = Color(0xFFFFC94D), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
