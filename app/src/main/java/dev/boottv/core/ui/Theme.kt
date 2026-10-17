package dev.boottv.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.boottv.settings.BootPrefs

/** A user-selectable accent color (Settings > Home screen > Accent color). */
data class AccentPreset(val id: String, val name: String, val color: Color)

object AccentPresets {
    val all = listOf(
        AccentPreset("purple", "Purple", Color(0xFF7B5CFF)),
        AccentPreset("blue", "Blue", Color(0xFF3D8BFF)),
        AccentPreset("teal", "Teal", Color(0xFF14B8A6)),
        AccentPreset("green", "Green", Color(0xFF34C759)),
        AccentPreset("orange", "Orange", Color(0xFFFF8A00)),
        AccentPreset("pink", "Pink", Color(0xFFEC4899)),
    )
    val default = all.first()
    fun byId(id: String?): AccentPreset = all.firstOrNull { it.id == id } ?: default
}

/**
 * Shared palette. Feature code should use these (or MaterialTheme.colorScheme) instead of raw colors.
 *
 * [Accent] and [AccentSoft] are backed by Compose snapshot state, so composables that read them
 * recompose automatically when the user picks a new accent color in Settings.
 */
object BootColors {
    private val accentState = mutableStateOf(AccentPresets.default.color)

    val Background = Color(0xFF0B0B14)
    val Surface = Color(0xFF161624)
    val SurfaceRaised = Color(0xFF22223A)
    val Accent: Color get() = accentState.value
    val AccentSoft: Color get() = accentState.value.copy(alpha = 0.2f)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFFA0A0B8)
    val Live = Color(0xFFE5484D)

    internal fun setAccent(color: Color) {
        if (accentState.value != color) accentState.value = color
    }
}

/** Shared spacing: TV overscan-safe page padding and card sizes. */
object BootDimens {
    val PagePaddingH = 48.dp
    val PagePaddingV = 27.dp
    val RowSpacing = 24.dp
    val CardSpacing = 16.dp
    val WideCardWidth = 240.dp   // 16:9
    val PosterCardWidth = 140.dp // 2:3

    /** Width of the collapsed side menu rail; screens are laid out to the right of it. */
    val SideMenuCollapsed = 76.dp
    val SideMenuExpanded = 248.dp
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BootTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Apply the persisted accent color (and follow live changes from Settings).
    LaunchedEffect(Unit) {
        BootPrefs.accentIdFlow(context).collect { BootColors.setAccent(AccentPresets.byId(it).color) }
    }
    val accent = BootColors.Accent
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = BootColors.Background,
            onBackground = BootColors.TextPrimary,
            surface = BootColors.Surface,
            onSurface = BootColors.TextPrimary,
            surfaceVariant = BootColors.SurfaceRaised,
            onSurfaceVariant = BootColors.TextSecondary,
            border = accent,
        ),
        content = content,
    )
}
