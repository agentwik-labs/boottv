package dev.boottv.localmedia

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootButton
import dev.boottv.core.ui.BootCard
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.PokeSprite
import dev.boottv.core.ui.RowTitle
import dev.boottv.core.ui.Sprites
import dev.boottv.core.ui.VideoPlayer
import dev.boottv.core.ui.leftToMenu
import dev.boottv.jellyfin.JellyfinClient
import dev.boottv.jellyfin.JfImage
import dev.boottv.jellyfin.formatClock
import dev.boottv.launcher.Featured
import dev.boottv.launcher.OnNowRow
import dev.boottv.launcher.onNow
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.livetv.MyChannels
import dev.boottv.livetv.MyContent
import dev.boottv.livetv.MyContentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MEDIA_SERVER_ROUTE = "media/server"
const val UPLOAD_ROUTE = "media/upload"
private fun localPlayRoute(id: Long) = "media/local/$id"

/** Registers Routes.MEDIA (your videos), Send to TV and the player. Jellyfin's screens live under media/server. */
fun NavGraphBuilder.localMediaGraph(nav: NavController) {
    composable(Routes.MEDIA) { MyMediaScreen(nav) }
    composable(UPLOAD_ROUTE) { UploadScreen(onDone = { nav.popBackStack() }) }
    composable("media/local/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
        LocalPlayerScreen(nav, it.arguments?.getLong("id") ?: 0L)
    }
}

private val CardShape = RoundedCornerShape(12.dp)

/** What the banner shows for the focused card. */
private data class Banner(val title: String, val line: String, val image: String?)

private val WelcomeBanner = Banner(
    "Your videos",
    "Send videos from your phone or computer, copy them over with LocalSend, or plug in a USB drive.",
    null,
)

/** A row on the My Media screen. */
private sealed interface MediaRow {
    val key: String
    data class Actions(val actions: List<MediaAction>) : MediaRow { override val key = "actions" }
    data class Videos(val title: String, val videos: List<LocalVideo>, val folder: String? = null) : MediaRow { override val key = "v:$title" }
    data object Channels : MediaRow { override val key = "channels" }
}

private data class MediaAction(val key: String, val title: String, val line: String, val icon: ImageVector, val onClick: () -> Unit)

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
                .fold({ "Channel ${it.number} · ${it.name} is on. Find it in Live TV and on Home." }, { it.message ?: "Couldn't make that channel." })
        }
    }

    val videos = LocalLibrary.videos
    val actions = buildList {
        add(MediaAction("send", "Send from your phone", "Or a computer, on the same Wi-Fi", Icons.Filled.PhoneAndroid) { nav.navigate(UPLOAD_ROUTE) })
        if (!LocalLibrary.permitted) add(
            MediaAction("allow", "Show all videos on this TV", "Videos from LocalSend and USB drives", Icons.Filled.FolderOpen) {
                askPermission.launch(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
                    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        )
        add(
            MediaAction("server", if (hasServer) "Your Mac library" else "Connect your Mac", "Movies and shows from Jellyfin", Icons.Filled.Computer) {
                nav.navigate(MEDIA_SERVER_ROUTE)
            }
        )
    }
    val resume = LocalProgress.entries.entries.sortedByDescending { it.value.updatedAt }.mapNotNull { LocalLibrary.byId(it.key) }
    val rows = buildList {
        if (resume.isNotEmpty()) add(MediaRow.Videos("Continue watching", resume))
        add(MediaRow.Actions(actions))
        if (myChannels.isNotEmpty()) add(MediaRow.Channels)
        if (videos.size > 6) add(MediaRow.Videos("Recently added", LocalLibrary.recentlyAdded()))
        LocalLibrary.folders().forEach { (folder, list) -> add(MediaRow.Videos(folder, list, folder)) }
    }

    var banner by remember { mutableStateOf(WelcomeBanner) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(k: String) = requesters.getOrPut(k) { FocusRequester() }
    var landed by remember { mutableStateOf(false) }
    val firstKey = when (val r = rows.first()) {
        is MediaRow.Videos -> "${r.key}:${r.videos.first().id}"
        is MediaRow.Actions -> "a:${r.actions.first().key}"
        MediaRow.Channels -> "ch:"
    }
    LaunchedEffect(Unit) {
        repeat(15) { delay(100); if (landed) return@LaunchedEffect; runCatching { requester(firstKey).requestFocus() } }
    }

    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(340.dp)) {
            banner.image?.let { JfImage(it, Modifier.fillMaxSize(), plain = true) }
            if (banner.image == null && Sprites.on) {
                PokeSprite(235, 200.dp, Modifier.align(Alignment.Center).padding(start = 120.dp), animated = true)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to BootColors.Background, 0.5f to BootColors.Background.copy(alpha = 0.6f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to BootColors.Background)))
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().height(200.dp).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                Text(
                    "MY MEDIA · " + if (videos.isEmpty()) "NOTHING HERE YET" else "${videos.size} VIDEO${if (videos.size == 1) "" else "S"} ON THIS TV",
                    color = BootColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
                )
                Text(banner.title, style = MaterialTheme.typography.displaySmall, fontSize = 36.sp, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 700.dp))
                Text(status ?: banner.line, style = MaterialTheme.typography.bodyLarge, color = if (status != null) BootColors.Focused else BootColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
            }
            LazyColumn(
                // Rows fade out as they scroll up under the banner instead of being cut off mid-card.
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black), blendMode = BlendMode.DstIn)
                    },
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is MediaRow.Actions -> ActionsRow(row.actions, ::requester) { a -> banner = Banner(a.title, a.line, null); landed = true }
                        is MediaRow.Channels -> Box(Modifier.padding(start = BootDimens.PagePaddingH)) {
                            OnNowRow(
                                myChannels, ::requester,
                                onFocused = { _, f -> landed = true; (f as? Featured.Live)?.onNow?.let { banner = Banner(it.now?.title ?: it.channel.name, "Channel ${it.channel.number} · ${it.channel.name} · playing now", it.now?.image) } },
                                onWatch = { nav.navigate("live/play/${it.id}") },
                                title = "My channels", keyPrefix = "ch:",
                            )
                        }
                        is MediaRow.Videos -> VideosRow(
                            row, ::requester,
                            channel = row.folder?.let { f -> MyChannels.forFolder(f) },
                            onFocused = { v -> banner = v.banner(); landed = true },
                            onPlay = { nav.navigate(localPlayRoute(it.id)) },
                            onMakeChannel = { row.folder?.let(::makeChannel) },
                            onChannelFocused = { f ->
                                val ch = MyChannels.forFolder(f)
                                banner = if (ch != null) Banner("Channel ${ch.number} · ${ch.name}", "Plays $f around the clock. OK to watch.", null)
                                else Banner("Make $f a channel", "Its videos play around the clock in Live TV, like regular TV.", null)
                                landed = true
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LocalVideo.banner() = Banner(
    title,
    listOfNotNull(folder, formatClock(durationMs), LocalProgress.entries[id]?.let { "${formatClock(it.durationMs - it.positionMs)} left" }).joinToString("   ·   "),
    image,
)

@Composable
private fun ActionsRow(actions: List<MediaAction>, requester: (String) -> FocusRequester, onFocused: (MediaAction) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
        contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 12.dp),
    ) {
        itemsIndexed(actions, key = { _, a -> a.key }) { i, a ->
            Card(
                onClick = a.onClick,
                modifier = Modifier.width(300.dp).height(96.dp).leftToMenu(i == 0).focusRequester(requester("a:${a.key}"))
                    .onFocusChanged { if (it.isFocused) onFocused(a) },
                shape = CardDefaults.shape(CardShape),
                scale = BootCard.scale, border = BootCard.border, glow = BootCard.glow,
                colors = BootCard.colors(BootColors.SurfaceRaised),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(a.icon, null, tint = BootColors.Focused, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(a.title, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary, maxLines = 1)
                        Text(a.line, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideosRow(
    row: MediaRow.Videos,
    requester: (String) -> FocusRequester,
    channel: dev.boottv.livetv.MyChannel?,
    onFocused: (LocalVideo) -> Unit,
    onPlay: (LocalVideo) -> Unit,
    onMakeChannel: () -> Unit,
    onChannelFocused: (String) -> Unit,
) {
    Column {
        RowTitle(row.title, Modifier.padding(start = BootDimens.PagePaddingH))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 12.dp),
        ) {
            itemsIndexed(row.videos, key = { _, v -> v.id }) { i, v ->
                VideoCard(v, Modifier.leftToMenu(i == 0).focusRequester(requester("${row.key}:${v.id}")), { onFocused(v) }, { onPlay(v) })
            }
            // A folder's last card: make it a channel (or watch the one already made).
            row.folder?.let { folder ->
                item(key = "channel") {
                    Column(Modifier.width(BootDimens.WideCardWidth)) {
                        Card(
                            onClick = onMakeChannel,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onChannelFocused(folder) },
                            shape = CardDefaults.shape(CardShape),
                            scale = BootCard.scale, border = BootCard.border, glow = BootCard.glow,
                            colors = BootCard.colors(BootColors.Surface),
                        ) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(if (channel != null) Icons.Filled.LiveTv else Icons.Filled.Add, null, tint = BootColors.Focused, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(if (channel != null) "Watch channel ${channel.number}" else "Make it a channel", color = BootColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(if (channel != null) channel.name else "Plays around the clock", style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(v: LocalVideo, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(BootDimens.WideCardWidth)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(CardShape),
            scale = BootCard.scale, border = BootCard.border, glow = BootCard.glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                JfImage(v.image, Modifier.fillMaxSize())
                Text(
                    formatClock(v.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                LocalProgress.entries[v.id]?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp).height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(BootColors.Accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary)
        Text(v.folder, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
    }
}

// ---------------------------------------------------------------------------- Send to TV

/** Shows the address and code for sending videos from a phone or computer, and what's arriving. */
@Composable
fun UploadScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        UploadServer.start(context)
        onDispose { UploadServer.stop() }
    }
    val state by UploadServer.state.collectAsState()
    val link = state.address?.let { "$it/#${state.code}" }
    val qr = remember(link) { link?.let { qrCode(it) } }

    Box(Modifier.fillMaxSize().background(BootColors.Background).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (Sprites.on) PokeSprite(16, 72.dp, animated = true)
                    Text("Send videos to this TV", style = MaterialTheme.typography.displaySmall, fontSize = 34.sp, color = BootColors.TextPrimary)
                }
                if (state.error != null) {
                    Text(state.error!!, style = MaterialTheme.typography.titleMedium, color = BootColors.Live)
                } else {
                    Step("1", "On your phone or computer, join the same Wi-Fi as the TV.")
                    Step("2", "Scan the code with the phone's camera, or open this address in a browser:")
                    Text(state.address ?: "Starting…", style = MaterialTheme.typography.headlineMedium, color = BootColors.Focused, modifier = Modifier.padding(start = 44.dp))
                    Step("3", "Type this code there, pick a folder name and your videos:")
                    Text(state.code.toCharArray().joinToString("  "), style = MaterialTheme.typography.displaySmall, color = BootColors.TextPrimary, modifier = Modifier.padding(start = 44.dp))
                    Text(
                        "Videos appear in My Media as they arrive, and a folder can become its own channel. Sending stops when you leave this screen.",
                        style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary,
                    )
                }
                // Something to hold focus here (otherwise it goes to the side menu); OK goes back to My Media.
                val done = remember { FocusRequester() }
                LaunchedEffect(Unit) { repeat(10) { delay(100); if (runCatching { done.requestFocus() }.isSuccess) return@LaunchedEffect } }
                BootButton("Done", onClick = onDone, icon = Icons.Filled.Check, modifier = Modifier.leftToMenu().focusRequester(done))
                if (state.uploads.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    state.uploads.takeLast(4).forEach { u -> UploadLine(u) }
                }
            }
            qr?.let {
                Box(Modifier.size(300.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Image(it, contentDescription = "QR code for ${state.address}", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(BootColors.Focused, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text(n, color = BootColors.OnFocused, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
    }
}

@Composable
private fun UploadLine(u: Upload) {
    Column(Modifier.widthIn(max = 640.dp)) {
        Row {
            Text("${u.folder} / ${u.name}", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when { u.error != null -> u.error; u.done -> "Arrived ✓"; else -> "${(100 * u.received / u.total.coerceAtLeast(1))}%" },
                style = MaterialTheme.typography.bodyMedium, color = if (u.error != null) BootColors.Live else BootColors.TextSecondary,
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (u.done) 1f else u.received.toFloat() / u.total.coerceAtLeast(1)).background(BootColors.Focused, RoundedCornerShape(2.dp)))
        }
    }
}

private fun qrCode(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

// ---------------------------------------------------------------------------- player

@Composable
fun LocalPlayerScreen(nav: NavController, id: Long) {
    val context = LocalContext.current
    val video = remember(id) { LocalLibrary.byId(id) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { LocalProgress.clear(id); nav.popBackStack() }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (player.playbackState != Player.STATE_ENDED) LocalProgress.save(id, player.currentPosition, player.duration)
            player.release()
        }
    }
    LaunchedEffect(id) {
        val v = video ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(v.uri))
        LocalProgress.entries[id]?.let { player.seekTo(it.positionMs) }
        player.prepare()
        player.playWhenReady = true
    }
    VideoPlayer(
        player = player,
        title = video?.title.orEmpty(),
        error = if (video == null) "This video isn't on the TV anymore." else null,
        onExit = { nav.popBackStack() },
        onCheckpoint = { pos, dur -> LocalProgress.save(id, pos, dur) },
    )
}
