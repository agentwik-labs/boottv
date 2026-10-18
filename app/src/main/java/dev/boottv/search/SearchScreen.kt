package dev.boottv.search

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.speech.RecognizerIntent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.boottv.core.SearchAction
import dev.boottv.core.SearchProvider
import dev.boottv.core.SearchRegistry
import dev.boottv.core.SearchResult
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.BootTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val DEBOUNCE_MS = 300L
private const val PROVIDER_TIMEOUT_MS = 5_000L

private data class ProviderRow(val items: List<SearchResult>, val loading: Boolean)

private val KEY_ROWS = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890")
private val KEY_W = 54.dp
private val KEY_H = 44.dp
private val KEY_GAP = 6.dp

@Composable
fun SearchScreen(nav: NavController) {
    BootTheme { SearchContent(nav) }
}

@Composable
private fun SearchContent(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyStore = remember { SearchHistoryStore(context) }
    val recent by historyStore.recent.collectAsState(initial = emptyList())
    val providers: List<SearchProvider> = remember { runCatching { SearchRegistry.providers }.getOrDefault(emptyList()) }

    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val rows = remember { mutableStateMapOf<Int, ProviderRow>() }
    val firstKeyFocus = remember { FocusRequester() }

    // Provider work runs here so a provider that blocks its thread can't stall the timeout.
    val ioScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    DisposableEffect(Unit) { onDispose { ioScope.cancel() } }

    fun saveRecent(q: String = query) {
        if (q.isNotBlank()) scope.launch { historyStore.add(q) }
    }

    val voiceAvailable = remember {
        runCatching {
            context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
        }.getOrDefault(false)
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            query = text
            saveRecent(text)
        }
    }
    val startVoice: () -> Unit = {
        runCatching {
            voiceLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search BootTV")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstKeyFocus.requestFocus() }
    }

    // Debounced fan-out to every provider, each with its own timeout and error isolation.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            rows.clear()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        providers.forEachIndexed { i, _ ->
            rows[i] = rows[i]?.copy(loading = true) ?: ProviderRow(emptyList(), loading = true)
        }
        providers.forEachIndexed { i, provider ->
            launch {
                val deferred = ioScope.async {
                    try {
                        provider.search(q)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        emptyList()
                    }
                }
                val items = try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { deferred.await() } ?: emptyList()
                } finally {
                    deferred.cancel()
                }
                rows[i] = ProviderRow(items, loading = false)
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(BootColors.Background)
            .padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV)
            .onPreviewKeyEvent { e ->
                if (editing) return@onPreviewKeyEvent false
                val native = e.nativeKeyEvent
                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_SEARCH || native.keyCode == AndroidKeyEvent.KEYCODE_VOICE_ASSIST -> {
                        if (e.type == KeyEventType.KeyDown && voiceAvailable) startVoice()
                        voiceAvailable
                    }
                    native.keyCode == AndroidKeyEvent.KEYCODE_DEL -> {
                        if (e.type == KeyEventType.KeyDown) query = query.dropLast(1)
                        true
                    }
                    else -> {
                        val ch = native.unicodeChar
                        if (ch != 0 && (Character.isLetterOrDigit(ch) || ch == ' '.code)) {
                            if (e.type == KeyEventType.KeyDown) query += String(Character.toChars(ch)).lowercase()
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        // ---------------- left: query + on-screen keyboard
        Column(Modifier.width(KEY_W * 6 + KEY_GAP * 5)) {
            QueryBox(
                query = query,
                editing = editing,
                onStartEditing = { editing = true },
                onQueryChange = { query = it },
                onDone = {
                    editing = false
                    saveRecent()
                    runCatching { firstKeyFocus.requestFocus() }
                },
            )
            Spacer(Modifier.height(14.dp))
            KEY_ROWS.forEachIndexed { r, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    keys.forEachIndexed { c, ch ->
                        KeyButton(
                            width = KEY_W,
                            modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKeyFocus) else Modifier,
                            onClick = { query += ch.lowercaseChar() },
                        ) {
                            Text(ch.toString(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(KEY_GAP))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                KeyButton(width = KEY_W * 2 + KEY_GAP, onClick = { if (query.isNotEmpty()) query += " " }) {
                    Icon(Icons.Filled.SpaceBar, "Space")
                }
                KeyButton(width = KEY_W, onClick = { query = query.dropLast(1) }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                }
                KeyButton(width = KEY_W, onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, "Clear")
                }
                if (voiceAvailable) {
                    KeyButton(width = KEY_W, onClick = startVoice, accent = true) {
                        Icon(Icons.Filled.Mic, "Voice search")
                    }
                }
                KeyButton(width = KEY_W, onClick = { editing = true }) {
                    Icon(Icons.Filled.Keyboard, "System keyboard")
                }
            }
        }

        Spacer(Modifier.width(36.dp))

        // ---------------- right: recents or results
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (query.isBlank()) {
                RecentSearches(
                    recent = recent,
                    onPick = { query = it },
                    onClear = { scope.launch { historyStore.clear() } },
                )
            } else {
                ResultsList(
                    query = query.trim(),
                    providers = providers,
                    rows = rows,
                    onResult = { result ->
                        saveRecent()
                        performSearchAction(context, nav, result.action)
                    },
                    onYouTube = { saveRecent(); searchYouTube(context, nav, query) },
                    onWeb = { saveRecent(); searchWeb(nav, query) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- left column pieces

@Composable
private fun QueryBox(
    query: String,
    editing: Boolean,
    onStartEditing: () -> Unit,
    onQueryChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    if (editing) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var hadFocus by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        BackHandler { keyboard?.hide(); onDone() }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = BootColors.TextPrimary, fontSize = 20.sp),
            cursorBrush = SolidColor(BootColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { keyboard?.hide(); onDone() },
                onDone = { keyboard?.hide(); onDone() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusRequester(focus)
                // With the keyboard closed, Up/Down leave the field (losing focus ends editing).
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) || run { onDone(); true } }
                        Key.DirectionUp -> { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Up) || run { onDone(); true } }
                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) onDone()
                }
                .background(BootColors.SurfaceRaised, shape)
                .border(BorderStroke(2.dp, BootColors.Accent), shape)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        )
    } else {
        Surface(
            onClick = onStartEditing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = BootColors.SurfaceRaised,
                contentColor = BootColors.TextPrimary,
                focusedContainerColor = BootColors.SurfaceRaised,
                focusedContentColor = BootColors.TextPrimary,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, BootColors.AccentSoft), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, BootColors.Accent), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, Modifier.size(22.dp), tint = BootColors.TextSecondary)
                Spacer(Modifier.width(10.dp))
                if (query.isEmpty()) {
                    Text("Search apps, movies, live TV…", color = BootColors.TextSecondary, fontSize = 17.sp, maxLines = 1)
                } else {
                    Text(query, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.padding(start = 2.dp).width(2.dp).height(24.dp).background(BootColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width, KEY_H),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (accent) BootColors.AccentSoft else BootColors.Surface,
            contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Accent,
            focusedContentColor = Color.White,
            pressedContainerColor = BootColors.Accent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

// ---------------------------------------------------------------------------- right column

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item {
            Text("Recent searches", style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        if (recent.isEmpty()) {
            item {
                Text(
                    "Type with the keyboard on the left, use the system keyboard, or press the mic to search " +
                        "your apps, media library, live channels, YouTube and the web.",
                    color = BootColors.TextSecondary,
                    modifier = Modifier.width(520.dp),
                )
            }
        }
        items(recent, key = { it }) { q ->
            ListButton(icon = Icons.Filled.History, text = q, onClick = { onPick(q) })
        }
        if (recent.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                ListButton(icon = Icons.Filled.Close, text = "Clear recent searches", onClick = onClear, dim = true)
            }
        }
    }
}

@Composable
private fun ListButton(icon: ImageVector, text: String, onClick: () -> Unit, dim: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(520.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (dim) BootColors.TextSecondary else BootColors.TextPrimary,
            focusedContainerColor = BootColors.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultsList(
    query: String,
    providers: List<SearchProvider>,
    rows: Map<Int, ProviderRow>,
    onResult: (SearchResult) -> Unit,
    onYouTube: () -> Unit,
    onWeb: () -> Unit,
) {
    val anyLoading = rows.values.any { it.loading }
    val anyResults = rows.values.any { it.items.isNotEmpty() }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    ) {
        providers.forEachIndexed { i, provider ->
            val row = rows[i] ?: return@forEachIndexed
            if (row.items.isEmpty() && !row.loading) return@forEachIndexed
            item(key = "p$i") {
                Column {
                    RowHeader(provider.name, if (row.loading) "Searching…" else null)
                    if (row.items.isEmpty()) {
                        Box(Modifier.height(48.dp))
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            items(row.items, key = { it.id }) { r -> ResultCard(r) { onResult(r) } }
                        }
                    }
                }
            }
        }
        if (!anyLoading && !anyResults) {
            item(key = "none") {
                Text("No matches in BootTV for “$query”", color = BootColors.TextSecondary, fontSize = 18.sp)
            }
        }
        item(key = "elsewhere") {
            Column {
                RowHeader("Search elsewhere", null)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    item {
                        ElsewhereCard("YouTube", "Search “$query”", Icons.Filled.PlayArrow,
                            Brush.linearGradient(listOf(Color(0xFFE62117), Color(0xFF8E0E07))), onYouTube)
                    }
                    item {
                        ElsewhereCard("Web", "DuckDuckGo “$query”", Icons.Filled.Language,
                            Brush.linearGradient(listOf(BootColors.Accent, Color(0xFF3A2A8C))), onWeb)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowHeader(title: String, status: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
        if (status != null) {
            Spacer(Modifier.width(12.dp))
            Text(status, fontSize = 13.sp, color = BootColors.TextSecondary)
        }
    }
}

/** For app results without artwork, use the app's TV banner (or icon) from PackageManager. */
private fun appArtwork(context: Context, action: SearchAction): Drawable? {
    val pkg = (action as? SearchAction.LaunchApp)?.packageName ?: return null
    val pm = context.packageManager
    return runCatching {
        val info = pm.getApplicationInfo(pkg, 0)
        info.loadBanner(pm) ?: pm.getActivityBanner(pm.getLeanbackLaunchIntentForPackage(pkg) ?: Intent())
            ?: info.loadIcon(pm)
    }.getOrNull()
}

@Composable
private fun ResultCard(result: SearchResult, onClick: () -> Unit) {
    val context = LocalContext.current
    val width = if (result.wide) BootDimens.WideCardWidth else BootDimens.PosterCardWidth
    val ratio = if (result.wide) 16f / 9f else 2f / 3f
    val model: Any? = remember(result.id, result.imageUrl) { result.imageUrl ?: appArtwork(context, result.action) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = BootColors.SurfaceRaised,
                focusedContainerColor = BootColors.SurfaceRaised,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize()) {
                FallbackTile(result.title)
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = result.title,
                        contentScale = if (model is Drawable && result.imageUrl == null) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            result.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            color = if (focused) BootColors.TextPrimary else BootColors.TextSecondary,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (!result.subtitle.isNullOrBlank()) {
            Text(result.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                color = BootColors.TextSecondary)
        }
    }
}

@Composable
private fun FallbackTile(title: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(BootColors.SurfaceRaised, BootColors.AccentSoft))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = BootColors.TextPrimary,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun ElsewhereCard(title: String, subtitle: String, icon: ImageVector, brush: Brush, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(BootDimens.WideCardWidth)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(36.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            color = if (focused) BootColors.TextPrimary else BootColors.TextSecondary)
    }
}
