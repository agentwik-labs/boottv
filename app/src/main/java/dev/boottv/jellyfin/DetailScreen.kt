package dev.boottv.jellyfin

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DetailViewModel : ViewModel() {
    internal var client by mutableStateOf<JellyfinClient?>(null)
    internal var item by mutableStateOf<BaseItem?>(null)
    internal var error by mutableStateOf<String?>(null)
    internal var seasons by mutableStateOf<List<BaseItem>>(emptyList())
    internal var selectedSeason by mutableStateOf<String?>(null)
    internal var episodes by mutableStateOf<List<BaseItem>>(emptyList())
    internal var nextUp by mutableStateOf<BaseItem?>(null)
    internal var similar by mutableStateOf<List<BaseItem>>(emptyList())
    internal var children by mutableStateOf<List<BaseItem>>(emptyList())
    internal var focusedEpisode by mutableStateOf<BaseItem?>(null)
    private var itemId: String? = null

    fun load(id: String) {
        itemId = id
        viewModelScope.launch {
            error = null
            try {
                val c = JellyfinClient.current() ?: run { error = "Not signed in to a media server."; return@launch }
                client = c
                coroutineScope {
                    val itemD = async { c.item(id) }
                    val simD = async { runCatching { c.similar(id) }.getOrDefault(emptyList()) }
                    val it = itemD.await()
                    item = it
                    when {
                        it.isSeries -> {
                            val s = runCatching { c.seasons(id) }.getOrDefault(emptyList())
                            seasons = s
                            val nu = runCatching { c.nextUp(1, id).firstOrNull() }.getOrNull()
                            nextUp = nu
                            val target = selectedSeason?.takeIf { sel -> s.any { it.id == sel } }
                                ?: nu?.seasonId?.takeIf { sid -> s.any { it.id == sid } }
                                ?: s.firstOrNull()?.id
                            if (target != null) loadEpisodes(c, id, target)
                        }
                        it.isSeason && it.seriesId != null -> {
                            episodes = runCatching { c.episodes(it.seriesId, seasonId = it.id) }.getOrDefault(emptyList())
                        }
                        it.type == "BoxSet" -> {
                            children = runCatching { c.items("ParentId" to id, "SortBy" to "ProductionYear,SortName").items }.getOrDefault(emptyList())
                        }
                    }
                    similar = simD.await()
                }
            } catch (e: Exception) {
                if (item == null) error = e.friendly(client?.server)
            }
        }
    }

    private suspend fun loadEpisodes(c: JellyfinClient, seriesId: String, seasonId: String) {
        selectedSeason = seasonId
        episodes = runCatching { c.episodes(seriesId, seasonId = seasonId) }.getOrDefault(emptyList())
    }

    fun selectSeason(seasonId: String) {
        val c = client ?: return
        val sid = item?.id ?: return
        if (seasonId == selectedSeason) return
        viewModelScope.launch { loadEpisodes(c, sid, seasonId) }
    }

    fun togglePlayed() {
        val c = client ?: return
        val it = item ?: return
        val newValue = it.userData?.played != true
        viewModelScope.launch {
            runCatching { c.setPlayed(it.id, newValue) }.onSuccess { ud -> item = it.copy(userData = ud) }
            if (it.isSeries || it.isSeason) itemId?.let { id -> load(id) }
        }
    }

    fun toggleFavorite() {
        val c = client ?: return
        val it = item ?: return
        val newValue = it.userData?.isFavorite != true
        viewModelScope.launch {
            runCatching { c.setFavorite(it.id, newValue) }.onSuccess { ud -> item = it.copy(userData = ud) }
        }
    }
}

@Composable
fun DetailScreen(nav: NavController, id: String) {
    val vm: DetailViewModel = viewModel(key = "detail-$id")
    // Reload on every visit so watched state / resume points are fresh after playback.
    LaunchedEffect(id) { vm.load(id) }

    val item = vm.item
    val client = vm.client
    Box(Modifier.fillMaxSize().background(BootColors.Background)) {
        when {
            vm.error != null -> CenteredMessage("Couldn't load this title", vm.error) { fr ->
                JfButton("Retry", Icons.Filled.Refresh, primary = true, onClick = { vm.load(id) }, modifier = Modifier.focusRequester(fr))
            }
            item == null || client == null -> LoadingMessage()
            else -> DetailContent(nav, vm, item, client)
        }
    }
}

@Composable
private fun DetailContent(nav: NavController, vm: DetailViewModel, item: BaseItem, client: JellyfinClient) {
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        if (!focusedOnce) {
            delay(80)
            focusedOnce = runCatching { playFocus.requestFocus() }.isSuccess
        }
    }

    Box(Modifier.fillMaxSize()) {
        client.backdropUrl(item, 1600)?.let { url ->
            JfImage(url, Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f).align(androidx.compose.ui.Alignment.TopEnd), plain = true)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to BootColors.Background, 0.7f to Color(0x660B0B14))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.85f to BootColors.Background)))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = BootDimens.PagePaddingV, bottom = BootDimens.PagePaddingV + 32.dp),
            verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = BootDimens.PagePaddingH).widthIn(max = 640.dp).heightIn(min = 300.dp)) {
                    val logo = client.logoUrl(item)
                    if (item.isEpisode && item.seriesName != null) {
                        Text(item.seriesName, style = MaterialTheme.typography.titleLarge, color = BootColors.TextSecondary)
                    }
                    if (logo != null && !item.isEpisode) {
                        JfImage(logo, Modifier.height(100.dp).widthIn(max = 420.dp).fillMaxWidth(), contentScale = ContentScale.Fit, plain = true, alignment = androidx.compose.ui.Alignment.CenterStart)
                    } else {
                        Text(item.name.orEmpty(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(metaLine(item), style = MaterialTheme.typography.titleSmall, color = BootColors.TextSecondary)
                    item.genres?.takeIf { it.isNotEmpty() }?.let {
                        Text(it.take(4).joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
                    }
                    item.taglines?.firstOrNull()?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = BootColors.TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = BootColors.TextPrimary,
                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                    if (item.progress > 0f) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            ProgressBar(item.progress, Modifier.widthIn(max = 240.dp).fillMaxWidth())
                            Text("  " + (item.remainingText ?: ""), style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    DetailButtons(nav, vm, item, playFocus, onPlay = { target ->
                        scope.launch {
                            resolvePlayTarget(client, target)?.let { (pid, t) -> nav.navigate(playRoute(pid, t)) }
                        }
                    })
                }
            }

            if (item.isSeries && vm.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH),
                    ) {
                        items(vm.seasons, key = { it.id }) { season ->
                            Chip(season.name ?: "Season ${season.indexNumber ?: ""}", selected = season.id == vm.selectedSeason,
                                onClick = { vm.selectSeason(season.id) }, onFocused = { vm.selectSeason(season.id) })
                        }
                    }
                }
            }
            if ((item.isSeries || item.isSeason) && vm.episodes.isNotEmpty()) {
                item(key = "episodes") {
                    EpisodeList(vm.episodes, client, vm, onPlay = { ep -> nav.navigate(playRoute(ep.id, ep.resumeTicks)) },
                        onInfo = { ep -> nav.navigate(detailRoute(ep.id)) })
                }
            }
            if (vm.children.isNotEmpty()) {
                item(key = "children") {
                    MediaRow("In this collection", vm.children, client, wide = false, onClick = { openItem(nav, it) })
                }
            }
            if (vm.similar.isNotEmpty()) {
                item(key = "similar") {
                    MediaRow("More Like This", vm.similar, client, wide = false, onClick = { openItem(nav, it) })
                }
            }
        }
    }
}

@Composable
private fun DetailButtons(
    nav: NavController,
    vm: DetailViewModel,
    item: BaseItem,
    playFocus: FocusRequester,
    onPlay: (BaseItem) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            item.isSeries -> {
                val nu = vm.nextUp
                val label = when {
                    nu == null -> "Play"
                    nu.resumeTicks > 0 -> "Resume ${nu.episodeLabel ?: ""}".trim()
                    else -> "Play ${nu.episodeLabel ?: ""}".trim()
                }
                JfButton(label, Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onClick = {
                    if (nu != null) nav.navigate(playRoute(nu.id, nu.resumeTicks)) else onPlay(item)
                })
            }
            item.type == "BoxSet" -> {
                val first = vm.children.firstOrNull()
                JfButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus),
                    enabled = first != null, onClick = { first?.let { onPlay(it) } })
            }
            item.isSeason -> {
                JfButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onClick = { onPlay(item) })
            }
            else -> {
                val resume = item.resumeTicks
                if (resume > 0) {
                    JfButton("Resume from ${formatClock(resume / TICKS_PER_MS)}", Icons.Filled.PlayArrow, primary = true,
                        modifier = Modifier.focusRequester(playFocus), onClick = { nav.navigate(playRoute(item.id, resume)) })
                    JfButton("Restart", Icons.Filled.Replay, onClick = { nav.navigate(playRoute(item.id, 0)) })
                } else {
                    JfButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus),
                        onClick = { nav.navigate(playRoute(item.id, 0)) })
                }
            }
        }
        if (item.type != "BoxSet") {
            val played = item.userData?.played == true
            JfButton(if (played) "Watched" else "Mark watched", if (played) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                onClick = { vm.togglePlayed() })
        }
        val fav = item.userData?.isFavorite == true
        JfButton(if (fav) "Favorite" else "Add to favorites", if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            onClick = { vm.toggleFavorite() })
        if (item.isEpisode && item.seriesId != null) {
            JfButton("Go to series", Icons.AutoMirrored.Filled.ArrowForward, onClick = { nav.navigate(detailRoute(item.seriesId)) })
        }
    }
}

@Composable
private fun EpisodeList(
    episodes: List<BaseItem>,
    client: JellyfinClient,
    vm: DetailViewModel,
    onPlay: (BaseItem) -> Unit,
    onInfo: (BaseItem) -> Unit,
) {
    val focused = vm.focusedEpisode?.takeIf { f -> episodes.any { it.id == f.id } }
    Column(Modifier.fillMaxWidth()) {
        Text("Episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BootColors.TextPrimary,
            modifier = Modifier.padding(start = BootDimens.PagePaddingH, bottom = 10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 8.dp),
        ) {
            itemsIndexed(episodes, key = { _, it -> it.id }) { _, ep ->
                MediaCard(
                    item = ep,
                    client = client,
                    wide = true,
                    onClick = { onPlay(ep) },
                    onLongClick = { onInfo(ep) },
                    onFocused = { vm.focusedEpisode = ep },
                    showText = false,
                )
            }
        }
        val shown = focused ?: episodes.firstOrNull { it.userData?.played != true } ?: episodes.first()
        Column(Modifier.padding(horizontal = BootDimens.PagePaddingH).widthIn(max = 760.dp)) {
            Text(
                listOfNotNull(shown.episodeLabel, shown.name).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(listOfNotNull(shown.runtimeText, shown.remainingText, if (shown.userData?.played == true) "Watched" else null).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(shown.overview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("OK to play · hold OK for details", style = MaterialTheme.typography.labelSmall, color = BootColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
