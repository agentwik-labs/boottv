package dev.boottv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.boottv.browser.browserGraph
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.BootMenuItems
import dev.boottv.core.ui.BootTheme
import dev.boottv.core.ui.SideMenu
import dev.boottv.jellyfin.jellyfinGraph
import dev.boottv.launcher.HomeScreen
import dev.boottv.launcher.HomeSignals
import dev.boottv.livetv.liveTvGraph
import dev.boottv.search.SearchScreen
import dev.boottv.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    /** Top-level routes requested from outside Compose (Home key, Search key). */
    private val navRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BootTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                val topLevel = topLevelOf(route)
                val showMenu = !isFullScreen(route)
                val contentFocus = remember { FocusRequester() }
                var focusContentTick by remember { mutableIntStateOf(0) }
                var menuHasFocus by remember { mutableStateOf(false) }
                var contentHasFocus by remember { mutableStateOf(false) }
                val focusManager = LocalFocusManager.current

                LaunchedEffect(Unit) {
                    navRequests.collect { target ->
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
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = if (showMenu) BootDimens.SideMenuCollapsed else 0.dp)
                            .onFocusChanged { contentHasFocus = it.hasFocus }
                            .focusRequester(contentFocus)
                            .focusRestorer()
                            .focusGroup(),
                    ) {
                        NavHost(nav, startDestination = Routes.HOME) {
                            composable(Routes.HOME) { HomeScreen(nav) }
                            composable(Routes.SEARCH) { SearchScreen(nav) }
                            composable(Routes.SETTINGS) { SettingsScreen(nav) }
                            jellyfinGraph(nav)
                            liveTvGraph(nav)
                            browserGraph(nav)
                        }
                    }
                    // Side menu everywhere except full-screen players.
                    if (showMenu) {
                        SideMenu(
                            currentRoute = topLevel,
                            onSelect = { target ->
                                when {
                                    target != topLevel -> nav.navigateTopLevel(target)
                                    // On a nested screen of this section: go back to its root.
                                    route?.substringBefore('?') != target.substringBefore('?') ->
                                        if (!nav.popBackStack(target, inclusive = false)) nav.navigateTopLevel(target)
                                }
                                focusContentTick++
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .onFocusChanged { menuHasFocus = it.hasFocus },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while BootTV is already the foreground home app.
        if (intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        ) {
            navRequests.tryEmit(Routes.HOME)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) navRequests.tryEmit(Routes.SEARCH)
            return true
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
            route != null && (route.startsWith("media/play") || route.startsWith("live/play"))

        fun NavHostController.navigateTopLevel(target: String) {
            navigate(target) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}
