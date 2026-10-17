package dev.boottv.browser

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Text
import dev.boottv.core.Routes

/** Registers Routes.BROWSER ("browser?url={url}" optional arg is also fine). */
fun NavGraphBuilder.browserGraph(nav: NavController) {
    composable(Routes.BROWSER) { Text("Browser") }
}
