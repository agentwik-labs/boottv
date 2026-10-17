package dev.boottv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.boottv.browser.browserGraph
import dev.boottv.core.Routes
import dev.boottv.jellyfin.jellyfinGraph
import dev.boottv.launcher.HomeScreen
import dev.boottv.livetv.liveTvGraph
import dev.boottv.search.SearchScreen
import dev.boottv.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.SEARCH) { SearchScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                jellyfinGraph(nav)
                liveTvGraph(nav)
                browserGraph(nav)
            }
        }
    }
}
