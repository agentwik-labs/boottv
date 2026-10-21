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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import dev.boottv.core.ui.Pixel
import dev.boottv.core.ui.gameFrame
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.pageBackground
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

    Box(Modifier.fillMaxSize().pageBackground().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
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
    var currentId by rememberSaveable { mutableLongStateOf(id) }
    val video = remember(currentId) { LocalLibrary.byId(currentId) }
    // Netflix-style: when an episode ends, the next one is lined up and starts after a short countdown.
    var upNext by remember { mutableStateOf<LocalVideo?>(null) }
    var countdown by remember { mutableIntStateOf(10) }
    val player = remember { ExoPlayer.Builder(context).build() }
    fun playNext() {
        val n = upNext ?: return
        upNext = null
        currentId = n.id
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                LocalProgress.markWatched(currentId); LocalProgress.clear(currentId)
                val next = LocalLibrary.nextEpisode(currentId)
                if (next == null) nav.popBackStack() else { countdown = 10; upNext = next }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (player.playbackState != Player.STATE_ENDED) LocalProgress.save(currentId, player.currentPosition, player.duration)
            player.release()
        }
    }
    LaunchedEffect(currentId) {
        val v = LocalLibrary.byId(currentId) ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(v.uri))
        LocalProgress.entries[currentId]?.let { player.seekTo(it.positionMs) }
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(upNext) {
        if (upNext == null) return@LaunchedEffect
        while (countdown > 0) { delay(1000); countdown-- }
        playNext()
    }
    VideoPlayer(
        player = player,
        title = video?.let { if (it.isEpisode) "${it.folder}  ·  ${it.episodeLabel}  ${it.title}" else it.title }.orEmpty(),
        error = if (video == null) "This video isn't on the TV anymore." else null,
        onExit = { nav.popBackStack() },
        onCheckpoint = { pos, dur -> LocalProgress.save(currentId, pos, dur) },
        interceptKey = { k ->
            val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == android.view.KeyEvent.KEYCODE_ENTER || k.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            if (upNext != null && ok) { if (k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0) playNext(); true } else false
        },
    ) {
        upNext?.let { n -> UpNextCard(n, countdown, Modifier.align(Alignment.BottomEnd).padding(BootDimens.PagePaddingH, BootDimens.PagePaddingV)) }
    }
}

/** "Up next" in the games' message window: picture, episode, countdown. */
@Composable
private fun UpNextCard(n: LocalVideo, seconds: Int, modifier: Modifier) {
    Row(modifier.width(520.dp).gameFrame(BootColors.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) { JfImage(n.image, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("UP NEXT", fontFamily = Pixel, fontSize = 10.sp, color = BootColors.TextSecondary)
            Text(listOfNotNull(n.episodeLabel.takeIf { n.isEpisode }, n.title).joinToString("  "), style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("Playing in $seconds…   OK play now · Back to stop", style = MaterialTheme.typography.bodySmall, color = BootColors.Focused)
        }
    }
}
