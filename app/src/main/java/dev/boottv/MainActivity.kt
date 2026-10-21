package dev.boottv

import dev.boottv.core.ui.LocalSideMenuFocus
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.boottv.archive.archiveGraph
import dev.boottv.browser.browserGraph
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.BootMenuItems
import dev.boottv.core.ui.ThemeBackdrop
import dev.boottv.core.ui.BootTheme
import dev.boottv.core.ui.SideMenu
import dev.boottv.jellyfin.jellyfinGraph
import dev.boottv.launcher.HomeScreen
import dev.boottv.launcher.HomeSignals
import dev.boottv.livetv.liveTvGraph
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.livetv.TvProvider
import dev.boottv.localmedia.localMediaGraph
import dev.boottv.remote.RemoteButtons
import androidx.compose.runtime.SideEffect
import dev.boottv.search.SearchScreen
import dev.boottv.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    /** Top-level routes requested from outside Compose (Home key, Search key). */
    // Replays the latest request so one made before the screen is up (e.g. opened by a remote button) isn't lost.
    private val navRequests = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    /** The route on screen, for hot keys that behave differently in the player. */
    @Volatile private var currentRoute: String? = null

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteButtons.load(this)
        intent?.getStringExtra(RemoteButtons.EXTRA_ROUTE)?.let { openRoute(it) }
        setContent {
            BootTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                val topLevel = topLevelOf(route)
                SideEffect { currentRoute = route }
                val showMenu = !isFullScreen(route)
                val contentFocus = remember { FocusRequester() }
                val menuFocus = remember { FocusRequester() }
                var focusContentTick by remember { mutableIntStateOf(0) }
                var menuHasFocus by remember { mutableStateOf(false) }
                var contentHasFocus by remember { mutableStateOf(false) }
                val focusManager = LocalFocusManager.current

                LaunchedEffect(Unit) {
                    navRequests.collect { target ->
                        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                        navRequests.resetReplayCache()
                        if (target == Routes.HOME) {
                            if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.navigateTopLevel(Routes.HOME)
                            HomeSignals.reset.tryEmit(Unit)
                        } else {
                            nav.navigateTopLevel(target)
                        }
                        focusContentTick++
                    }
                }
                LaunchedEffect(focusContentTick) {
                    if (focusContentTick == 0) return@LaunchedEffect
                    // Like pressing Right on the remote: hand focus from the rail to the new screen.
                    // The destination animates in, so retry until the content area really holds focus.
                    repeat(14) {
                        delay(150)
                        if (contentHasFocus) return@LaunchedEffect
                        if (!(menuHasFocus && focusManager.moveFocus(FocusDirection.Right))) {
                            runCatching { contentFocus.requestFocus() }
                        }
                    }
                }

                // A launcher must never close on Back from Home.
                BackHandler(enabled = route == Routes.HOME) { HomeSignals.reset.tryEmit(Unit) }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BootColors.Background),
                ) {
                    if (BootColors.theme.playful) ThemeBackdrop()
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = if (showMenu) BootDimens.SideMenuCollapsed else 0.dp)
                            .onFocusChanged { contentHasFocus = it.hasFocus }
                            .focusRequester(contentFocus)
                            .focusGroup(),
                    ) {
                        CompositionLocalProvider(LocalSideMenuFocus provides menuFocus.takeIf { showMenu }) {
                        NavHost(nav, startDestination = Routes.HOME) {
                            composable(Routes.HOME) { HomeScreen(nav) }
                            composable(Routes.SEARCH) { SearchScreen(nav) }
                            composable(Routes.SETTINGS) { SettingsScreen(nav) }
                            localMediaGraph(nav)
                            jellyfinGraph(nav)
                            liveTvGraph(nav)
                            browserGraph(nav)
                            archiveGraph(nav)
                        }
                        }
                    }
                    // Side menu everywhere except full-screen players.
                    if (showMenu) {
                        SideMenu(
                            currentRoute = topLevel,
                            onSelect = { target ->
                                when {
                                    // Live TV can be set to open the TV provider's app (Settings › Live TV).
                                    target != topLevel -> nav.navigateTopLevel(target)
                                    // On a nested screen of this section: go back to its root.
                                    route?.substringBefore('?') != target.substringBefore('?') ->
                                        if (!nav.popBackStack(target, inclusive = false)) nav.navigateTopLevel(target)
                                }
                                focusContentTick++
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .onFocusChanged { menuHasFocus = it.hasFocus }
                                .focusRequester(menuFocus),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A remote button assigned in Settings › Remote buttons.
        intent.getStringExtra(RemoteButtons.EXTRA_ROUTE)?.let { openRoute(it); return }
        // Home button pressed while BootTV is already the foreground home app.
        if (intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        ) {
            navRequests.tryEmit(Routes.HOME)
        }
    }

    /** Goes to [route]; "last channel" becomes the channel watched last (or the first one). */
    private fun openRoute(route: String) {
        if (route == TvProvider.ROUTE) { TvProvider.open(this); return }
        if (route == RemoteButtons.QUICK_MENU) return // inside BootTV the side menu is already there
        val target = if (route == RemoteButtons.LAST_CHANNEL) {
            val s = LiveTvRepository.state.value
            val id = s.lastChannelId?.takeIf { s.channel(it) != null } ?: s.channels.firstOrNull()?.id
            if (id != null) "live/play/$id" else Routes.LIVE
        } else route
        navRequests.tryEmit(target)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Settings › Remote buttons is waiting for a button (when the accessibility service is off, it hears it here).
        if (RemoteButtons.learning.value && RemoteButtons.canAssign(code)) {
            if (event.action == KeyEvent.ACTION_UP) { RemoteButtons.learned.value = code; RemoteButtons.learning.value = false }
            return true
        }
        val inPlayer = currentRoute?.startsWith("live/play") == true
        val hot = RemoteButtons.mappings.value[code]?.route ?: when (code) {
            // Hot keys that work in BootTV without any setup.
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> Routes.LIVE
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_LAST_CHANNEL ->
                RemoteButtons.LAST_CHANNEL.takeIf { !inPlayer } // the player changes channel with these itself
            else -> null
        }
        if (hot != null) {
            if (event.action == KeyEvent.ACTION_UP) openRoute(hot)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) navRequests.tryEmit(Routes.SEARCH)
            return true
        }
        // Keyboards (and the emulator) send Esc / Backspace where a remote sends Back. Treat them as Back,
        // except Backspace while typing in a text field or web form.
        val asBack = when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> true
            KeyEvent.KEYCODE_DEL -> !getSystemService(InputMethodManager::class.java).isAcceptingText
            else -> false
        }
        if (asBack) {
            return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
            )
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        private val topLevelRoutes = BootMenuItems.map { it.route }.toSet()

        /** The side-menu destination that [route] belongs to (prefix match), e.g. "media/detail/{id}" -> "media". */
        fun topLevelOf(route: String?): String? {
            val base = route?.substringBefore('?') ?: return Routes.HOME
            return topLevelRoutes.firstOrNull { base == it || base.startsWith("$it/") }
        }

        /** Full-screen players: no side menu at all (not composed, never takes focus). */
        fun isFullScreen(route: String?): Boolean =
            route != null && (route.startsWith("media/play") || route.startsWith("media/local/") || route.startsWith("live/play") || route.startsWith("free/play"))

        fun NavHostController.navigateTopLevel(target: String) {
            navigate(target) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}
