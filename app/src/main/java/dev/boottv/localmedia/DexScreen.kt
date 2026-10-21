package dev.boottv.localmedia

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.core.ui.BallEmblem
import dev.boottv.core.ui.BootCard
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.Pixel
import dev.boottv.core.ui.PokeSprite
import dev.boottv.core.ui.PokeType
import dev.boottv.core.ui.SpriteArt
import dev.boottv.core.ui.Sprites
import dev.boottv.core.ui.gameFrame
import dev.boottv.core.ui.leftToMenu
import dev.boottv.jellyfin.JellyfinClient
import dev.boottv.jellyfin.JfImage
import dev.boottv.jellyfin.formatClock
import dev.boottv.launcher.OnNow
import dev.boottv.launcher.onNow
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.livetv.MyChannel
import dev.boottv.livetv.MyChannels
import dev.boottv.livetv.MyContent
import dev.boottv.livetv.MyContentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * My Media as a Pokédex, laid out for a library that grows: the hand-held's left screen lists the sections (Continue
 * watching, My channels, All, then every folder with its type and count) and switches the page as focus moves; the
 * right screen is a grid of numbered entries for that section, sortable by number, name or newest, with the folder's
 * channel action at the top and a status strip underneath describing whatever is focused.
 */

private val DexScreenBg = Color(0xFF0E1024)
private val DexBezel = Color(0xFF2B2D3F)

/** A page of the Dex. */
private sealed interface DexSection {
    val key: String
    val title: String
    val type: PokeType
    val sprite: Int
    data class Videos(override val key: String, override val title: String, override val type: PokeType, val videos: List<LocalVideo>, val folder: String? = null, override val sprite: Int = type.sprite, val show: Boolean = false) : DexSection
    data class Channels(val channels: List<OnNow>) : DexSection { override val key = "channels"; override val title = "My channels"; override val type = PokeType.ELECTRIC; override val sprite = 479 }
    data class Action(override val key: String, override val title: String, val line: String, val detail: String, override val sprite: Int, val button: String, val onClick: () -> Unit) : DexSection {
        override val type = PokeType.STEEL
    }
}

private enum class Sort(val label: String) { NUMBER("No."), NAME("A–Z"), NEWEST("NEW") }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MyMediaScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { LocalLibrary.start(context) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocalLibrary.refreshSoon(context, 0)
    }
    val live by LiveTvRepository.state.collectAsState()
    val mine by MyChannels.channels.collectAsState()
    val myChannels = remember(live, mine) { live.onNow(live.channels.filter { it.sourceId == "my" }) }
    var status by remember { mutableStateOf<String?>(null) }
    var hasServer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasServer = JellyfinClient.current() != null }

    fun makeChannel(folder: String) {
        val existing = MyChannels.forFolder(folder)
        if (existing != null) { nav.navigate("live/play/${MyChannels.ID_PREFIX}${existing.id}"); return }
        status = "Making a channel from $folder…"
        scope.launch {
            // A show's channel runs its episodes in order; a folder of films is shuffled once, like a movie channel.
            status = runCatching { MyChannels.create(context, MyContent(MyContentKind.DEVICE_FOLDER, folder, folder), shuffle = !LocalLibrary.isShow(folder)) }
                .fold({ "Channel ${it.number} · ${it.name} is on the air! Find it in Live TV and on Home." }, { it.message ?: "Couldn't make that channel." })
        }
    }

    // Until the first scan has answered there'd be nothing but the two action pages, and focus would settle on them.
    if (!LocalLibrary.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { dev.boottv.core.ui.LoadingIndicator("Opening the Dex…") }
        return
    }
    val videos = LocalLibrary.videos
    // Dex numbers follow the order videos arrived on the TV, so they never change as more are added.
    val numbers = remember(videos) { videos.sortedWith(compareBy({ it.addedAt }, { it.id })).mapIndexed { i, v -> v.id to i + 1 }.toMap() }
    val folders = LocalLibrary.folders()
    val resume = LocalLibrary.continueWatching()
    val sections = buildList<DexSection> {
        if (resume.isNotEmpty()) add(DexSection.Videos("continue", "Continue watching", PokeType.PSYCHIC, resume, sprite = 143))
        if (myChannels.isNotEmpty()) add(DexSection.Channels(myChannels))
        if (folders.size > 1) add(DexSection.Videos("all", "All videos", PokeType.NORMAL, videos, sprite = 133))
        folders.forEach { (folder, list) -> add(DexSection.Videos("f:$folder", folder, PokeType.of(folder), list, folder = folder, show = list.any { it.isEpisode })) }
        add(
            DexSection.Action(
                "send", "Send from your phone", "Or a computer on the same Wi-Fi",
                "The TV shows an address, a QR code and a 4-digit code. Pick videos in the phone's browser and they register here; a folder can become a channel.",
                16, "Open Send to TV",
            ) { nav.navigate(UPLOAD_ROUTE) }
        )
        if (!LocalLibrary.permitted) add(
            DexSection.Action("allow", "Show all videos on this TV", "LocalSend and USB drives", "BootTV needs permission to see videos that other apps saved on this TV.", 164, "Allow") {
                askPermission.launch(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
                    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        )
        add(
            DexSection.Action(
                "server", if (hasServer) "Your Mac library" else "Connect your Mac", "Movies and shows from Jellyfin",
                "Your Mac's movies and shows with artwork, episodes and resume, streamed over Wi-Fi.", 137, if (hasServer) "Open library" else "Set up",
            ) { nav.navigate(MEDIA_SERVER_ROUTE) }
        )
    }
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    val current = sections.firstOrNull { it.key == currentKey } ?: sections.first()
    var focusedVideo by remember { mutableStateOf<LocalVideo?>(null) }
    var focusedChannel by remember { mutableStateOf<OnNow?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }

    val sectionRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun sectionRequester(k: String) = sectionRequesters.getOrPut(k) { FocusRequester() }
    // Coming in from the side menu, focus first lands on whichever section sits level with the rail's My Media entry;
    // move it to the page we mean to show (the first section, or the one open last time).
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // The first focus to land in the list is redirected to the open section if it hit another row.
    val initialKey = remember { current.key }
    var redirected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val want = current.key
        repeat(15) { delay(100); if (focusedKey == want) return@LaunchedEffect; runCatching { sectionRequester(want).requestFocus() } }
    }

    Column(
        Modifier.fillMaxSize()
            .padding(start = 24.dp, end = BootDimens.PagePaddingH, top = 18.dp, bottom = 18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(BootColors.theme.shell, BootColors.theme.shellDark)))
            .padding(14.dp),
    ) {
        DexTop(seen = videos.size, own = LocalProgress.entries.size)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Focus entering the section list (from the side menu, or on arrival) lands on the open section, not on
            // whichever row happens to sit level with the rail.
            DexPane(Modifier.width(250.dp).fillMaxHeight().focusProperties { enter = { sectionRequester(current.key) } }.focusGroup()) {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(sections, key = { it.key }) { s ->
                        SectionLine(
                            s, selected = s.key == current.key,
                            modifier = Modifier.leftToMenu().focusRequester(sectionRequester(s.key))
                                .onFocusChanged {
                                    if (!it.isFocused) return@onFocusChanged
                                    if (!redirected) {
                                        redirected = true
                                        if (s.key != initialKey && runCatching { sectionRequester(initialKey).requestFocus() }.isSuccess) return@onFocusChanged
                                    }
                                    currentKey = s.key; focusedKey = s.key; focusedVideo = null; focusedChannel = null
                                },
                            onClick = { if (s is DexSection.Action) s.onClick() },
                        )
                    }
                }
            }
            DexPane(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (val s = current) {
                            is DexSection.Videos -> VideoGrid(
                                s, numbers, Sort.entries[sort],
                                onSort = { sort = (sort + 1) % Sort.entries.size },
                                channel = s.folder?.let { MyChannels.forFolder(it) },
                                onChannel = { s.folder?.let(::makeChannel) },
                                onFocused = { focusedVideo = it; focusedChannel = null },
                                onPlay = { nav.navigate("media/local/${it.id}") },
                            )
                            is DexSection.Channels -> ChannelGrid(s.channels, onFocused = { focusedChannel = it; focusedVideo = null }, onWatch = { nav.navigate("live/play/${it.channel.id}") })
                            is DexSection.Action -> ActionPage(s)
                        }
                    }
                    StatusStrip(current, focusedVideo, focusedChannel, numbers, status)
                }
            }
        }
    }
}

/** Lens, three lights and the counts, like the top of the hand-held. */
@Composable
private fun DexTop(seen: Int, own: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(56.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color.White, r)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8ED6FF), Color(0xFF1E88E5), Color(0xFF0B3D91)), center = Offset(r * 0.8f, r * 0.8f), radius = r), r * 0.84f)
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.16f, center = Offset(r * 0.68f, r * 0.62f))
        }
        Spacer(Modifier.width(14.dp))
        listOf(Color(0xFFFF4D4D), Color(0xFFFFD84D), Color(0xFF5CE65C)).forEach { c ->
            Box(Modifier.size(14.dp).background(c, CircleShape).border(2.dp, BootColors.theme.shellDark, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("MY MEDIA", color = Color.White, fontFamily = Pixel, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (seen == 0) "NO ENTRIES YET" else "SEEN $seen   OWN $own", color = Color.White.copy(alpha = 0.85f), fontFamily = Pixel, fontSize = 10.sp)
        }
    }
}

/** A dark inset screen with a bevel. */
@Composable
private fun DexPane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(DexBezel).padding(5.dp).clip(RoundedCornerShape(10.dp)).background(DexScreenBg)) { content() }
}

/** One section on the left screen: sprite, name, type and count. The focused line gets the ▶ cursor. */
@Composable
private fun SectionLine(s: DexSection, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent, contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused, focusedContentColor = BootColors.OnFocused,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (focused) "▶" else " ", fontSize = 12.sp, modifier = Modifier.width(14.dp))
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { if (Sprites.on) PokeSprite(s.sprite, 44.dp) else Box(Modifier.size(10.dp).background(s.type.color, CircleShape)) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (s) {
                    is DexSection.Videos -> if (s.show) "SERIES  ${s.videos.mapNotNull { it.season }.distinct().size.let { if (it > 1) "$it SEASONS  " else "" }}${s.videos.size} EP" else "${s.type.label.uppercase()}  ${s.videos.size}"
                    is DexSection.Channels -> "${s.channels.size} ON AIR"
                    is DexSection.Action -> s.line.uppercase()
                }
                Text(sub, fontFamily = Pixel, fontSize = 7.sp, color = if (focused) BootColors.OnFocused.copy(alpha = 0.7f) else s.type.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------- right screen

@Composable
private fun VideoGrid(
    s: DexSection.Videos,
    numbers: Map<Long, Int>,
    sort: Sort,
    onSort: () -> Unit,
    channel: MyChannel?,
    onChannel: () -> Unit,
    onFocused: (LocalVideo) -> Unit,
    onPlay: (LocalVideo) -> Unit,
) {
    // Shows stay in episode order unless another sort is picked; films default to Dex number.
    val sorted = remember(s, sort) {
        when {
            s.key == "continue" -> s.videos
            sort == Sort.NAME -> s.videos.sortedBy { it.title.lowercase() }
            sort == Sort.NEWEST -> s.videos.sortedByDescending { it.addedAt }
            s.show -> s.videos
            else -> s.videos.sortedBy { numbers[it.id] }
        }
    }
    val bySeason = remember(sorted) { if (s.show && sort == Sort.NUMBER) sorted.groupBy { it.season } else null }
    val upNext = remember(s, LocalProgress.entries.size) { if (s.show && s.folder != null) LocalLibrary.upNext(s.folder) else null }
    Column(Modifier.fillMaxSize()) {
    // The title and chips stay put; only the entries scroll.
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(s.type.color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(s.title.uppercase(), fontFamily = Pixel, fontSize = 11.sp, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (upNext != null) {
            Chip("▶ " + (if (s.videos.any { LocalProgress.isWatched(it.id) || LocalProgress.entries.containsKey(it.id) }) "CONTINUE ${upNext.episodeLabel}" else "START ${upNext.episodeLabel}"), onClick = { onPlay(upNext) }, accent = true)
            Spacer(Modifier.width(8.dp))
        }
        Chip("SORT ${if (s.show && sort == Sort.NUMBER) "EP" else sort.label}", onClick = onSort)
        if (s.folder != null) {
            Spacer(Modifier.width(8.dp))
            Chip(if (channel != null) "CH ${channel.number} ▶" else "+ CHANNEL", onClick = onChannel, accent = true)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (bySeason != null) {
            bySeason.forEach { (season, eps) ->
                if (bySeason.size > 1 || season != null) item(span = { GridItemSpan(maxLineSpan) }, key = "season:$season") {
                    Text(if (season != null) "SEASON $season" else "EPISODES", fontFamily = Pixel, fontSize = 10.sp, color = s.type.color, modifier = Modifier.padding(top = 6.dp))
                }
                items(eps, key = { it.id }) { v ->
                    VideoCard(v, numbers[v.id] ?: 0, Modifier.onFocusChanged { if (it.isFocused) onFocused(v) }, onClick = { onPlay(v) })
                }
            }
        } else {
            items(sorted, key = { it.id }) { v ->
                VideoCard(v, numbers[v.id] ?: 0, Modifier.onFocusChanged { if (it.isFocused) onFocused(v) }, onClick = { onPlay(v) })
            }
        }
        if (sorted.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("No entries here yet.", color = BootColors.TextSecondary, modifier = Modifier.padding(top = 24.dp))
        }
    }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit, accent: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (accent) BootColors.Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f), contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused, focusedContentColor = BootColors.OnFocused,
        ),
    ) {
        Text(text, fontFamily = Pixel, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

@Composable
private fun VideoCard(v: LocalVideo, number: Int, modifier: Modifier, onClick: () -> Unit) {
    Column {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            scale = BootCard.scale, border = BootCard.border, glow = BootCard.glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                JfImage(v.image, Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (v.isEpisode) v.episodeLabel else "No.%03d".format(number), color = Color.White, fontFamily = Pixel, fontSize = 8.sp)
                    if (LocalProgress.entries.containsKey(v.id)) { Spacer(Modifier.width(5.dp)); BallEmblem(10.dp) }
                }
                if (LocalProgress.isWatched(v.id)) {
                    Text("✓", color = BootColors.OnFocused, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(BootColors.Focused, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Text(
                    formatClock(v.durationMs), color = Color.White, fontFamily = Pixel, fontSize = 8.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp),
                )
                LocalProgress.entries[v.id]?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.7f).padding(8.dp).height(3.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(BootColors.Focused, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Text(v.title, style = MaterialTheme.typography.bodySmall, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

@Composable
private fun ChannelGrid(channels: List<OnNow>, onFocused: (OnNow) -> Unit, onWatch: (OnNow) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("MY CHANNELS · ON AIR NOW", fontFamily = Pixel, fontSize = 11.sp, color = BootColors.TextPrimary)
        }
        items(channels, key = { it.channel.id }) { c ->
            Column {
                Card(
                    onClick = { onWatch(c) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused(c) },
                    shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = BootCard.scale, border = BootCard.border, glow = BootCard.glow,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        val art = c.now?.image
                        if (art != null) JfImage(art, Modifier.fillMaxSize()) else SpriteArt(c.channel.name, Modifier.fillMaxSize())
                        Text("CH ${c.channel.number ?: ""}", color = Color.White, fontFamily = Pixel, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
                        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(BootColors.Live, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Text(c.channel.name, style = MaterialTheme.typography.bodySmall, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
        }
    }
}

/** A section that does something (Send to TV, Mac library): the explanation and one button. */
@Composable
private fun ActionPage(s: DexSection.Action) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (Sprites.on) PokeSprite(s.sprite, 140.dp, animated = true)
        Text(s.title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary, modifier = Modifier.width(460.dp))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.height(44.dp)) { Chip(s.button.uppercase(), onClick = s.onClick, accent = true) }
    }
}

/** Under the grid: what the focused entry is, as a Dex line. */
@Composable
private fun StatusStrip(section: DexSection, v: LocalVideo?, c: OnNow?, numbers: Map<Long, Int>, status: String?) {
    val blink = rememberInfiniteTransition(label = "cursor").animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink")
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp).gameFrame().padding(horizontal = 12.dp, vertical = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            status != null -> Text(status, color = BootColors.Focused, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            v != null -> {
                val type = PokeType.of(v.folder)
                val seen = LocalProgress.entries[v.id]
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (v.isEpisode) v.episodeLabel else "No.%03d".format(numbers[v.id] ?: 0), fontFamily = Pixel, fontSize = 10.sp, color = BootColors.TextSecondary)
                        Spacer(Modifier.width(10.dp))
                        Text(v.title, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(10.dp))
                        Text(type.label.uppercase(), color = Color.White, fontFamily = Pixel, fontSize = 7.sp, modifier = Modifier.background(type.color, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                    Text(
                        listOfNotNull(
                            v.folder, v.subfolder.takeIf { it.isNotEmpty() }, formatClock(v.durationMs), formatSize(v.sizeBytes),
                            "added " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(v.addedAt)),
                            seen?.let { "${(it.fraction * 100).toInt()}% seen" } ?: if (LocalProgress.isWatched(v.id)) "watched ✓" else "not played yet",
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            c != null -> Column(Modifier.weight(1f)) {
                Text("CH ${c.channel.number ?: ""}  ${c.channel.name}", style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(c.now?.let { "On now: ${it.title}" }, c.next?.let { "Next: ${it.title}" }).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> Text(
                when (section) {
                    is DexSection.Videos -> if (section.folder != null) "${section.videos.size} entr${if (section.videos.size == 1) "y" else "ies"} in ${section.title}. ▶ to browse, OK plays, + CHANNEL puts the folder on the air." else "${section.videos.size} entries. ▶ to browse; OK plays."
                    is DexSection.Channels -> "Your own channels play around the clock. ▶ then OK tunes in."
                    is DexSection.Action -> section.line
                },
                style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        Text("▼", color = BootColors.TextPrimary.copy(alpha = if (blink.value > 0.5f) 1f else 0f), fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp).align(Alignment.Bottom))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%d MB".format(bytes / 1_000_000)
    else -> "%d KB".format(bytes / 1000)
}
