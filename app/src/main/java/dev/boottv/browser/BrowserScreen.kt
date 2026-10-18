package dev.boottv.browser

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.BootTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun BrowserScreen(nav: NavController, initialUrl: String?) {
    BootTheme {
        BrowserContent(nav, initialUrl)
    }
}

@Composable
private fun BrowserContent(nav: NavController, initialUrl: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserStore(context) }
    // Survives leaving the browser and coming back via the back stack.
    var lastUrl by rememberSaveable { mutableStateOf(initialUrl) }
    val state = remember {
        BrowserState(context, store, scope, onExit = {
            if (!nav.popBackStack()) nav.navigate(Routes.HOME)
        })
    }
    val bookmarks by store.bookmarks.collectAsState(initial = BrowserStore.DEFAULT_BOOKMARKS)
    val history by store.history.collectAsState(initial = emptyList())

    DisposableEffect(Unit) { onDispose { state.destroy() } }
    BackHandler { state.handleBack() }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        state.adBlock = s.adBlock
        AdBlocker.enabled = s.adBlock
        state.desktopMode = s.desktopMode
        state.cursorMode = s.cursorMode
        val start = lastUrl
        if (start.isNullOrBlank()) state.showHome() else state.loadUrl(start)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { state.url }.collect { if (it.startsWith("http")) lastUrl = it }
    }
    LaunchedEffect(state.focusWebSeq) {
        if (state.focusWebSeq > 0) {
            delay(16)
            state.webView.requestFocus()
        }
    }
    // Continuous, accelerating cursor motion while a direction is held.
    LaunchedEffect(state.heldMask != 0) {
        if (state.heldMask == 0) return@LaunchedEffect
        var start = -1L
        var last = -1L
        while (isActive && state.heldMask != 0) {
            withFrameNanos { t ->
                if (start < 0) { start = t; last = t }
                state.stepCursor((t - start) / 1e9f, (t - last) / 1e9f)
                last = t
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.onAreaSize(it) },
        ) {
            AndroidView(
                factory = {
                    (state.webView.parent as? ViewGroup)?.removeView(state.webView)
                    state.webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (state.cursorMode && !state.startVisible && !state.toolbarVisible && !state.panelVisible) {
                CursorPointer(state)
            }
        }

        if (state.loading && state.progress < 100) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(state.progress.coerceIn(5, 100) / 100f)
                    .height(3.dp)
                    .background(BootColors.Accent),
            )
        }

        if (state.startVisible) {
            StartPage(state, bookmarks, history)
        }

        AnimatedVisibility(
            visible = state.toolbarVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Toolbar(state, isBookmarked = bookmarks.any { it.url == state.url })
        }

        AnimatedVisibility(
            visible = state.panelVisible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            BookmarksPanel(state, bookmarks, history)
        }

        MessagePill(state, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------------------- cursor

@Composable
private fun CursorPointer(state: BrowserState) {
    val c = state.cursor
    Canvas(
        Modifier
            .offset { IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
            .size(26.dp),
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.92f)
            lineTo(w * 0.27f, h * 0.68f)
            lineTo(w * 0.46f, h)
            lineTo(w * 0.60f, h * 0.94f)
            lineTo(w * 0.42f, h * 0.62f)
            lineTo(w * 0.75f, h * 0.62f)
            close()
        }
        drawPath(path, Color.White)
        drawPath(path, Color.Black, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------- toolbar

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Toolbar(state: BrowserState, isBookmarked: Boolean) {
    val scope = rememberCoroutineScope()
    val urlButtonFocus = remember { FocusRequester() }
    val urlFieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedLabel by remember { mutableStateOf("") }
    val onFocused: (String) -> Unit = { focusedLabel = it }

    LaunchedEffect(state.editingUrl) {
        delay(60)
        if (state.editingUrl) {
            runCatching { urlFieldFocus.requestFocus() }
            keyboard?.show()
        } else if (!state.startVisible) {
            runCatching { urlButtonFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(BootColors.Background.copy(alpha = 0.94f))
            .padding(horizontal = BootDimens.PagePaddingH, vertical = 12.dp)
            .focusGroup(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BrowserIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", state::goBack,
                enabled = state.canGoBack, onFocused = onFocused)
            BrowserIconButton(Icons.AutoMirrored.Filled.ArrowForward, "Forward", state::goForward,
                enabled = state.canGoForward, onFocused = onFocused)
            BrowserIconButton(if (state.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                if (state.loading) "Stop" else "Reload", state::reloadOrStop, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.Home, "Home", state::showHome, onFocused = onFocused)

            Box(Modifier.weight(1f).height(44.dp)) {
                if (state.editingUrl) {
                    var field by remember {
                        val initial = if (state.url.startsWith("http")) state.url else ""
                        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = TextStyle(color = BootColors.TextPrimary, fontSize = 18.sp),
                        cursorBrush = SolidColor(BootColors.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { keyboard?.hide(); state.submitInput(field.text) },
                            onDone = { keyboard?.hide(); state.submitInput(field.text) },
                            onSearch = { keyboard?.hide(); state.submitInput(field.text) },
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(urlFieldFocus)
                            // With the keyboard closed, Up/Down/Back cancel editing instead of trapping focus.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown, Key.DirectionUp, Key.Back, Key.Escape -> {
                                        keyboard?.hide(); state.editingUrl = false; true
                                    }
                                    else -> false
                                }
                            }
                            .background(BootColors.SurfaceRaised, RoundedCornerShape(22.dp))
                            .border(BorderStroke(2.dp, BootColors.Accent), RoundedCornerShape(22.dp))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                } else {
                    BrowserSurfaceButton(
                        onClick = { state.editingUrl = true },
                        shape = RoundedCornerShape(22.dp),
                        container = BootColors.SurfaceRaised,
                        focusedScale = 1.02f,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(urlButtonFocus),
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.url.takeIf { it.startsWith("http") } ?: "Search DuckDuckGo or type a web address",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 17.sp,
                            )
                        }
                    }
                }
            }

            BrowserIconButton(if (isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                if (isBookmarked) "Remove bookmark" else "Bookmark this page",
                onClick = {
                    val u = state.url
                    if (!u.startsWith("http")) return@BrowserIconButton
                    scope.launch {
                        if (isBookmarked) {
                            state.store.removeBookmark(u); state.toast("Bookmark removed")
                        } else {
                            state.store.addBookmark(Bookmark(state.title.ifBlank { u }, u)); state.toast("Bookmarked")
                        }
                    }
                },
                active = isBookmarked, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.AddToHomeScreen, "Add to Home as an app",
                onClick = {
                    val u = state.url
                    if (!u.startsWith("http")) return@BrowserIconButton
                    val name = state.title.ifBlank { hostOf(u) }.take(40)
                    scope.launch { WebApps.add(state.context, WebApp(name, u)); state.toast("Added \"$name\" to Home") }
                },
                onFocused = onFocused)
            BrowserIconButton(Icons.Filled.Bookmarks, "Bookmarks & history",
                { state.panelVisible = true }, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.ZoomOut, "Zoom out", state::zoomOut, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.ZoomIn, "Zoom in", state::zoomIn, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.DesktopWindows,
                "Desktop site: " + if (state.desktopMode) "on" else "off",
                state::toggleDesktop, active = state.desktopMode, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.Mouse,
                if (state.cursorMode) "Cursor mode (switch to scroll/focus)" else "Scroll/focus mode (switch to cursor)",
                state::toggleCursor, active = state.cursorMode, onFocused = onFocused)
            BrowserIconButton(Icons.Filled.Shield,
                if (state.adBlock) "Ad blocker on · ${state.blocked} blocked" else "Ad blocker off",
                state::toggleAdBlock, active = state.adBlock, onFocused = onFocused)
        }
        Text(
            text = focusedLabel.ifEmpty { state.title },
            color = BootColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------- start page

@Composable
private fun StartPage(state: BrowserState, bookmarks: List<Bookmark>, history: List<HistoryEntry>) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BootColors.Background),
        contentPadding = PaddingValues(
            start = BootDimens.PagePaddingH, end = BootDimens.PagePaddingH,
            top = 110.dp, bottom = BootDimens.PagePaddingV,
        ),
        verticalArrangement = Arrangement.spacedBy(BootDimens.RowSpacing),
    ) {
        item {
            BrowserSurfaceButton(
                onClick = { state.toolbarVisible = true; state.editingUrl = true },
                shape = RoundedCornerShape(30.dp),
                container = BootColors.SurfaceRaised,
                focusedScale = 1.02f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .focusRequester(firstFocus),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null)
                    Spacer(Modifier.width(14.dp))
                    Text("Search DuckDuckGo or type a web address", fontSize = 20.sp)
                }
            }
        }
        item { SectionTitle("Bookmarks") }
        items(bookmarks.chunked(5)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing)) {
                row.forEach { b -> BookmarkTile(b) { state.loadUrl(b.url) } }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle("Recently visited") }
            items(history.take(8), key = { "h" + it.url }) { h ->
                ListRow(title = h.title, subtitle = h.url, onClick = { state.loadUrl(h.url) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary)
}

internal val tileColors = listOf(
    Color(0xFF7B5CFF), Color(0xFFE5484D), Color(0xFF12A594), Color(0xFFF76B15),
    Color(0xFF0090FF), Color(0xFFD6409F), Color(0xFF46A758), Color(0xFFFFB224),
)

internal fun hostOf(url: String): String =
    runCatching { android.net.Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

@Composable
private fun BookmarkTile(b: Bookmark, onClick: () -> Unit) {
    val color = tileColors[abs(hostOf(b.url).hashCode()) % tileColors.size]
    BrowserSurfaceButton(
        onClick = onClick,
        container = color.copy(alpha = 0.35f),
        focusedScale = 1.08f,
        modifier = Modifier.width(BootDimens.WideCardWidth * 0.9f).height(120.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(40.dp).background(color, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(b.title.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            }
            Column {
                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(hostOf(b.url), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                    color = BootColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowserSurfaceButton(onClick = onClick, focusedScale = 1.02f, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                color = BootColors.TextSecondary)
        }
    }
}

// ---------------------------------------------------------------------------- bookmarks / history panel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BookmarksPanel(state: BrowserState, bookmarks: List<Bookmark>, history: List<HistoryEntry>) {
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(BootColors.Surface)
            .padding(horizontal = 24.dp, vertical = BootDimens.PagePaddingV)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TabChip("Bookmarks", selected = !showHistory, Modifier.focusRequester(firstFocus)) { showHistory = false }
            TabChip("History", selected = showHistory) { showHistory = true }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (!showHistory) {
                if (state.url.startsWith("http") && bookmarks.none { it.url == state.url }) {
                    item {
                        ListRow("+ Bookmark this page", state.title.ifBlank { state.url }, onClick = {
                            val u = state.url
                            scope.launch { state.store.addBookmark(Bookmark(state.title.ifBlank { u }, u)) }
                            state.toast("Bookmarked")
                        })
                    }
                }
                items(bookmarks, key = { "b" + it.url }) { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListRow(b.title, b.url, onClick = { state.loadUrl(b.url) }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        BrowserIconButton(Icons.Filled.Delete, "Delete bookmark", onClick = {
                            scope.launch { state.store.removeBookmark(b.url) }
                            state.toast("Removed ${b.title}")
                        })
                    }
                }
                if (bookmarks.isEmpty()) item { EmptyText("No bookmarks yet") }
            } else {
                if (history.isNotEmpty()) {
                    item {
                        ListRow("Clear history", "${history.size} pages", onClick = {
                            scope.launch { state.store.clearHistory() }
                            state.webView.clearHistory()
                            state.toast("History cleared")
                        })
                    }
                }
                items(history, key = { "h" + it.url }) { h ->
                    val ago = DateUtils.getRelativeTimeSpanString(h.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    ListRow(h.title, "$ago · ${hostOf(h.url)}", onClick = { state.loadUrl(h.url) })
                }
                if (history.isEmpty()) item { EmptyText("No history yet") }
            }
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BrowserSurfaceButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        container = if (selected) BootColors.AccentSoft else BootColors.SurfaceRaised,
        modifier = modifier,
    ) {
        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, color = BootColors.TextSecondary, modifier = Modifier.padding(8.dp))
}

// ---------------------------------------------------------------------------- transient message

@Composable
private fun MessagePill(state: BrowserState, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.messageSeq) {
        if (state.messageSeq == 0) return@LaunchedEffect
        visible = true
        delay(2500)
        visible = false
    }
    // First-run hint so the toolbar is discoverable on remotes without a Menu key.
    var hintShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.url) {
        if (!hintShown && state.url.startsWith("http")) {
            hintShown = true
            delay(1500)
            state.toast("Press ▲ at the top of the page (or Menu) for the toolbar")
        }
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(bottom = 36.dp)) {
        Text(
            text = state.message.orEmpty(),
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier
                .background(Color(0xE6161624), RoundedCornerShape(24.dp))
                .border(1.dp, BootColors.AccentSoft, RoundedCornerShape(24.dp))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}
