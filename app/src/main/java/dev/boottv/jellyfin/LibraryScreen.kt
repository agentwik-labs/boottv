package dev.boottv.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal enum class LibrarySort(val label: String, val sortBy: String, val order: String) {
    Name("A–Z", "SortName", "Ascending"),
    Added("Date added", "DateCreated,SortName", "Descending"),
    Rating("Rating", "CommunityRating,SortName", "Descending"),
    Year("Year", "ProductionYear,SortName", "Descending"),
}

internal class LibraryViewModel : ViewModel() {
    internal var client by mutableStateOf<JellyfinClient?>(null)
    internal var folder by mutableStateOf<BaseItem?>(null)
    internal var items by mutableStateOf<List<BaseItem>>(emptyList())
    internal var total by mutableStateOf(0)
    internal var sort by mutableStateOf(LibrarySort.Name)
    internal var loading by mutableStateOf(false)
    internal var error by mutableStateOf<String?>(null)
    internal var lastFocusedIndex = -1
    private var parentId: String? = null
    private var pageJob: Job? = null

    private val pageSize = 60

    fun init(id: String) {
        if (parentId == id && (items.isNotEmpty() || loading)) return
        parentId = id
        reload()
    }

    fun setSort(s: LibrarySort) {
        if (s == sort) return
        sort = s
        lastFocusedIndex = -1
        reload()
    }

    fun reload() {
        pageJob?.cancel()
        items = emptyList(); total = 0; error = null
        pageJob = viewModelScope.launch {
            loading = true
            try {
                val c = JellyfinClient.current() ?: run { error = "Not signed in to a media server."; return@launch }
                client = c
                if (folder?.id != parentId) folder = c.item(parentId!!)
                fetchPage(c, 0)
            } catch (e: Exception) {
                error = e.friendly(client?.server)
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val c = client ?: return
        if (loading || items.size >= total) return
        pageJob = viewModelScope.launch {
            loading = true
            try { fetchPage(c, items.size) } catch (e: Exception) { error = e.friendly(c.server) } finally { loading = false }
        }
    }

    private suspend fun fetchPage(c: JellyfinClient, start: Int) {
        val f = folder
        val (recursive, types) = when (f?.collectionType) {
            "movies" -> true to "Movie"
            "tvshows" -> true to "Series"
            "boxsets" -> true to "BoxSet"
            "musicvideos" -> true to "MusicVideo"
            else -> false to null
        }
        val result = c.items(
            "ParentId" to parentId,
            "Recursive" to recursive,
            "IncludeItemTypes" to types,
            "SortBy" to sort.sortBy,
            "SortOrder" to sort.order,
            "StartIndex" to start,
            "Limit" to pageSize,
            "EnableTotalRecordCount" to true,
        )
        items = if (start == 0) result.items else items + result.items.filter { n -> items.none { it.id == n.id } }
        total = result.totalRecordCount
    }
}

@Composable
fun LibraryScreen(nav: NavController, id: String) {
    val vm: LibraryViewModel = viewModel(key = "library-$id")
    LaunchedEffect(id) { vm.init(id) }
    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= vm.items.size - 20) vm.loadMore() }
    }
    LaunchedEffect(vm.items.isNotEmpty()) {
        if (vm.items.isNotEmpty()) {
            delay(50)
            if (runCatching { firstFocus.requestFocus() }.isFailure) runCatching { sortFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().background(BootColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = BootDimens.PagePaddingH, end = BootDimens.PagePaddingH, top = BootDimens.PagePaddingV, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(vm.folder?.name ?: "Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BootColors.TextPrimary)
                if (vm.total > 0) Text("${vm.total} titles", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LibrarySort.entries.forEachIndexed { i, s ->
                    Chip(s.label, selected = vm.sort == s, onClick = { vm.setSort(s) },
                        modifier = if (i == 0) Modifier.focusRequester(sortFocus) else Modifier)
                }
            }
        }

        val client = vm.client
        when {
            vm.error != null && vm.items.isEmpty() -> CenteredMessage("Couldn't load this library", vm.error) { fr ->
                JfButton("Retry", Icons.Filled.Refresh, primary = true, onClick = { vm.reload() }, modifier = Modifier.focusRequester(fr))
            }
            client == null || (vm.items.isEmpty() && vm.loading) -> LoadingMessage()
            vm.items.isEmpty() -> CenteredMessage("Nothing here yet", "This library has no items.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(BootDimens.PosterCardWidth + 8.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
                verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
            ) {
                itemsIndexed(vm.items, key = { _, it -> it.id }) { index, item ->
                    val restoreIdx = if (vm.lastFocusedIndex >= 0) vm.lastFocusedIndex else 0
                    MediaCard(
                        item = item,
                        client = client,
                        wide = false,
                        onClick = { openItem(nav, item) },
                        onFocused = { vm.lastFocusedIndex = index },
                        focusRequester = if (index == restoreIdx) firstFocus else null,
                    )
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
