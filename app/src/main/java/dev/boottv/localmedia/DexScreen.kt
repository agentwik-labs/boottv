package dev.boottv.localmedia

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import dev.boottv.core.ui.BallEmblem
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.Pixel
import dev.boottv.core.ui.gameFrame
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.PokeSprite
import dev.boottv.core.ui.PokeType
import dev.boottv.core.ui.SpriteArt
import dev.boottv.core.ui.Sprites
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
 * My Media as a Pokédex: a red hand-held with a lens and three lights, a screen on the left showing the selected
 * entry (picture, number, type, stats, a line of "Dex text"), and the register of entries on the right. Every video
 * on the TV is a numbered entry (No.001 is the first one that arrived); folders are its sections, each with a type.
 * Nothing works differently from before: OK plays, folders become channels, Send to TV and the Mac library are entries.
 */

private val DexScreenBg = Color(0xFF0E1024)
private val DexBezel = Color(0xFF2B2D3F)

/** One line of the register. */
private sealed interface DexEntry {
    val key: String
    data class Action(override val key: String, val title: String, val line: String, val sprite: Int, val onClick: () -> Unit) : DexEntry
    data class Section(val title: String, val type: PokeType, val count: Int) : DexEntry { override val key = "s:$title" }
    data class Video(val video: LocalVideo, val number: Int, val type: PokeType) : DexEntry { override val key = "v:${video.id}" }
    data class Channel(val onNow: OnNow, val type: PokeType) : DexEntry { override val key = "c:${onNow.channel.id}" }
    data class MakeChannel(val folder: String, val type: PokeType, val channel: MyChannel?) : DexEntry { override val key = "m:$folder" }
}

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
            status = runCatching { MyChannels.create(context, MyContent(MyContentKind.DEVICE_FOLDER, folder, folder)) }
                .fold({ "Channel ${it.number} · ${it.name} is on the air! Find it in Live TV and on Home." }, { it.message ?: "Couldn't make that channel." })
        }
    }

    val videos = LocalLibrary.videos
    // Dex numbers: in the order videos arrived on the TV, so they never change as more are added.
    val numbers = remember(videos) { videos.sortedWith(compareBy({ it.addedAt }, { it.id })).mapIndexed { i, v -> v.id to i + 1 }.toMap() }
    val entries = buildList {
        add(DexEntry.Action("send", "Send from your phone", "Or a computer, on the same Wi-Fi", 16) { nav.navigate(UPLOAD_ROUTE) })
        if (!LocalLibrary.permitted) add(
            DexEntry.Action("allow", "Show all videos on this TV", "Videos from LocalSend and USB drives", 164) {
                askPermission.launch(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
                    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        )
        add(DexEntry.Action("server", if (hasServer) "Your Mac library" else "Connect your Mac", "Movies and shows from Jellyfin", 137) { nav.navigate(MEDIA_SERVER_ROUTE) })
        if (myChannels.isNotEmpty()) {
            add(DexEntry.Section("My channels", PokeType.ELECTRIC, myChannels.size))
            myChannels.forEach { add(DexEntry.Channel(it, PokeType.of(it.channel.name))) }
        }
        val resume = LocalProgress.entries.entries.sortedByDescending { it.value.updatedAt }.mapNotNull { LocalLibrary.byId(it.key) }
        if (resume.isNotEmpty()) {
            add(DexEntry.Section("Continue watching", PokeType.PSYCHIC, resume.size))
            resume.forEach { add(DexEntry.Video(it, numbers.getValue(it.id), PokeType.of(it.folder))) }
        }
        LocalLibrary.folders().forEach { (folder, list) ->
            val type = PokeType.of(folder)
            add(DexEntry.Section(folder, type, list.size))
            list.sortedBy { numbers.getValue(it.id) }.forEach { add(DexEntry.Video(it, numbers.getValue(it.id), type)) }
            add(DexEntry.MakeChannel(folder, type, MyChannels.forFolder(folder)))
        }
    }
    val selectable = entries.filter { it !is DexEntry.Section }
    var selected by remember { mutableStateOf<DexEntry?>(null) }
    if (selected == null || selectable.none { it.key == selected?.key }) selected = selectable.firstOrNull()

    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(k: String) = requesters.getOrPut(k) { FocusRequester() }
    var landed by remember { mutableStateOf(false) }
    val firstKey = selectable.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        if (firstKey == null) return@LaunchedEffect
        repeat(15) { delay(100); if (landed) return@LaunchedEffect; runCatching { requester(firstKey).requestFocus() } }
    }

    // The device: red shell with its lens and lights above two dark panes.
    Column(
        Modifier.fillMaxSize().background(BootColors.Background)
            .padding(start = 24.dp, end = BootDimens.PagePaddingH, top = 18.dp, bottom = 18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(BootColors.theme.shell, BootColors.theme.shellDark)))
            .padding(14.dp),
    ) {
        DexTop(registered = videos.size)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DexPane(Modifier.weight(0.42f).fillMaxHeight()) { DexDetail(selected, videos.size, status) }
            DexPane(Modifier.weight(0.58f).fillMaxHeight()) {
                LazyColumn(contentPadding = PaddingValues(vertical = 10.dp, horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries, key = { it.key }) { e ->
                        if (e is DexEntry.Section) {
                            SectionLine(e)
                        } else {
                            RegisterLine(
                                e,
                                modifier = Modifier.leftToMenu().focusRequester(requester(e.key)).onFocusChanged { if (it.isFocused) { selected = e; landed = true } },
                                onClick = {
                                    when (e) {
                                        is DexEntry.Action -> e.onClick()
                                        is DexEntry.Video -> nav.navigate("media/local/${e.video.id}")
                                        is DexEntry.Channel -> nav.navigate("live/play/${e.onNow.channel.id}")
                                        is DexEntry.MakeChannel -> makeChannel(e.folder)
                                        is DexEntry.Section -> {}
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Lens, three lights and the title, like the top of the hand-held. */
@Composable
private fun DexTop(registered: Int) {
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
            Text(
                if (registered == 0) "NO ENTRIES YET" else "SEEN $registered   OWN ${LocalProgress.entries.size}",
                color = Color.White.copy(alpha = 0.85f), fontFamily = Pixel, fontSize = 9.sp,
            )
        }
    }
}

/** A dark inset screen with a bevel. */
@Composable
private fun DexPane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(DexBezel).padding(5.dp)
            .clip(RoundedCornerShape(10.dp)).background(DexScreenBg),
    ) { content() }
}

/** The left screen: what the focused line is, as a Dex entry. */
@Composable
private fun DexDetail(entry: DexEntry?, registered: Int, status: String?) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Picture with scanlines, like a little CRT.
        Box(
            Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                .drawWithContent {
                    drawContent()
                    var y = 0f
                    while (y < size.height) { drawRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, 2f)); y += 5f }
                },
        ) {
            when (entry) {
                is DexEntry.Video -> JfImage(entry.video.image, Modifier.fillMaxSize())
                is DexEntry.Channel -> {
                    val art = entry.onNow.now?.image
                    if (art != null) JfImage(art, Modifier.fillMaxSize()) else SpriteArt(entry.onNow.channel.name, Modifier.fillMaxSize())
                }
                is DexEntry.Action -> Box(Modifier.fillMaxSize().background(BootColors.Surface), contentAlignment = Alignment.Center) { PokeSprite(entry.sprite, 150.dp, animated = true) }
                is DexEntry.MakeChannel -> Box(Modifier.fillMaxSize().background(entry.type.color.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { PokeSprite(479, 150.dp, animated = true) }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PokeSprite(Sprites.partner(), 150.dp, animated = true) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (entry) {
            is DexEntry.Video -> {
                val v = entry.video
                val seen = LocalProgress.entries[v.id]
                Text("No.%03d".format(entry.number), color = BootColors.TextSecondary, fontFamily = Pixel, fontSize = 11.sp)
                Text(v.title, style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                TypeBadge(entry.type)
                Spacer(Modifier.height(6.dp))
                Row { Column(Modifier.weight(1f)) { Stat("LENGTH", formatClock(v.durationMs)); Stat("ADDED", DateFormat.getDateInstance(DateFormat.SHORT).format(Date(v.addedAt))) }
                    Column(Modifier.weight(1f)) { Stat("SIZE", formatSize(v.sizeBytes)); Stat("SEEN", seen?.let { "${(it.fraction * 100).toInt()}%" } ?: "Not yet") } }
                Stat("FOLDER", v.folder)
                Spacer(Modifier.height(8.dp))
                DexText(
                    "A ${lengthWord(v.durationMs)} video kept in ${v.folder}. " +
                        (if (seen != null) "It was last watched part-way through; OK picks up where it stopped." else "It hasn't been played on this TV yet. OK plays it."),
                )
            }
            is DexEntry.Channel -> {
                val (ch, now, next) = entry.onNow
                Text("CHANNEL ${ch.number ?: ""}", color = BootColors.TextSecondary, fontFamily = Pixel, fontSize = 11.sp)
                Text(ch.name, style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                TypeBadge(entry.type)
                Spacer(Modifier.height(10.dp))
                Stat("ON NOW", now?.title ?: "—")
                Stat("NEXT", next?.title ?: "—")
                Spacer(Modifier.height(10.dp))
                DexText("One of your own channels. It plays around the clock like regular TV; tuning in joins whatever is on.")
            }
            is DexEntry.Action -> {
                Text(entry.title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                DexText(entry.line + ". " + when (entry.key) {
                    "send" -> "The TV shows an address, a QR code and a 4-digit code; pick videos in your browser and they register here."
                    "allow" -> "BootTV needs permission to see videos that other apps saved on this TV."
                    else -> "Your Mac's movies and shows, with artwork and resume, through Jellyfin."
                })
            }
            is DexEntry.MakeChannel -> {
                val ch = entry.channel
                Text(if (ch != null) "CHANNEL ${ch.number}" else "NEW CHANNEL", color = BootColors.TextSecondary, fontFamily = Pixel, fontSize = 11.sp)
                Text(entry.folder, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                TypeBadge(entry.type)
                Spacer(Modifier.height(10.dp))
                DexText(if (ch != null) "This folder is already on the air as channel ${ch.number}. OK tunes in." else "Turns everything in ${entry.folder} into a channel that plays around the clock, in Live TV and on Home.")
            }
            else -> {
                Text("No data", style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                DexText(if (registered == 0) "Nothing is registered yet. Send videos from your phone, copy them over with LocalSend, or plug in a USB drive." else "Move through the register to see each entry.")
            }
        }
        Spacer(Modifier.height(8.dp))
        status?.let { Text(it, color = BootColors.Focused, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun TypeBadge(type: PokeType) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            type.label.uppercase(), color = Color.White, fontFamily = Pixel, fontSize = 9.sp,
            modifier = Modifier.background(type.color, RoundedCornerShape(4.dp)).border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
        if (Sprites.on) PokeSprite(type.sprite, 40.dp, Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.padding(vertical = 0.dp)) {
        Text(label, color = BootColors.TextSecondary, fontFamily = Pixel, fontSize = 8.sp, modifier = Modifier.width(64.dp).padding(top = 3.dp))
        Text(value, color = BootColors.TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The entry's flavour text, in a bordered box like the games' Dex pages. */
@Composable
private fun DexText(text: String) {
    val blink = rememberInfiniteTransition(label = "cursor").animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink")
    Box(Modifier.fillMaxWidth().gameFrame().padding(10.dp)) {
        Text(text, color = BootColors.TextPrimary.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 14.dp))
        Text("▼", color = BootColors.TextPrimary.copy(alpha = if (blink.value > 0.5f) 1f else 0f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun SectionLine(s: DexEntry.Section) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(s.type.color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(s.title.uppercase(), color = BootColors.TextSecondary, fontFamily = Pixel, fontSize = 9.sp)
        Spacer(Modifier.width(10.dp))
        Text("${s.type.label.uppercase()} ${s.count}", color = s.type.color, fontFamily = Pixel, fontSize = 9.sp)
    }
}

/** One line of the register: number, picture or sprite, name. The focused line gets the ▶ cursor. */
@Composable
private fun RegisterLine(e: DexEntry, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused, focusedContentColor = BootColors.OnFocused,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (focused) "▶" else " ", fontSize = 12.sp, modifier = Modifier.width(16.dp))
            when (e) {
                is DexEntry.Video -> {
                    Column(Modifier.width(58.dp)) {
                        Text("No.%03d".format(e.number), fontFamily = Pixel, fontSize = 8.sp)
                        if (LocalProgress.entries.containsKey(e.video.id)) BallEmblem(12.dp, Modifier.padding(top = 3.dp))
                    }
                    Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(4.dp))) { JfImage(e.video.image, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Text(e.video.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatClock(e.video.durationMs), fontFamily = Pixel, fontSize = 8.sp, modifier = Modifier.padding(start = 8.dp))
                }
                is DexEntry.Channel -> {
                    Text("CH ${e.onNow.channel.number ?: ""}", fontFamily = Pixel, fontSize = 8.sp, modifier = Modifier.width(58.dp))
                    Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(4.dp))) {
                        val art = e.onNow.now?.image
                        if (art != null) JfImage(art, Modifier.fillMaxSize()) else SpriteArt(e.onNow.channel.name, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.onNow.channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.onNow.now?.title ?: "", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.background(BootColors.Live, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                is DexEntry.Action -> {
                    Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) { if (Sprites.on) PokeSprite(e.sprite, 48.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(e.line, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                is DexEntry.MakeChannel -> {
                    Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) { if (Sprites.on) PokeSprite(479, 44.dp) else Text("TV", fontWeight = FontWeight.Bold) }
                    Text(if (e.channel != null) "Watch channel ${e.channel.number}" else "Make ${e.folder} a channel", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                is DexEntry.Section -> {}
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%d MB".format(bytes / 1_000_000)
    else -> "%d KB".format(bytes / 1000)
}

private fun lengthWord(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 2 -> "short"
        min < 20 -> "$min-minute"
        min < 60 -> "$min-minute"
        else -> "%d h %02d min".format(min / 60, min % 60)
    }
}
