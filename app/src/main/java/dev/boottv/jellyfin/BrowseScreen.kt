package dev.boottv.jellyfin

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class BrowseRow(val key: String, val title: String, val items: List<BaseItem>, val wide: Boolean, val playOnClick: Boolean = false)

internal sealed interface BrowseState {
    data object Loading : BrowseState
    data object NotConfigured : BrowseState
    data class Error(val message: String, val auth: Boolean) : BrowseState
    data class Ready(val client: JellyfinClient, val rows: List<BrowseRow>) : BrowseState
}

internal class BrowseViewModel : ViewModel() {
    internal var state by mutableStateOf<BrowseState>(BrowseState.Loading)
        private set
    internal var hero by mutableStateOf<BaseItem?>(null)
    internal var lastFocusedKey: String? = null
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val client = JellyfinClient.current()
            if (client == null) { state = BrowseState.NotConfigured; return@launch }
            if (state !is BrowseState.Ready) state = BrowseState.Loading
            try {
                val rows = load(client)
                state = BrowseState.Ready(client, rows)
                if (hero == null || rows.none { r -> r.items.any { it.id == hero?.id } }) {
                    hero = (rows.firstOrNull { !it.key.startsWith("libs") } ?: rows.firstOrNull())?.items?.firstOrNull()
                }
            } catch (e: Exception) {
                if (state !is BrowseState.Ready) state = BrowseState.Error(e.friendly(client.server), e.isAuthError)
            }
        }
    }

    private suspend fun load(client: JellyfinClient): List<BrowseRow> = coroutineScope {
        val viewsD = async { client.views() }
        val resumeD = async { runCatching { client.resume() }.getOrDefault(emptyList()) }
        val nextUpD = async { runCatching { client.nextUp() }.getOrDefault(emptyList()) }
        val views = viewsD.await()
        val videoViews = views.filter { it.collectionType in setOf(null, "movies", "tvshows", "homevideos", "mixed", "boxsets", "musicvideos") }
        val latest = videoViews.map { v ->
            async { v to runCatching { client.latest(v.id) }.getOrDefault(emptyList()) }
        }.awaitAll()
        buildList {
            resumeD.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("resume", "Continue Watching", it, wide = true, playOnClick = true)) }
            nextUpD.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("nextup", "Next Up", it, wide = true, playOnClick = true)) }
            latest.forEach { (v, items) ->
                if (items.isNotEmpty()) add(BrowseRow("latest-${v.id}", "Recently Added in ${v.name}", items, wide = false))
            }
            if (views.isNotEmpty()) add(BrowseRow("libs", "My Libraries", views, wide = true))
        }
    }
}

/** Resolves what "Play" means for an item: series → next up episode, otherwise the item itself (with resume). */
internal suspend fun resolvePlayTarget(client: JellyfinClient, item: BaseItem): Pair<String, Long>? = when {
    item.isSeries -> {
        val next = runCatching { client.nextUp(1, item.id).firstOrNull() }.getOrNull()
            ?: runCatching { client.episodes(item.id).let { eps -> eps.firstOrNull { it.userData?.played != true } ?: eps.firstOrNull() } }.getOrNull()
        next?.let { it.id to it.resumeTicks }
    }
    item.isSeason -> {
        val sid = item.seriesId
        val eps = if (sid != null) runCatching { client.episodes(sid, seasonId = item.id) }.getOrDefault(emptyList()) else emptyList()
        (eps.firstOrNull { it.userData?.played != true } ?: eps.firstOrNull())?.let { it.id to it.resumeTicks }
    }
    item.isBrowsableFolder || item.type == "BoxSet" -> null
    else -> item.id to item.resumeTicks
}

internal fun playRoute(id: String, startTicks: Long = 0) = "media/play/$id?start=$startTicks"
internal fun detailRoute(id: String) = "media/detail/$id"
internal fun libraryRoute(id: String) = "media/library/$id"

internal fun openItem(nav: NavController, item: BaseItem) {
    if (item.isBrowsableFolder) nav.navigate(libraryRoute(item.id)) else nav.navigate(detailRoute(item.id))
}

@Composable
fun BrowseScreen(nav: NavController) {
    val vm: BrowseViewModel = viewModel()
    LaunchedEffect(Unit) { vm.refresh() }

    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        when (val s = vm.state) {
            BrowseState.Loading -> LoadingMessage("Loading your media…")
            BrowseState.NotConfigured -> NotConfiguredState(nav)
            is BrowseState.Error -> CenteredMessage(
                title = if (s.auth) "Sign in needed" else "Media server offline",
                message = s.message,
            ) { fr ->
                if (!s.auth) JfButton("Retry", Icons.Filled.Refresh, onClick = { vm.refresh() }, primary = true, modifier = Modifier.focusRequester(fr))
                JfButton("Settings", Icons.Filled.Settings, onClick = { nav.navigate(Routes.SETTINGS) },
                    modifier = if (s.auth) Modifier.focusRequester(fr) else Modifier)
            }
            is BrowseState.Ready -> if (s.rows.isEmpty()) {
                CenteredMessage("Your libraries are empty", "Add media folders as libraries in the Jellyfin dashboard on your Mac, then refresh.") { fr ->
                    JfButton("Refresh", Icons.Filled.Refresh, onClick = { vm.refresh() }, primary = true, modifier = Modifier.focusRequester(fr))
                }
            } else {
                BrowseContent(nav, vm, s)
            }
        }
    }
}


@Composable
private fun NotConfiguredState(nav: NavController) {
    CenteredMessage(
        title = "Set up My Media",
        message = "Stream your own movies and shows from your Mac, ad-free.\n\n" +
            "1. On your Mac, download Jellyfin Server from jellyfin.org/downloads and open it.\n" +
            "2. In the browser page it opens (localhost:8096), create a user and add your Movies and TV folders as libraries.\n" +
            "3. Keep the Mac awake and on the same Wi-Fi as this TV.\n" +
            "4. Open Settings → My Media here and pick your server.",
    ) { fr ->
        JfButton("Open Settings", Icons.Filled.Settings, primary = true, onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.focusRequester(fr))
    }
}

@Composable
private fun BrowseContent(nav: NavController, vm: BrowseViewModel, s: BrowseState.Ready) {
    val client = s.client
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pendingHero by remember { mutableStateOf<BaseItem?>(null) }
    val restoreFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }

    // Debounce hero changes so quickly scrolling through a row doesn't thrash images.
    LaunchedEffect(pendingHero) {
        val p = pendingHero ?: return@LaunchedEffect
        delay(300)
        vm.hero = p
    }
    LaunchedEffect(Unit) {
        delay(50)
        val restored = vm.lastFocusedKey != null && runCatching { restoreFocus.requestFocus() }.isSuccess
        if (!restored) runCatching { heroPlayFocus.requestFocus() }
    }

    val hero = vm.hero
    Box(Modifier.fillMaxSize()) {
        // Backdrop
        Crossfade(targetState = hero?.let { client.backdropUrl(it) }, animationSpec = tween(500), label = "backdrop",
            modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.7f).align(Alignment.TopEnd)) { url ->
            if (url != null) JfImage(url, Modifier.fillMaxSize(), plain = true)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.25f to BootColors.Background, 0.6f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.7f to BootColors.Background)))

        Column(Modifier.fillMaxSize()) {
            // Hero info
            Column(
                Modifier.padding(start = BootDimens.PagePaddingH, top = BootDimens.PagePaddingV, end = BootDimens.PagePaddingH)
                    .widthIn(max = 560.dp).heightIn(min = 230.dp)
            ) {
                if (hero != null) {
                    val logo = client.logoUrl(hero)
                    if (logo != null) {
                        JfImage(logo, Modifier.height(80.dp).widthIn(max = 360.dp).fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.Fit, plain = true, alignment = androidx.compose.ui.Alignment.CenterStart)
                    } else {
                        Text(hero.cardTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (hero.isEpisode) {
                        Text(hero.cardSubtitle.orEmpty(), style = MaterialTheme.typography.titleSmall, color = BootColors.TextPrimary, maxLines = 1)
                    }
                    Text(metaLine(hero), style = MaterialTheme.typography.labelLarge, color = BootColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(hero.overview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val playable = !hero.isBrowsableFolder && hero.type != "BoxSet"
                        if (playable) {
                            JfButton(if (hero.resumeTicks > 0) "Resume" else "Play", Icons.Filled.PlayArrow, primary = true,
                                modifier = Modifier.focusRequester(heroPlayFocus),
                                onClick = {
                                    scope.launch {
                                        resolvePlayTarget(client, hero)?.let { (id, t) -> nav.navigate(playRoute(id, t)) }
                                            ?: openItem(nav, hero)
                                    }
                                })
                        }
                        JfButton(if (hero.isBrowsableFolder) "Open" else "More Info", Icons.Filled.Info,
                            modifier = if (!playable) Modifier.focusRequester(heroPlayFocus) else Modifier,
                            onClick = { openItem(nav, hero) })
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 12.dp, bottom = BootDimens.PagePaddingV + 24.dp),
                verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
            ) {
                items(s.rows, key = { it.key }) { row ->
                    MediaRow(
                        title = row.title,
                        items = row.items,
                        client = client,
                        wide = row.wide,
                        onClick = { item ->
                            if (row.playOnClick) nav.navigate(playRoute(item.id, item.resumeTicks)) else openItem(nav, item)
                        },
                        onLongClick = { item -> openItem(nav, item) },
                        onFocused = { item ->
                            vm.lastFocusedKey = row.key + "/" + item.id
                            if (row.key != "libs") pendingHero = item
                        },
                        focusKey = vm.lastFocusedKey?.takeIf { it.startsWith(row.key + "/") }?.substringAfter('/'),
                        focusRequester = restoreFocus,
                    )
                }
            }
        }
    }
}
