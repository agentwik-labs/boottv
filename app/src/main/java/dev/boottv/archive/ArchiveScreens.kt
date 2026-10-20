package dev.boottv.archive

import dev.boottv.core.ui.BootCard
import dev.boottv.core.ui.leftToMenu
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.Routes
import dev.boottv.core.SearchAction
import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchResult
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.jellyfin.CenteredMessage
import dev.boottv.jellyfin.JfButton
import dev.boottv.jellyfin.JfImage
import dev.boottv.jellyfin.LoadingMessage
import dev.boottv.jellyfin.formatClock
import dev.boottv.launcher.FreeApp
import dev.boottv.launcher.FreeAppsRow
import dev.boottv.launcher.Featured
import dev.boottv.launcher.OnNow
import dev.boottv.launcher.OnNowRow
import dev.boottv.launcher.ToonApps
import dev.boottv.launcher.isInstalled
import dev.boottv.launcher.onNow
import dev.boottv.launcher.openFreeApp
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.livetv.animationChannels
import dev.boottv.livetv.isAnime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

internal fun freeDetailRoute(id: String) = archiveDetailRoute(Routes.FREE, id)
/** Detail page under [section] (Free Movies or Cartoons), so the side menu keeps showing where you are. */
private fun archiveDetailRoute(section: String, id: String) = "$section/detail/$id"
internal fun freePlayRoute(id: String, restart: Boolean = false) = "${Routes.FREE}/play/$id?restart=$restart"

/** Registers Routes.FREE, Routes.TOONS and their "free/..." and "toons/..." routes. */
fun NavGraphBuilder.archiveGraph(nav: NavController) {
    composable(Routes.FREE) { FreeMoviesScreen(nav) }
    composable(Routes.TOONS) { ToonsScreen(nav) }
    for (section in listOf(Routes.FREE, Routes.TOONS)) {
        composable("$section/detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            FreeDetailScreen(nav, it.arguments?.getString("id").orEmpty())
        }
    }
    composable(
        "${Routes.FREE}/play/{id}?restart={restart}",
        arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("restart") { type = NavType.BoolType; defaultValue = false },
        ),
    ) {
        FreePlayerScreen(nav, it.arguments?.getString("id").orEmpty(), restart = it.arguments?.getBoolean("restart") ?: false)
    }
}

object ArchiveSearchProvider : SearchProvider {
    override val name = "Free Movies"

    override suspend fun search(query: String): List<SearchResult> = try {
        ArchiveClient.search(query, 20).map {
            SearchResult(
                id = "archive:${it.id}",
                title = it.title,
                subtitle = listOfNotNull("Free movie", it.year).joinToString(" · "),
                imageUrl = it.thumbUrl,
                wide = true,
                action = SearchAction.Navigate(freeDetailRoute(it.id)),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList()
    }
}

/** Shelves survive leaving the screen, so coming back is instant. */
private object FreeCache {
    /** By [ArchiveShelf.key]. */
    val shelves = mutableStateMapOf<String, List<ArchiveItem>>()
    /** By section route. */
    val errors = mutableStateMapOf<String, String>()
}

private val CardShape = RoundedCornerShape(12.dp)
private val DownloadedShelf = ArchiveShelf("Downloaded · plays offline", "_downloaded", "")
private val ContinueShelf = ArchiveShelf("Continue watching", "_continue", "")

/** A row on a browse screen: archive.org titles, live channels or free apps. */
private sealed interface BrowseRow {
    val key: String
    /** Focus key of the row's first card. */
    val firstKey: String

    data class Titles(val shelf: ArchiveShelf, val items: List<ArchiveItem>) : BrowseRow {
        override val key = shelf.key
        override val firstKey = titleKey(shelf, items.first())
    }
    data class Live(val title: String, val channels: List<OnNow>) : BrowseRow {
        override val key = "live:$title"
        val prefix = "live:$title:"
        override val firstKey = prefix + channels.first().channel.id
    }
    data class Apps(val title: String, val apps: List<FreeApp>) : BrowseRow {
        override val key = "apps:$title"
        override val firstKey = "free:${apps.first().pkg}"
    }
}

private fun titleKey(shelf: ArchiveShelf, item: ArchiveItem) = "t:${shelf.key}:${item.id}"

/** What the banner at the top shows for the focused card. */
private data class Banner(val title: String, val line: String, val image: String?, val fit: Boolean = false)

private fun ArchiveItem.banner() = Banner(title, listOfNotNull(year, "Free · no account, no ads").joinToString("   ·   "), thumbUrl)

/** Loads [shelves] once per process; true while the first load is still running. */
@Composable
private fun loadShelves(section: String, shelves: List<ArchiveShelf>, reload: Int): Boolean {
    var loading by remember { mutableStateOf(shelves.any { it.key !in FreeCache.shelves }) }
    LaunchedEffect(reload) {
        if (shelves.all { it.key in FreeCache.shelves }) return@LaunchedEffect
        loading = true
        FreeCache.errors.remove(section)
        val ok = coroutineScope {
            shelves.map { shelf ->
                async { runCatching { ArchiveClient.shelf(shelf) }.onSuccess { FreeCache.shelves[shelf.key] = it }.isSuccess }
            }.awaitAll()
        }
        if (ok.none { it }) FreeCache.errors[section] = "Couldn't reach the Internet Archive. Check the connection and try again."
        loading = false
    }
    return loading
}

private fun shelfRows(shelves: List<ArchiveShelf>) =
    shelves.mapNotNull { s -> FreeCache.shelves[s.key]?.takeIf { it.isNotEmpty() }?.let { BrowseRow.Titles(s, it) } }

// ---------------------------------------------------------------------------- browse

@Composable
fun FreeMoviesScreen(nav: NavController) {
    var reload by remember { mutableStateOf(0) }
    // Show the rows only once every shelf has answered, so rows don't pop in above the focused one.
    val loading = loadShelves(Routes.FREE, ArchiveShelves, reload)
    val saved = ArchiveDownloads.items.values.filter { it.done }.map { ArchiveItem(it.id, it.title, it.year) }
    val resume = ArchiveProgress.continueWatching().map { ArchiveItem(it.id, it.title, it.year) }
    val rows = listOfNotNull(
        resume.takeIf { it.isNotEmpty() }?.let { BrowseRow.Titles(ContinueShelf, it) },
        saved.takeIf { it.isNotEmpty() }?.let { BrowseRow.Titles(DownloadedShelf, it) },
    ) + shelfRows(ArchiveShelves)
    BrowseOrMessage(nav, Routes.FREE, "FREE MOVIES · INTERNET ARCHIVE", rows, loading && saved.isEmpty() && resume.isEmpty(), "Free Movies") { reload++ }
}

/** Anime and cartoons: live channels from the free feeds, free anime apps, and public-domain cartoons by series. */
@Composable
fun ToonsScreen(nav: NavController) {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val loading = loadShelves(Routes.TOONS, ToonShelves, reload)
    val live by LiveTvRepository.state.collectAsState()
    val channels = remember(live) { live.animationChannels() }
    val installed = remember { ToonApps.filter { isInstalled(context, it.pkg) }.map { it.pkg }.toSet() }
    val rows = listOfNotNull(
        channels.filter { it.isAnime() }.takeIf { it.isNotEmpty() }?.let { BrowseRow.Live("Anime · live now", live.onNow(it.take(24))) },
        channels.filter { !it.isAnime() }.takeIf { it.isNotEmpty() }?.let { BrowseRow.Live("Cartoons · live now", live.onNow(it.take(24))) },
        BrowseRow.Apps("More free anime", ToonApps.sortedBy { it.pkg !in installed }),
    ) + shelfRows(ToonShelves)
    BrowseOrMessage(nav, Routes.TOONS, "CARTOONS & ANIME · FREE AND LEGAL", rows, loading && channels.isEmpty(), "Cartoons & Anime") { reload++ }
}

@Composable
private fun BrowseOrMessage(nav: NavController, section: String, badge: String, rows: List<BrowseRow>, loading: Boolean, name: String, retry: () -> Unit) {
    val error = FreeCache.errors[section]
    when {
        loading -> LoadingMessage("Loading ${name.lowercase()}…")
        rows.none { it is BrowseRow.Titles || it is BrowseRow.Live } && error != null -> CenteredMessage(name, error) { fr ->
            JfButton("Try again", Icons.Filled.Replay, primary = true, modifier = Modifier.focusRequester(fr), onClick = retry)
        }
        rows.isEmpty() -> LoadingMessage("Nothing to show right now.")
        else -> FreeBrowse(nav, section, badge, rows)
    }
}

@Composable
private fun FreeBrowse(nav: NavController, section: String, badge: String, rows: List<BrowseRow>) {
    val context = LocalContext.current
    var banner by remember {
        mutableStateOf(when (val r = rows.first()) {
            is BrowseRow.Titles -> r.items.first().banner()
            is BrowseRow.Live -> liveBanner(r.channels.first())
            is BrowseRow.Apps -> Banner(r.apps.first().name, r.apps.first().blurb, null)
        })
    }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(k: String) = requesters.getOrPut(k) { FocusRequester() }
    val firstKey = rows.first().firstKey
    var firstFocused by remember { mutableStateOf(false) }
    // Start on the top row's first card. Retry until it really has focus:
    // the side menu may still be closing, and coming back the focus system first restores the old card.
    LaunchedEffect(Unit) {
        repeat(15) { delay(100); if (firstFocused) return@LaunchedEffect; runCatching { requester(firstKey).requestFocus() } }
    }
    fun focused(k: String, b: Banner) { banner = b; if (k == firstKey) firstFocused = true }
    val onFeatured: (String, Featured) -> Unit = { k, f ->
        when (f) {
            is Featured.Live -> focused(k, liveBanner(f.onNow))
            is Featured.App -> focused(k, Banner(f.app.name, f.app.blurb, null))
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        // Banner artwork for the focused card.
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(340.dp)) {
            if (banner.fit) {
                Box(Modifier.fillMaxSize().padding(start = 220.dp, top = 40.dp, bottom = 120.dp, end = 80.dp)) {
                    JfImage(banner.image, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, plain = true, fallback = Icons.Filled.Movie)
                }
            } else if (banner.image != null) {
                JfImage(banner.image, Modifier.fillMaxSize(), plain = true)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to BootColors.Background, 0.5f to BootColors.Background.copy(alpha = 0.6f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to BootColors.Background)))
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().height(200.dp).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                Text(badge, color = BootColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Text(banner.title, style = MaterialTheme.typography.displaySmall, fontSize = 36.sp, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 700.dp))
                Text(banner.line, style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
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
                        is BrowseRow.Titles -> TitlesRow(row, ::requester, onFocused = ::focused, onOpen = { nav.navigate(archiveDetailRoute(section, it.id)) })
                        is BrowseRow.Live -> Box(Modifier.padding(start = BootDimens.PagePaddingH)) {
                            OnNowRow(row.channels, ::requester, onFeatured, onWatch = { nav.navigate("live/play/${it.id}") }, title = row.title, keyPrefix = row.prefix)
                        }
                        is BrowseRow.Apps -> Box(Modifier.padding(start = BootDimens.PagePaddingH)) {
                            FreeAppsRow(row.apps, ::requester, onFeatured, onOpen = { openFreeApp(context, it) }, title = row.title)
                        }
                    }
                }
            }
        }
    }
}

private fun liveBanner(entry: OnNow) = Banner(
    entry.now?.title ?: entry.channel.name,
    listOfNotNull("LIVE", entry.channel.name, entry.next?.title?.takeIf { it != entry.now?.title }?.let { "Next: $it" }).joinToString("   ·   "),
    entry.channel.logo,
    fit = true,
)

@Composable
private fun TitlesRow(row: BrowseRow.Titles, requester: (String) -> FocusRequester, onFocused: (String, Banner) -> Unit, onOpen: (ArchiveItem) -> Unit) {
    Column {
        Text(row.shelf.title, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = BootDimens.PagePaddingH))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 12.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                val k = titleKey(row.shelf, item)
                ArchiveCard(
                    item,
                    modifier = Modifier.leftToMenu(item == row.items.first()).focusRequester(requester(k)),
                    onFocused = { onFocused(k, item.banner()) },
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun ArchiveCard(item: ArchiveItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(BootDimens.WideCardWidth)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(CardShape),
            scale = BootCard.scale,
            border = BootCard.border,
            glow = BootCard.glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                JfImage(item.thumbUrl, Modifier.fillMaxSize(), fallback = Icons.Filled.Movie)
                ArchiveProgress.entries[item.id]?.let { r ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp).height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(r.fraction).background(BootColors.Accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary)
        item.year?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary) }
    }
}

// ---------------------------------------------------------------------------- detail

@Composable
fun FreeDetailScreen(nav: NavController, id: String) {
    var details by remember { mutableStateOf<ArchiveDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        runCatching { ArchiveClient.details(id) }
            .onSuccess { details = it }
            .onFailure { e ->
                // Offline but saved: show what we know.
                val saved = ArchiveDownloads.items[id]?.takeIf { it.done }
                if (saved != null) details = ArchiveDetails(ArchiveItem(id, saved.title, saved.year), null, null, null, "Saved on this device", true, null, null, null, null)
                else error = e.message ?: "Couldn't load this title."
            }
    }
    val d = details
    when {
        d == null && error != null -> CenteredMessage("Can't load this title", error) { fr ->
            JfButton("Try again", Icons.Filled.Replay, primary = true, modifier = Modifier.focusRequester(fr), onClick = { attempt++ })
            JfButton("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = { nav.popBackStack() })
        }
        d == null -> LoadingMessage()
        else -> FreeDetail(nav, d)
    }
}

@Composable
private fun FreeDetail(nav: NavController, d: ArchiveDetails) {
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { delay(80); if (runCatching { play.requestFocus() }.isSuccess) return@LaunchedEffect } }
    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.65f).fillMaxHeight(0.8f)) {
            JfImage(d.item.thumbUrl, Modifier.fillMaxSize(), plain = true)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to BootColors.Background, 0.55f to BootColors.Background.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to BootColors.Background)))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = BootDimens.PagePaddingH, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(d.item.title, style = MaterialTheme.typography.displaySmall, fontSize = 42.sp, color = BootColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
            val meta = listOfNotNull(d.item.year, d.runtime, d.creator?.take(60), d.license)
            Text(meta.joinToString("   ·   "), style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary)
            d.description?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = BootColors.TextPrimary.copy(alpha = 0.88f), maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 720.dp))
            }
            Spacer(Modifier.height(8.dp))
            val local = ArchiveDownloads.items[d.item.id]?.done == true
            val download = ArchiveDownloads.states[d.item.id]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val resumeAt = ArchiveProgress.entries[d.item.id]
                if (!d.cleared) {
                    Text("Hidden: archive.org doesn't list this as public domain or Creative Commons.", color = BootColors.TextSecondary)
                } else if ((d.videoUrl != null || local) && resumeAt != null) {
                    JfButton("Resume ${formatClock(resumeAt.positionMs)}", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(play),
                        onClick = { nav.navigate(freePlayRoute(d.item.id)) })
                    JfButton("Start over", Icons.Filled.Replay, onClick = { nav.navigate(freePlayRoute(d.item.id, restart = true)) })
                } else if (d.videoUrl != null || local) {
                    JfButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(play), onClick = { nav.navigate(freePlayRoute(d.item.id)) })
                } else {
                    Text("No streamable video for this title.", color = BootColors.TextSecondary)
                }
                // One button whose label follows the download, so focus stays put as it changes.
                val canDownload = d.cleared && d.torrentUrl != null && d.downloadUrl != null
                if (local || download is DownloadState.Running || canDownload) {
                    val (label, icon) = when {
                        local -> "Delete download" to Icons.Filled.Delete
                        download is DownloadState.Running -> "Cancel download" to Icons.Filled.Close
                        else -> "Download" to Icons.Filled.Download
                    }
                    JfButton(label, icon, onClick = {
                        if (local || download is DownloadState.Running) ArchiveDownloads.cancelOrDelete(d.item.id) else ArchiveDownloads.download(d)
                    })
                }
                val noPlay = !d.cleared || (d.videoUrl == null && !local)
                JfButton("Back", Icons.AutoMirrored.Filled.ArrowBack, modifier = if (noPlay) Modifier.focusRequester(play) else Modifier, onClick = { nav.popBackStack() })
            }
            DownloadStatus(local, download)
            Text("From the Internet Archive (archive.org), free and legal to watch.", style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
        }
    }
}

@Composable
private fun DownloadStatus(local: Boolean, state: DownloadState?) {
    when {
        local -> Text("Saved on this device · plays without internet", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5BD68A))
        state is DownloadState.Running -> Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val label = when {
                state.checking && state.progress == 0f -> "Starting download…"
                state.checking -> "Finishing ${(state.progress * 100).toInt()}%…"
                else -> "Downloading ${(state.progress * 100).toInt()}%  ·  ${formatRate(state.bytesPerSec)}  ·  " +
                    if (state.peers == 0) "from archive.org" else "archive.org + ${state.peers} peer${if (state.peers == 1) "" else "s"}"
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary)
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(state.progress.coerceIn(0f, 1f)).background(BootColors.Accent, RoundedCornerShape(3.dp)))
            }
            Text("Keeps going while you watch other things.", style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
        }
        state is DownloadState.Failed -> Text("Download failed: ${state.message}", style = MaterialTheme.typography.bodyMedium, color = BootColors.Live)
    }
}

private fun formatRate(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
    bps >= 1_000 -> "${bps / 1_000} KB/s"
    else -> "$bps B/s"
}

// ---------------------------------------------------------------------------- player

@Composable
fun FreePlayerScreen(nav: NavController, id: String, restart: Boolean = false) {
    val context = LocalContext.current
    var details by remember { mutableStateOf<ArchiveDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var overlay by remember { mutableStateOf(true) }
    var poked by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    val root = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) { ArchiveProgress.clear(id); nav.popBackStack() }
            }
            override fun onPlayerError(e: PlaybackException) { error = "This video couldn't be played (${e.errorCodeName})." }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (player.playbackState != Player.STATE_ENDED) details?.item?.let { ArchiveProgress.save(it, player.currentPosition, player.duration) }
            player.release()
        }
    }
    LaunchedEffect(id) {
        val local = ArchiveDownloads.localFile(id)
        if (local != null) {
            val saved = ArchiveDownloads.items.getValue(id)
            details = ArchiveDetails(ArchiveItem(id, saved.title, saved.year), null, null, null, "Saved on this device", true, null, null, null, null)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(local)))
            if (!restart) ArchiveProgress.entries[id]?.let { player.seekTo(it.positionMs) }
            player.prepare()
            player.playWhenReady = true
            return@LaunchedEffect
        }
        runCatching { ArchiveClient.details(id) }
            .onSuccess { d ->
                details = d
                val url = d.videoUrl
                if (!d.cleared) error = "archive.org doesn't list this as public domain or Creative Commons."
                else if (url == null) error = "No streamable video for this title." else {
                    player.setMediaItem(MediaItem.fromUri(url))
                    if (!restart) ArchiveProgress.entries[id]?.let { player.seekTo(it.positionMs) }
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            .onFailure { error = it.message ?: "Couldn't load this title." }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition; duration = player.duration.coerceAtLeast(0L)
            // Remember the spot every 10 s while playing, so a power cut doesn't lose it.
            if (++tick % 20 == 0 && player.isPlaying) details?.item?.let { ArchiveProgress.save(it, position, duration) }
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
    BackHandler { nav.popBackStack() }
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
        if (buffering && error == null) {
            Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text("Loading…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000))) {
                CenteredMessage("Can't play this video", msg) { fr ->
                    JfButton("Back", Icons.AutoMirrored.Filled.ArrowBack, primary = true, modifier = Modifier.focusRequester(fr), onClick = { nav.popBackStack() })
                }
            }
        }
        AnimatedVisibility(error == null && (overlay || seekTarget != null), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            val pos = seekTarget ?: position
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
                Text(details?.item?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playing) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text(formatClock(pos), color = if (seekTarget != null) BootColors.Accent else Color.White, style = MaterialTheme.typography.titleMedium)
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
