package dev.boottv.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Shared palette. Feature code should use these (or MaterialTheme.colorScheme) instead of raw colors. */
object BootColors {
    val Background = Color(0xFF0B0B14)
    val Surface = Color(0xFF161624)
    val SurfaceRaised = Color(0xFF22223A)
    val Accent = Color(0xFF7B5CFF)
    val AccentSoft = Color(0x337B5CFF)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFFA0A0B8)
    val Live = Color(0xFFE5484D)
}

/** Shared spacing: TV overscan-safe page padding and card sizes. */
object BootDimens {
    val PagePaddingH = 48.dp
    val PagePaddingV = 27.dp
    val RowSpacing = 24.dp
    val CardSpacing = 16.dp
    val WideCardWidth = 240.dp   // 16:9
    val PosterCardWidth = 140.dp // 2:3
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BootColors.Accent,
            onPrimary = Color.White,
            background = BootColors.Background,
            onBackground = BootColors.TextPrimary,
            surface = BootColors.Surface,
            onSurface = BootColors.TextPrimary,
            surfaceVariant = BootColors.SurfaceRaised,
            onSurfaceVariant = BootColors.TextSecondary,
            border = BootColors.Accent,
        ),
        content = content,
    )
}
