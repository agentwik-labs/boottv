package dev.boottv.livetv

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Text
import dev.boottv.core.Routes

/** Registers Routes.LIVE and every "live/..." route. */
fun NavGraphBuilder.liveTvGraph(nav: NavController) {
    composable(Routes.LIVE) { Text("Live TV") }
}

/** Shown inside the Settings screen. */
@Composable
fun LiveTvSettingsSection() {
    Text("Live TV settings")
}
