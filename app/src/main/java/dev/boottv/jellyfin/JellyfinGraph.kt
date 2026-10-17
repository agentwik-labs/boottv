package dev.boottv.jellyfin

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Text
import dev.boottv.core.Routes

/** Registers Routes.MEDIA and every "media/..." route. */
fun NavGraphBuilder.jellyfinGraph(nav: NavController) {
    composable(Routes.MEDIA) { Text("Media") }
}

/** Shown inside the Settings screen. */
@Composable
fun JellyfinSettingsSection() {
    Text("Jellyfin settings")
}
