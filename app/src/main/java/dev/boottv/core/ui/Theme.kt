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
import dev.boottv.R
import dev.boottv.settings.BootPrefs
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import androidx.tv.material3.ProvideTextStyle

/** A user-selectable accent color (Settings > Home screen > Accent color). */
data class AccentPreset(val id: String, val name: String, val color: Color)

object AccentPresets {
    val all = listOf(
        AccentPreset("blue", "Blue", Color(0xFF0A84FF)),
        AccentPreset("purple", "Purple", Color(0xFFBF5AF2)),
        AccentPreset("teal", "Teal", Color(0xFF40C8E0)),
        AccentPreset("green", "Green", Color(0xFF30D158)),
        AccentPreset("orange", "Orange", Color(0xFFFF9F0A)),
        AccentPreset("pink", "Pink", Color(0xFFFF375F)),
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

    // Quiet, near-black greys in the spirit of Apple TV: colour comes from the artwork, not the chrome.
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceRaised = Color(0xFF2C2C2E)
    val Accent: Color get() = accentState.value
    val AccentSoft: Color get() = accentState.value.copy(alpha = 0.2f)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF8E8E93)
    val Live = Color(0xFFFF453A)
    /** Focused buttons and menu entries: white with dark text, as on Apple TV. */
    val Focused = Color(0xFFF5F5F7)
    val OnFocused = Color(0xFF111114)

    internal fun setAccent(color: Color) {
        if (accentState.value != color) accentState.value = color
    }
}

/** Shared spacing: TV overscan-safe page padding and card sizes. */
object BootDimens {
    val PagePaddingH = 56.dp
    val PagePaddingV = 27.dp
    val RowSpacing = 28.dp
    val CardSpacing = 20.dp
    val WideCardWidth = 240.dp   // 16:9
    val PosterCardWidth = 140.dp // 2:3

    /** Width of the collapsed side menu rail; screens are laid out to the right of it. */
    val SideMenuCollapsed = 72.dp
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
        typography = BootTypography,
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = BootColors.Background,
            onBackground = BootColors.TextPrimary,
            surface = BootColors.Surface,
            onSurface = BootColors.TextPrimary,
            surfaceVariant = BootColors.SurfaceRaised,
            onSurfaceVariant = BootColors.TextSecondary,
            // Hairlines (filter chips, outlines) stay neutral; focus is shown with white, not colour.
            border = Color.White.copy(alpha = 0.18f),
            borderVariant = Color.White.copy(alpha = 0.1f),
            inverseSurface = BootColors.Focused,
            inverseOnSurface = BootColors.OnFocused,
        ),
    ) {
        // Every Text without its own style (many set only a size or weight) still gets Inter.
        ProvideTextStyle(BootTypography.bodyLarge, content)
    }
}

/** Inter: a clean, neutral face close to Apple's San Francisco (SIL Open Font License, see assets/licenses). */
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun TextStyle.inter(weight: FontWeight? = null, tracking: Float? = null) =
    copy(fontFamily = Inter, fontWeight = weight ?: fontWeight, letterSpacing = tracking?.sp ?: letterSpacing)

/** Material's sizes in Inter, with slightly tighter display text the way tvOS sets large titles. */
private val BootTypography = Typography().run {
    copy(
        displayLarge = displayLarge.inter(FontWeight.Bold, -1f),
        displayMedium = displayMedium.inter(FontWeight.Bold, -0.8f),
        displaySmall = displaySmall.inter(FontWeight.Bold, -0.5f),
        headlineLarge = headlineLarge.inter(FontWeight.Bold, -0.4f),
        headlineMedium = headlineMedium.inter(FontWeight.SemiBold, -0.3f),
        headlineSmall = headlineSmall.inter(FontWeight.SemiBold, -0.2f),
        titleLarge = titleLarge.inter(FontWeight.SemiBold),
        titleMedium = titleMedium.inter(FontWeight.SemiBold),
        titleSmall = titleSmall.inter(FontWeight.Medium),
        bodyLarge = bodyLarge.inter(),
        bodyMedium = bodyMedium.inter(),
        bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(FontWeight.Medium),
        labelMedium = labelMedium.inter(FontWeight.Medium),
        labelSmall = labelSmall.inter(FontWeight.Medium),
    )
}
