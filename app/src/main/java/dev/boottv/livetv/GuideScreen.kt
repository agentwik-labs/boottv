package dev.boottv.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.delay
import java.util.TimeZone

internal const val SLOT_MS = 30 * 60_000L
private const val WINDOW_MS = 4 * SLOT_MS
private const val NAV_RANGE_MS = 24 * 60 * 60_000L
private const val FILTER_ALL = "\u0000all"
private const val FILTER_FAV = "\u0000fav"
private const val FILTER_TOONS = "\u0000toons"

private val ChannelColWidth = 190.dp
private val RowHeight = 50.dp

/** Survives navigation to the player and back (process lifetime). */
private object GuideMemory {
    var filter: String = FILTER_ALL
    var channelId: String? = null
}

internal fun floorSlot(t: Long): Long {
    val off = TimeZone.getDefault().getOffset(t)
    return Math.floorDiv(t + off, SLOT_MS) * SLOT_MS - off
}

internal data class Block(val start: Long, val end: Long, val programme: Programme?)

/** Programmes covering [from, to) with gaps filled by "no info" blocks. Real blocks keep real bounds. */
internal fun buildBlocks(list: List<Programme>, from: Long, to: Long): List<Block> {
    val out = ArrayList<Block>()
    var cursor = from
    for (p in list) {
        if (p.stop <= cursor) continue
        if (p.start >= to) break
        if (p.start > cursor) out.add(Block(cursor, p.start, null))
        out.add(Block(p.start, p.stop, p))
        cursor = p.stop
    }
    if (cursor < to) out.add(Block(cursor, to, null))
    return out
}

@Composable
fun GuideScreen(nav: NavController) {
    LaunchedEffect(Unit) { LiveTvRepository.init() }
    val state by LiveTvRepository.state.collectAsState()
    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        when {
            !state.loaded -> CenterMessage("Loading Live TV…")
            state.sources.isEmpty() -> NoSources(nav)
            state.channels.isEmpty() -> NoChannels(state, nav)
            else -> Guide(state, nav)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextSecondary)
    }
}

@Composable
private fun NoSources(nav: NavController) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Live TV", style = MaterialTheme.typography.displaySmall, color = BootColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Watch free, legal live channels. Add a source to build your channel guide — " +
                "you can add more (or your own antenna tuner) later in Settings.",
            style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        SuggestedSources.forEachIndexed { i, s ->
            Button(
                onClick = { LiveTvRepository.addSource(s.name, SourceKind.M3U, s.m3u, s.epg) },
                modifier = Modifier.padding(vertical = 4.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            ) { Text("Add ${s.name}") }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Custom playlist or HDHomeRun… (Settings)") }
    }
}

@Composable
private fun NoChannels(state: LiveState, nav: NavController) {
    val busy = state.refreshing.isNotEmpty()
    val first = remember { FocusRequester() }
    LaunchedEffect(busy) { if (!busy) runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV),
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            Text("Loading channels…", style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloading playlists and the program guide. Large guides can take a minute the first time.",
                style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary,
            )
        } else {
            Text("No channels yet", style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            val enabled = state.sources.filter { it.enabled }
            if (enabled.isEmpty()) {
                Text("All sources are turned off. Enable one in Settings.", color = BootColors.TextSecondary)
            }
            enabled.forEach { s ->
                Text(
                    "${s.name}: ${s.lastError ?: "no channels"}",
                    style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary, maxLines = 2,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { LiveTvRepository.refreshAll() }, modifier = Modifier.focusRequester(first)) { Text("Try again") }
                Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Settings") }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun Guide(state: LiveState, nav: NavController) {
    val fmt = rememberTimeFormat()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000); now = System.currentTimeMillis()
        }
    }

    var filter by remember {
        mutableStateOf(GuideMemory.filter.takeIf { it == FILTER_ALL || it == FILTER_FAV || it == FILTER_TOONS || it in state.groups } ?: FILTER_ALL)
    }
    val filtered = remember(state.channels, state.favorites, filter) {
        when (filter) {
            FILTER_ALL -> state.channels
            FILTER_FAV -> state.channels.filter { it.id in state.favorites }
            FILTER_TOONS -> state.animationChannels().toSet().let { toons -> state.channels.filter { it in toons } }
            else -> state.channels.filter { filter in it.groups }
        }
    }
    val initialRow = remember {
        val want = state.lastChannelId ?: GuideMemory.channelId
        filtered.indexOfFirst { it.id == want }.coerceAtLeast(0)
    }
    var rowState by remember { mutableIntStateOf(initialRow) }
    val row = rowState.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    var windowStart by remember { mutableLongStateOf(floorSlot(now)) }
    var focusTime by remember { mutableLongStateOf(now) }

    val minTime = floorSlot(now)
    val maxTime = minTime + NAV_RANGE_MS
    val ws = windowStart.coerceIn(minTime, maxTime - WINDOW_MS)
    val ft = focusTime.coerceIn(ws, ws + WINDOW_MS - 1)

    val selected = filtered.getOrNull(row)
    LaunchedEffect(selected?.id) { selected?.let { GuideMemory.channelId = it.id } }
    val selectedBlock = selected?.let { ch ->
        buildBlocks(state.programmesFor(ch.id), minTime, maxTime).firstOrNull { ft >= it.start && ft < it.end }
    }

    val gridFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialRow - 1).coerceAtLeast(0))
    var visibleRows by remember { mutableIntStateOf(5) }
    // The grid can appear after a cold-start load while focus sits elsewhere (e.g. the side menu):
    // keep asking until it is actually attached and focused.
    LaunchedEffect(Unit) {
        repeat(20) {
            if (gridFocused) return@LaunchedEffect
            runCatching { gridFocus.requestFocus() }
            delay(100)
        }
    }

    // Keep the selected row on screen.
    LaunchedEffect(row, filtered) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) {
            if (filtered.isNotEmpty()) listState.scrollToItem(row)
            return@LaunchedEffect
        }
        val end = info.viewportEndOffset
        val fully = visible.filter { it.offset >= 0 && it.offset + it.size <= end }
        if (fully.isNotEmpty()) visibleRows = fully.size
        val first = fully.firstOrNull()?.index ?: visible.first().index
        val last = fully.lastOrNull()?.index ?: visible.last().index
        if (row < first) listState.scrollToItem(row)
        else if (row > last) listState.scrollToItem((row - (visibleRows - 1)).coerceAtLeast(0))
    }

    // Number entry ("1","0","4" -> channel 104).
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val target = filtered.indexOfFirst { it.number == digits }
            .takeIf { it >= 0 } ?: filtered.indexOfFirst { (it.number?.toDoubleOrNull() ?: -1.0) >= digits.toDouble() }
        if (target >= 0) rowState = target
        digits = ""
    }

    // ---- Muted live preview after ~1s dwell ----
    val context = LocalContext.current
    val preview = remember { createLivePlayer(context, handleAudioFocus = false).apply { volume = 0f } }
    var previewChannel by remember { mutableStateOf<Channel?>(null) }
    var previewReady by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf(false) }
    DisposableEffect(preview) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                previewReady = s == Player.STATE_READY || (previewReady && s == Player.STATE_BUFFERING)
            }
            override fun onPlayerError(error: PlaybackException) {
                previewError = true; previewReady = false
            }
        }
        preview.addListener(l)
        onDispose { preview.removeListener(l); preview.release() }
    }
    PlayerLifecycle(preview)
    LaunchedEffect(selected?.id, gridFocused) {
        if (!gridFocused || selected == null || selected.id == previewChannel?.id) return@LaunchedEffect
        delay(1000)
        previewChannel = selected
    }
    LaunchedEffect(previewChannel?.id) {
        val ch = previewChannel ?: return@LaunchedEffect
        previewReady = false; previewError = false
        runCatching { preview.tune(ch) }.onFailure { previewError = true }
    }

    // ---- Key handling helpers ----
    fun selectRow(r: Int) { rowState = r.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)) }

    fun moveHorizontal(dir: Int): Boolean {
        val ch = selected ?: return false
        val blocks = buildBlocks(state.programmesFor(ch.id), minTime, maxTime)
        val cur = blocks.firstOrNull { ft >= it.start && ft < it.end } ?: return false
        val we = ws + WINDOW_MS
        if (dir > 0) {
            if (cur.end > we) {
                if (ws + WINDOW_MS >= maxTime) return true
                val nws = ws + SLOT_MS
                windowStart = nws; focusTime = maxOf(ft, nws)
                return true
            }
            val next = blocks.firstOrNull { it.start >= cur.end } ?: return true
            focusTime = next.start
            windowStart = if (next.start >= we) floorSlot(next.start) else ws
            return true
        } else {
            if (cur.start < ws) {
                if (ws <= minTime) return false
                val nws = maxOf(minTime, ws - SLOT_MS)
                windowStart = nws; focusTime = maxOf(cur.start, nws)
                return true
            }
            if (cur.start <= minTime) return false
            val prev = blocks.lastOrNull { it.end <= cur.start } ?: return false
            val f = maxOf(prev.start, minTime)
            val nws = if (f < ws) maxOf(minTime, floorSlot(f)) else ws
            windowStart = nws; focusTime = maxOf(f, nws)
            return true
        }
    }

    fun play(ch: Channel) {
        GuideMemory.channelId = ch.id
        nav.navigate("live/play/${ch.id}")
    }

    var okDown by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }

    val hasToons = remember(state.channels) { state.animationChannels().isNotEmpty() }
    val chipsRow = remember(state.groups, hasToons) { listOfNotNull(FILTER_ALL, FILTER_FAV, FILTER_TOONS.takeIf { hasToons }) + state.groups }

    Column(
        Modifier.fillMaxSize().padding(
            start = BootDimens.PagePaddingH, end = BootDimens.PagePaddingH,
            top = BootDimens.PagePaddingV, bottom = 12.dp,
        )
    ) {
        // ---- Header: details + preview ----
        Row(Modifier.fillMaxWidth().height(140.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 20.dp)) {
                val p = selectedBlock?.programme
                val ch = selected
                if (ch != null) {
                    Text(
                        listOfNotNull(ch.number, ch.name).joinToString("  ") +
                            if (ch.id in state.favorites) "  ★" else "",
                        style = MaterialTheme.typography.labelLarge, color = BootColors.TextSecondary, maxLines = 1,
                    )
                }
                Text(
                    p?.title ?: ch?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                )
                if (p != null) {
                    val live = p.start <= now && p.stop > now
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (live) {
                            Text(
                                "LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(BootColors.Live, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            listOfNotNull(fmt.range(p), minutesLeft(p, now), p.subtitle, p.category).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    p.desc?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } else if (ch != null) {
                    Text("No program information", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                val hint = buildString {
                    append("OK: watch   ·   Hold OK / Menu: favorite")
                    if (state.refreshing.isNotEmpty()) append("   ·   Updating guide…")
                }
                Text(hint, style = MaterialTheme.typography.labelSmall, color = BootColors.TextSecondary.copy(alpha = 0.8f), maxLines = 1)
            }
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                LiveVideoSurface(preview, Modifier.fillMaxSize())
                if (!previewReady) {
                    val ch = previewChannel ?: selected
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (ch?.logo != null) {
                            AsyncImage(ch.logo, null, Modifier.size(width = 96.dp, height = 54.dp), contentScale = ContentScale.Fit)
                        }
                        Text(
                            if (previewError) "Preview unavailable" else if (previewChannel != null) "Loading…" else "Preview",
                            style = MaterialTheme.typography.labelSmall, color = BootColors.TextSecondary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Category chips ----
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(chipsRow, key = { it }) { f ->
                FilterChip(
                    selected = f == filter,
                    onClick = {
                        if (f != filter) {
                            filter = f; GuideMemory.filter = f; rowState = 0; focusTime = now; windowStart = floorSlot(now)
                        }
                    },
                ) {
                    Text(
                        when (f) { FILTER_ALL -> "All"; FILTER_FAV -> "Favorites"; FILTER_TOONS -> "Cartoons & Anime"; else -> f },
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == FILTER_FAV) "No favorites yet. Press and hold OK (or the Menu key) on a channel to add it."
                    else "No channels in this category.",
                    color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineWidth: Dp = maxWidth - ChannelColWidth
            val dpPerMs = timelineWidth.value / WINDOW_MS.toFloat()
            fun xFor(t: Long): Dp = ((t - ws).coerceIn(0, WINDOW_MS) * dpPerMs).dp

            Column(Modifier.fillMaxSize()) {
                // Time ruler
                Row(Modifier.fillMaxWidth().height(24.dp)) {
                    Box(Modifier.width(ChannelColWidth).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Text(dayLabel(ws, now), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
                    }
                    Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                        for (i in 0 until (WINDOW_MS / SLOT_MS).toInt()) {
                            val t = ws + i * SLOT_MS
                            Text(
                                fmt.t(t), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary,
                                modifier = Modifier.offset(x = xFor(t) + 6.dp).align(Alignment.CenterStart),
                            )
                        }
                    }
                }

                // Grid
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(gridFocus)
                        .onFocusChanged { gridFocused = it.isFocused }
                        .onKeyEvent { ev ->
                            val native = ev.nativeKeyEvent
                            val k = ev.key
                            if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                                if (ev.type == KeyEventType.KeyDown) {
                                    if (native.repeatCount == 0) { okDown = true; longFired = false }
                                    else if (okDown && !longFired) {
                                        longFired = true
                                        selected?.let { LiveTvRepository.toggleFavorite(it.id) }
                                    }
                                } else if (ev.type == KeyEventType.KeyUp) {
                                    if (okDown && !longFired) selected?.let { play(it) }
                                    okDown = false; longFired = false
                                }
                                return@onKeyEvent true
                            }
                            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (k) {
                                Key.DirectionUp -> if (row == 0) false else { selectRow(row - 1); true }
                                Key.DirectionDown -> { selectRow(row + 1); true }
                                Key.DirectionRight -> moveHorizontal(1)
                                Key.DirectionLeft -> moveHorizontal(-1)
                                Key.ChannelUp, Key.PageUp -> { selectRow(row - visibleRows); true }
                                Key.ChannelDown, Key.PageDown -> { selectRow(row + visibleRows); true }
                                Key.MediaFastForward -> {
                                    val n = (ws + WINDOW_MS).coerceAtMost(maxTime - WINDOW_MS); windowStart = n; focusTime = n; true
                                }
                                Key.MediaRewind -> {
                                    val n = (ws - WINDOW_MS).coerceAtLeast(minTime); windowStart = n; focusTime = maxOf(n, now); true
                                }
                                Key.Menu -> { selected?.let { LiveTvRepository.toggleFavorite(it.id) }; true }
                                else -> {
                                    val c = native.unicodeChar.toChar()
                                    if (c in '0'..'9' && digits.length < 5) { digits += c; true } else false
                                }
                            }
                        }
                        .focusable()
                ) {
                    LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filtered, key = { _, c -> c.id }) { index, ch ->
                            GuideRow(
                                channel = ch,
                                programmes = state.programmesFor(ch.id),
                                isFavorite = ch.id in state.favorites,
                                isSelectedRow = index == row,
                                gridFocused = gridFocused,
                                ws = ws, ft = ft, now = now,
                                timelineWidth = timelineWidth,
                                dpPerMs = dpPerMs,
                                fmt = fmt,
                            )
                        }
                    }
                    // Current-time indicator
                    if (now in ws until ws + WINDOW_MS) {
                        Box(
                            Modifier.offset(x = ChannelColWidth + xFor(now) - 1.dp).width(2.dp).fillMaxHeight()
                                .background(BootColors.Live)
                        )
                    }
                    if (digits.isNotEmpty()) {
                        Text(
                            digits, style = MaterialTheme.typography.displaySmall, color = BootColors.TextPrimary,
                            modifier = Modifier.align(Alignment.TopEnd).background(BootColors.SurfaceRaised, RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun dayLabel(ws: Long, now: Long): String {
    val dayNow = floorDay(now)
    val day = floorDay(ws)
    return when (day) {
        dayNow -> "Today"
        dayNow + 86_400_000L -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(ws))
    }
}

private fun floorDay(t: Long): Long {
    val off = TimeZone.getDefault().getOffset(t)
    return Math.floorDiv(t + off, 86_400_000L) * 86_400_000L
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    isFavorite: Boolean,
    isSelectedRow: Boolean,
    gridFocused: Boolean,
    ws: Long,
    ft: Long,
    now: Long,
    timelineWidth: Dp,
    dpPerMs: Float,
    fmt: java.text.SimpleDateFormat,
) {
    val we = ws + WINDOW_MS
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        // Channel cell
        Row(
            Modifier.width(ChannelColWidth).fillMaxHeight().padding(end = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelectedRow) BootColors.SurfaceRaised else BootColors.Surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number.orEmpty(), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary,
                modifier = Modifier.width(38.dp), maxLines = 1, overflow = TextOverflow.Clip,
            )
            Box(Modifier.size(width = 44.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                if (channel.logo != null) {
                    AsyncImage(channel.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                channel.name, style = MaterialTheme.typography.labelMedium, color = BootColors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (isFavorite) Icon(Icons.Filled.Star, "Favorite", tint = Color(0xFFFFC94D), modifier = Modifier.size(14.dp))
        }
        // Programme blocks
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            for (b in buildBlocks(programmes, ws, we)) {
                val s = maxOf(b.start, ws)
                val e = minOf(b.end, we)
                if (e <= s) continue
                val x = ((s - ws) * dpPerMs).dp
                val w = ((e - s) * dpPerMs).dp
                val isSel = isSelectedRow && ft >= b.start && ft < b.end
                val airing = b.start <= now && b.end > now
                val bg = when {
                    isSel && gridFocused -> BootColors.Focused
                    airing -> BootColors.SurfaceRaised
                    else -> BootColors.Surface
                }
                Box(
                    Modifier.offset(x = x).width(w).fillMaxHeight().padding(end = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .then(if (isSel && !gridFocused) Modifier.border(1.5.dp, BootColors.TextSecondary, RoundedCornerShape(8.dp)) else Modifier)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (w > 24.dp) {
                        Column {
                            Text(
                                (if (b.start < ws) "‹ " else "") + (b.programme?.title ?: channel.name),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSel && gridFocused) BootColors.OnFocused else BootColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (w > 110.dp && b.programme != null) {
                                Text(
                                    "${fmt.t(b.start)} – ${fmt.t(b.end)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSel && gridFocused) BootColors.OnFocused.copy(alpha = 0.7f) else BootColors.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
