package dev.boottv.livetv

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.boottv.core.Routes

/** Registers Routes.LIVE and every "live/..." route. */
fun NavGraphBuilder.liveTvGraph(nav: NavController) {
    composable(Routes.LIVE) { GuideScreen(nav) }
    composable(
        "live/play/{channelId}",
        arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("channelId").orEmpty()
        LivePlayerScreen(nav, id)
    }
}

/** Shown inside the Settings screen. */
@Composable
fun LiveTvSettingsSection() {
    LiveTvSettingsContent()
}
