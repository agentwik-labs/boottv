package dev.boottv.launcher

import dev.boottv.core.ui.PocketWordmark
import dev.boottv.core.ui.BootCard
import androidx.activity.compose.ReportDrawnWhen
import dev.boottv.core.ui.leftToMenu
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootButton
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDialog
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.PinPadDialog
import dev.boottv.core.ui.PinSetupDialog
import dev.boottv.settings.BootPrefs
import dev.boottv.settings.BootSettings
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.browser.WebApp
import dev.boottv.browser.WebApps
import dev.boottv.browser.browserRoute
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Signals from the activity to the home screen (e.g. Home key pressed while already home). */
object HomeSignals {
    val reset = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

private data class PinRequest(val title: String, val message: String?, val onSuccess: () -> Unit)

private enum class MoveDir { Left, Right, Up, Down }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { BootPrefs.flow(context) }.collectAsState(initial = null)

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Load / reload the installed app list.
    LaunchedEffect(refreshTick) { apps = AppRepository.loadApps(context) }
    // Content rows (Continue Watching, On Now): refreshed whenever Home comes back to the front.
    LaunchedEffect(refreshTick) { runCatching { HomeFeed.refresh() } }
    LaunchedEffect(Unit) { LiveTvRepository.state.collect { HomeFeed.refreshLive(it) } }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    // Live refresh on install / uninstall / update.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                AppRepository.invalidate(intent.data?.schemeSpecificPart)
                refreshTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val s = settings ?: BootSettings()
    val allApps = apps.orEmpty()
    val byPkg = remember(allApps) { allApps.associateBy { it.packageName } }

    // Home order: saved order first, then any new apps alphabetically (Roku puts new channels last).
    val ordered = remember(allApps, s.order) {
        val saved = s.order.mapNotNull { byPkg[it] }
        val rest = allApps.filter { it.packageName !in s.order.toSet() }
        saved + rest
    }
    val visible = remember(ordered, s.hidden) { ordered.filter { it.packageName !in s.hidden } }
    val favorites = remember(byPkg, s.favorites, s.hidden) {
        s.favorites.mapNotNull { byPkg[it] }.filter { it.packageName !in s.hidden }
    }

    // Move mode (Roku "Move channel").
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var workingOrder by remember { mutableStateOf<List<String>?>(null) }
    val gridApps: List<AppEntry> = workingOrder?.mapNotNull { byPkg[it] } ?: visible

    val webApps by remember { WebApps.flow(context) }.collectAsState(initial = emptyList())

    // Dialogs.
    var options by remember { mutableStateOf<Pair<AppEntry, Boolean>?>(null) }
    var webOptions by remember { mutableStateOf<WebApp?>(null) }
    var addingWebApp by remember { mutableStateOf(false) }
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }
    var setupThen by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    fun launch(app: AppEntry) {
        val go = {
            if (!AppRepository.launch(context, app.packageName)) {
                Toast.makeText(context, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
        if (app.packageName in s.locked && s.hasPin) {
            pinRequest = PinRequest("Enter PIN", "${app.label} is locked.") { go() }
        } else {
            go()
        }
    }

    /** Runs [action] directly, or after PIN entry if the app is locked. */
    fun guarded(app: AppEntry, action: () -> Unit) {
        if (app.packageName in s.locked && s.hasPin) {
            pinRequest = PinRequest("Enter PIN", "${app.label} is locked.", action)
        } else {
            action()
        }
    }

    fun startMove(app: AppEntry) {
        workingOrder = visible.map { it.packageName }
        movingPkg = app.packageName
    }

    fun moveBy(dir: MoveDir, columns: Int) {
        val pkg = movingPkg ?: return
        val list = workingOrder?.toMutableList() ?: return
        val i = list.indexOf(pkg)
        if (i < 0) return
        val target = when (dir) {
            MoveDir.Left -> i - 1
            MoveDir.Right -> i + 1
            MoveDir.Up -> i - columns
            MoveDir.Down -> i + columns
        }.coerceIn(0, list.lastIndex)
        if (target == i) return
        list.removeAt(i)
        list.add(target, pkg)
        workingOrder = list
    }

    fun dropMove() {
        val newVisible = workingOrder
        movingPkg = null
        workingOrder = null
        if (newVisible != null) {
            val hiddenTail = ordered.map { it.packageName }.filter { it !in newVisible }
            scope.launch { BootPrefs.setOrder(context, newVisible + hiddenTail) }
        }
    }

    fun cancelMove() {
        movingPkg = null
        workingOrder = null
    }

    // Keep focus on the tile being moved.
    LaunchedEffect(workingOrder) {
        val pkg = movingPkg ?: return@LaunchedEffect
        runCatching { requester("grid:$pkg").requestFocus() }
    }

    // Banner follows focus in the content rows; defaults to the first Continue Watching item.
    var featured by remember { mutableStateOf(HomeFeed.firstFeatured()) }
    val feedFirst = HomeFeed.firstFeatured()
    LaunchedEffect(feedFirst?.key) {
        val f = featured
        val stillThere = f != null && (HomeFeed.continueWatching.any { "media:${it.id}" == f.key } ||
            (HomeFeed.onNow + HomeFeed.cartoons).any { "live:${it.channel.id}" == f.key })
        if (!stillThere) featured = feedFirst
    }
    val showFeed = movingPkg == null && !HomeFeed.isEmpty
    // Startup counts as done once the rows are on screen (or the feed answered with nothing), for startup metrics.
    ReportDrawnWhen { !HomeFeed.isEmpty || HomeFeed.loaded }
    val firstRowPrefix = when {
        HomeFeed.client != null && HomeFeed.continueWatching.isNotEmpty() -> "feed:media:"
        HomeFeed.onNow.isNotEmpty() -> "feed:live:"
        else -> "feed:toon:"
    }
    val onFeedFocused: (String, Featured) -> Unit = { k, f ->
        lastFocused = k
        featured = f
        // Back on the top row: show the whole banner again.
        // Runs after the focus system's own bring-into-view scroll, which would otherwise cancel it.
        if (k.startsWith(firstRowPrefix) && scroll.value > 0) scope.launch { delay(200); scroll.animateScrollTo(0) }
    }

    // Initial focus: last focused tile (when returning), else the first content card, else the first tile.
    val feedFirstKey = when {
        HomeFeed.client != null && HomeFeed.continueWatching.isNotEmpty() -> "feed:media:${HomeFeed.continueWatching.first().id}"
        HomeFeed.onNow.isNotEmpty() -> "feed:live:${HomeFeed.onNow.first().channel.id}"
        HomeFeed.cartoons.isNotEmpty() -> "feed:toon:${HomeFeed.cartoons.first().channel.id}"
        else -> null
    }
    val firstKey = feedFirstKey ?: favorites.firstOrNull()?.takeIf { s.showFavorites }?.let { "fav:${it.packageName}" }
        ?: visible.firstOrNull()?.let { "grid:${it.packageName}" }
    var focusTick by remember { mutableIntStateOf(0) }
    var didInitialFocus by remember { mutableStateOf(false) }
    var homeHasFocus by remember { mutableStateOf(false) }
    /** The card focus was put on automatically, until the viewer moves. */
    var autoFocused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstKey, focusTick) {
        if (firstKey == null) return@LaunchedEffect
        if (didInitialFocus && focusTick == 0) return@LaunchedEffect
        // Give the content rows a moment on cold start so focus lands on them rather than jumping later. Not when
        // coming back (rows already showing): focus starts out on the side menu then, and a wait here would pull it
        // away from the menu if the viewer pressed Left meanwhile.
        if (!HomeFeed.loaded && HomeFeed.isEmpty) withTimeoutOrNull(2500) { snapshotFlow { HomeFeed.loaded }.first { it } }
        // Rows are lazy and animate in after navigation, so retry until a card really holds focus.
        repeat(20) { attempt ->
            delay(if (attempt == 0) 60 else 100)
            // Stop once it has landed, or a quick press of Left to the side menu gets pulled back here.
            if (homeHasFocus) { didInitialFocus = true; return@LaunchedEffect }
            val restore = lastFocused?.takeIf { focusTick == 0 && requesters.containsKey(it) && attempt < 10 }
            val ok = runCatching { requester(restore ?: firstKey).requestFocus() }.isSuccess
            if (!ok) runCatching { requester(firstKey).requestFocus() }
            if (restore == null) autoFocused = firstKey
        }
        didInitialFocus = true
    }
    // On a first start the live rows can arrive after focus went to the apps below. If the viewer hasn't moved yet,
    // bring focus up to the first card so they start at the top.
    LaunchedEffect(feedFirstKey) {
        val target = feedFirstKey ?: return@LaunchedEffect
        val auto = autoFocused ?: return@LaunchedEffect
        if (auto.startsWith("feed:") || lastFocused != auto) return@LaunchedEffect
        repeat(10) { delay(100); if (runCatching { requester(target).requestFocus() }.isSuccess) { autoFocused = target; return@LaunchedEffect } }
    }

    // Home key while already home: close everything and go back to the top.
    LaunchedEffect(Unit) {
        HomeSignals.reset.collect {
            options = null
            webOptions = null
            addingWebApp = false
            pinRequest = null
            setupThen = null
            cancelMove()
            lastFocused = null
            scroll.animateScrollTo(0)
            featured = HomeFeed.firstFeatured()
            focusTick++
        }
    }

    Box(Modifier.fillMaxSize().background(BootColors.Background).onFocusChanged { homeHasFocus = it.hasFocus }) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollSpec) {
    // The preview stops (and frees the video decoder) once the page scrolls down to the apps.
    if (showFeed) HomeBackdrop(featured, alpha = 1f - scroll.value / 500f, livePreview = settings?.livePreview == true && scroll.value < 300)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV),
    ) {
        HomeTopBar(movingLabel = movingPkg?.let { byPkg[it]?.label }, compact = showFeed)
        Spacer(Modifier.height(if (showFeed) 4.dp else 20.dp))

        if (showFeed) {
            featured?.let { HomeHero(it) }
            Spacer(Modifier.height(12.dp))
            val client = HomeFeed.client
            if (client != null && HomeFeed.continueWatching.isNotEmpty()) {
                ContinueRow(
                    items = HomeFeed.continueWatching,
                    client = client,
                    requester = ::requester,
                    onFocused = onFeedFocused,
                    onOpen = { nav.navigate("media/detail/${it.id}") },
                )
                Spacer(Modifier.height(BootDimens.RowSpacing))
            }
            if (HomeFeed.onNow.isNotEmpty()) {
                OnNowRow(
                    channels = HomeFeed.onNow,
                    requester = ::requester,
                    onFocused = onFeedFocused,
                    onWatch = { nav.navigate("live/play/${it.id}") },
                )
                Spacer(Modifier.height(BootDimens.RowSpacing))
            }
            if (BootColors.theme.playful && HomeFeed.pokemon.isNotEmpty()) {
                OnNowRow(
                    channels = HomeFeed.pokemon,
                    requester = ::requester,
                    onFocused = onFeedFocused,
                    onWatch = { nav.navigate("live/play/${it.id}") },
                    title = "Pokémon on now",
                    keyPrefix = "feed:poke:",
                )
                Spacer(Modifier.height(BootDimens.RowSpacing))
            }
            if (HomeFeed.cartoons.isNotEmpty()) {
                OnNowRow(
                    channels = HomeFeed.cartoons,
                    requester = ::requester,
                    onFocused = onFeedFocused,
                    onWatch = { nav.navigate("live/play/${it.id}") },
                    title = "Cartoons & Anime",
                    keyPrefix = "feed:toon:",
                )
                Spacer(Modifier.height(BootDimens.RowSpacing))
            }
        }
        if (movingPkg == null) {
            WebAppsRow(
                apps = webApps,
                requester = ::requester,
                onFocused = { k, f -> lastFocused = k; if (showFeed && f != null) featured = f },
                onOpen = { nav.navigate(browserRoute(it.url)) },
                onOptions = { webOptions = it },
                onAdd = { addingWebApp = true },
            )
            Spacer(Modifier.height(BootDimens.RowSpacing))
        }
        // Free legal apps not installed yet; once installed they show up in the Apps grid instead.
        val toInstall = remember(allApps) {
            val installed = allApps.map { it.packageName }.toSet()
            FreeApps.filter { it.pkg !in installed }
        }
        if (movingPkg == null && apps != null && toInstall.isNotEmpty()) {
            FreeAppsRow(
                apps = toInstall,
                requester = ::requester,
                onFocused = { k, f -> lastFocused = k; if (showFeed) featured = f },
                onOpen = { openStoreListing(context, it) },
            )
            Spacer(Modifier.height(BootDimens.RowSpacing))
        }

        if (s.showFavorites && favorites.isNotEmpty() && movingPkg == null) {
            SectionTitle("Favorites")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            ) {
                favorites.forEachIndexed { i, app ->
                    key(app.packageName) {
                        val k = "fav:${app.packageName}"
                        AppTile(
                            app = app,
                            locked = app.packageName in s.locked,
                            moving = false,
                            modifier = Modifier
                                .width(BootDimens.WideCardWidth)
                                .leftToMenu(i == 0)
                                .focusRequester(requester(k))
                                .onFocusChanged { if (it.isFocused) lastFocused = k },
                            onClick = { launch(app) },
                            onOptions = { options = app to true },
                            onMoveKey = { false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(BootDimens.RowSpacing))
        }

        SectionTitle("Apps", hint = if (showFeed) "Hold OK for options" else null)
        Spacer(Modifier.height(10.dp))
        when {
            apps == null -> Text("Loading apps…", color = BootColors.TextSecondary)
            gridApps.isEmpty() -> Text(
                "No apps to show. Hidden apps can be restored in Settings › Home screen.",
                color = BootColors.TextSecondary,
            )
            else -> TileGrid(columns = s.gridColumns, spacing = BootDimens.CardSpacing) {
                gridApps.forEachIndexed { i, app ->
                    key(app.packageName) {
                        val k = "grid:${app.packageName}"
                        val isMoving = movingPkg == app.packageName
                        AppTile(
                            app = app,
                            locked = app.packageName in s.locked,
                            moving = isMoving,
                            dimmed = movingPkg != null && !isMoving,
                            modifier = Modifier
                                .leftToMenu(i % s.gridColumns == 0 && movingPkg == null)
                                .focusRequester(requester(k))
                                .onFocusChanged { if (it.isFocused) lastFocused = k },
                            onClick = { if (movingPkg == null) launch(app) },
                            onOptions = { if (movingPkg == null) options = app to false },
                            onMoveKey = { key ->
                                if (!isMoving) return@AppTile false
                                when (key) {
                                    Key.DirectionLeft -> moveBy(MoveDir.Left, s.gridColumns)
                                    Key.DirectionRight -> moveBy(MoveDir.Right, s.gridColumns)
                                    Key.DirectionUp -> moveBy(MoveDir.Up, s.gridColumns)
                                    Key.DirectionDown -> moveBy(MoveDir.Down, s.gridColumns)
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> dropMove()
                                    Key.Back, Key.Escape -> cancelMove()
                                }
                                true
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
    }
    }

    // ---- Dialogs ----
    options?.let { (app, fromFavorites) ->
        AppOptionsDialog(
            app = app,
            fromFavorites = fromFavorites,
            settings = s,
            onDismiss = { options = null },
            onMove = { options = null; startMove(app) },
            onToggleFavorite = {
                options = null
                scope.launch { BootPrefs.setFavorite(context, app.packageName, app.packageName !in s.favorites) }
            },
            onHide = {
                options = null
                guarded(app) {
                    scope.launch { BootPrefs.setHidden(context, app.packageName, true) }
                    Toast.makeText(context, "${app.label} hidden. Restore it in Settings › Home screen.", Toast.LENGTH_LONG).show()
                }
            },
            onToggleLock = {
                options = null
                val locked = app.packageName in s.locked
                when {
                    locked -> pinRequest = PinRequest("Unlock ${app.label}", "Enter your PIN to unlock.") {
                        scope.launch { BootPrefs.setLocked(context, app.packageName, false) }
                    }
                    s.hasPin -> scope.launch { BootPrefs.setLocked(context, app.packageName, true) }
                    else -> setupThen = { pin ->
                        scope.launch {
                            BootPrefs.setPin(context, pin)
                            BootPrefs.setLocked(context, app.packageName, true)
                        }
                    }
                }
            },
            onInfo = { options = null; AppRepository.openAppInfo(context, app.packageName) },
            onUninstall = {
                options = null
                guarded(app) { AppRepository.uninstall(context, app.packageName) }
            },
        )
    }

    webOptions?.let { app ->
        WebAppOptionsDialog(
            app = app,
            onDismiss = { webOptions = null },
            onOpen = { webOptions = null; nav.navigate(browserRoute(app.url)) },
            onRemove = {
                webOptions = null
                // Land on the neighbouring tile, not the side menu, once this one is gone.
                val i = webApps.indexOfFirst { it.url == app.url }
                val next = (webApps.getOrNull(i + 1) ?: webApps.getOrNull(i - 1))?.takeIf { it.url != app.url }
                val nextKey = next?.let { "web:${it.url}" } ?: "web:add"
                scope.launch {
                    WebApps.remove(context, app.url)
                    repeat(10) { delay(80); if (runCatching { requester(nextKey).requestFocus() }.isSuccess) return@launch }
                }
                Toast.makeText(context, "${app.name} removed from Home", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (addingWebApp) {
        AddWebAppDialog(
            onDismiss = { addingWebApp = false },
            onSave = { app ->
                addingWebApp = false
                scope.launch { WebApps.add(context, app) }
                Toast.makeText(context, "${app.name} added to Home", Toast.LENGTH_SHORT).show()
            },
        )
    }

    pinRequest?.let { req ->
        PinPadDialog(
            title = req.title,
            message = req.message,
            onDismiss = { pinRequest = null },
            onComplete = { pin ->
                if (BootPrefs.checkPin(s, pin)) {
                    pinRequest = null
                    req.onSuccess()
                    true
                } else {
                    false
                }
            },
        )
    }

    setupThen?.let { then ->
        PinSetupDialog(
            onDismiss = { setupThen = null },
            onPinSet = { pin -> setupThen = null; then(pin) },
        )
    }
}

/**
 * Scroll only as far as needed to show the focused card (plus room for its focus zoom), instead of
 * the TV default that parks it a third of the way down and pushes the banner off screen.
 */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScrollSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = 32f
        val leading = offset - margin
        val trailing = offset + size + margin
        return when {
            leading >= 0 && trailing <= containerSize -> 0f
            leading < 0 || size + 2 * margin > containerSize -> leading
            else -> trailing - containerSize
        }
    }
}

@Composable
private fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary.copy(alpha = 0.9f))
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun HomeTopBar(movingLabel: String?, compact: Boolean = false) {
    // Minimal: no logo, the artwork is the header (like Apple TV). Pocket Monsters: the wordmark, top left.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (BootColors.theme.playful) { PocketWordmark(); Spacer(Modifier.width(24.dp)) }
        // With the content banner showing, the app-options hint moves down to the Apps title.
        if (compact) Spacer(Modifier.weight(1f)) else Text(
            if (movingLabel != null) {
                "Moving $movingLabel — use the arrows, OK to drop, Back to cancel"
            } else {
                "Hold OK or press ☰ on an app for options"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (movingLabel != null) BootColors.TextPrimary else BootColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Clock()
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000 - (System.currentTimeMillis() % 1000) + 5)
        }
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    Column(horizontalAlignment = Alignment.End) {
        // Shadowed so it stays readable over bright backdrop artwork.
        val shadow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), blurRadius = 12f))
        Text(timeFormat.format(now), style = MaterialTheme.typography.headlineSmall.merge(shadow).copy(fontWeight = FontWeight.Medium), color = BootColors.TextPrimary)
        Text(dateFormat.format(now), style = MaterialTheme.typography.bodySmall.merge(shadow), color = BootColors.TextPrimary.copy(alpha = 0.6f))
    }
}

/** Simple grid that keeps all tiles in one parent, so keyed tiles keep focus while being reordered. */
@Composable
private fun TileGrid(columns: Int, spacing: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val cellW = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1)
        val cellH = cellW * 9 / 16
        val placeables = measurables.map { it.measure(Constraints.fixed(cellW, cellH)) }
        val rows = (placeables.size + columns - 1) / columns
        val height = if (rows == 0) 0 else rows * cellH + (rows - 1) * gap
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val r = i / columns
                val c = i % columns
                p.place(c * (cellW + gap), r * (cellH + gap))
            }
        }
    }
}

/** Apps without TV banner art sit on one quiet grey, so the icons carry the colour. */
private val tileTop = Color(0xFF3A3A3C)
private val tileBottom = Color(0xFF242426)

@Composable
private fun AppTile(
    app: AppEntry,
    locked: Boolean,
    moving: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    onMoveKey: (Key) -> Boolean,
) {
    val context = LocalContext.current
    var art by remember(app.packageName) { mutableStateOf<AppArt?>(null) }
    LaunchedEffect(app.packageName) { art = AppRepository.loadArt(context, app) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Card(
        onClick = onClick,
        onLongClick = onOptions,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (moving) {
                    // Act on key-down for arrows, key-up for OK/Back so the release doesn't re-trigger.
                    val isConfirm = ev.key == Key.DirectionCenter || ev.key == Key.Enter ||
                        ev.key == Key.NumPadEnter || ev.key == Key.Back || ev.key == Key.Escape
                    when {
                        isConfirm && ev.type == KeyEventType.KeyUp -> onMoveKey(ev.key)
                        isConfirm -> true
                        ev.type == KeyEventType.KeyDown -> onMoveKey(ev.key)
                        else -> true
                    }
                } else if (ev.key == Key.Menu) {
                    if (ev.type == KeyEventType.KeyUp) onOptions()
                    true
                } else {
                    false
                }
            },
        shape = CardDefaults.shape(shape),
        scale = BootCard.scale,
        border = CardDefaults.border(
            border = if (moving) Border(BorderStroke(3.dp, BootColors.Accent), shape = shape) else Border.None,
            focusedBorder = if (moving) Border(BorderStroke(3.dp, Color.White), shape = shape) else Border.None,
        ),
        glow = BootCard.glow,
        colors = CardDefaults.colors(containerColor = BootColors.Surface),
    ) {
        Box(Modifier.fillMaxSize()) {
            val a = art
            if (a != null && a.isBanner) {
                Image(a.bitmap, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(tileTop, tileBottom)))
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (a != null) {
                        Image(a.bitmap, contentDescription = null, modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        app.label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (a != null && a.isBanner && focused) {
                // Banner tiles have no visible label; show it on focus.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(app.label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (locked) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .background(Color(0xAA000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            if (moving) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x55000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.OpenWith, contentDescription = "Moving", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            if (dimmed) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            }
        }
    }
}

@Composable
private fun AppOptionsDialog(
    app: AppEntry,
    fromFavorites: Boolean,
    settings: BootSettings,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onToggleLock: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    val isFavorite = app.packageName in settings.favorites
    val isLocked = app.packageName in settings.locked
    BootDialog(onDismiss = onDismiss, title = app.label, subtitle = app.packageName) {
        val items = buildList<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> {
            if (!fromFavorites) add(Triple("Move app", Icons.Filled.OpenWith, onMove))
            add(
                Triple(
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    if (isFavorite) Icons.Filled.StarBorder else Icons.Filled.Star,
                    onToggleFavorite,
                ),
            )
            add(Triple("Hide from home", Icons.Filled.VisibilityOff, onHide))
            add(
                Triple(
                    if (isLocked) "Unlock (remove PIN lock)" else if (settings.hasPin) "Lock with PIN" else "Lock with PIN (set up PIN)",
                    if (isLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    onToggleLock,
                ),
            )
            add(Triple("App info", Icons.Filled.Info, onInfo))
            if (!app.isSystem) add(Triple("Uninstall", Icons.Filled.Delete, onUninstall))
        }
        items.forEachIndexed { i, (label, icon, action) ->
            BootButton(
                text = label,
                icon = icon,
                onClick = action,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}
