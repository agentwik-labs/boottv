package dev.boottv.browser

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.boottv.core.Routes

/**
 * Registers the browser. Both "browser" and "browser?url=<encoded url>" resolve here
 * (see [browserRoute] for building the latter).
 */
fun NavGraphBuilder.browserGraph(nav: NavController) {
    composable(
        route = "${Routes.BROWSER}?url={url}",
        arguments = listOf(
            navArgument("url") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        BrowserScreen(nav, entry.arguments?.getString("url"))
    }
}
