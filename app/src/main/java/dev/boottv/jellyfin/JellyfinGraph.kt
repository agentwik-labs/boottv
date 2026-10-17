package dev.boottv.jellyfin

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.boottv.core.Routes

/** Registers Routes.MEDIA and every "media/..." route. */
fun NavGraphBuilder.jellyfinGraph(nav: NavController) {
    composable(Routes.MEDIA) { BrowseScreen(nav) }
    composable(
        "media/library/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        LibraryScreen(nav, entry.arguments?.getString("id").orEmpty())
    }
    composable(
        "media/detail/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        DetailScreen(nav, entry.arguments?.getString("id").orEmpty())
    }
    composable(
        "media/play/{id}?start={start}",
        arguments = listOf(
            navArgument("id") { type = NavType.StringType },
            navArgument("start") { type = NavType.LongType; defaultValue = 0L },
        ),
    ) { entry ->
        PlayerScreen(nav, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getLong("start") ?: 0L)
    }
}

/** Shown inside the Settings screen. */
@Composable
fun JellyfinSettingsSection() {
    JellyfinSettingsContent()
}
