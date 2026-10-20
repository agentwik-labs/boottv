package dev.boottv.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow

/** Artwork cards focus the Apple TV way: they lift and catch a soft light, with no outline. */
object BootCard {
    val scale @Composable get() = CardDefaults.scale(focusedScale = 1.1f)
    val border @Composable get() = CardDefaults.border(focusedBorder = Border.None)
    val glow @Composable get() = CardDefaults.glow(focusedGlow = Glow(elevationColor = BootColors.Glow, elevation = 24.dp))

    /** For cards without full-bleed artwork: the surface brightens a step when focused. */
    @Composable
    fun colors(container: Color = BootColors.Surface) =
        CardDefaults.colors(containerColor = container, focusedContainerColor = if (BootColors.theme.playful) Color(0xFF3B48A0) else Color(0xFF48484A))
}
