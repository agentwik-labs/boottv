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

/** A look for the whole app (Settings > Home screen > Theme). */
data class ThemePreset(
    val id: String,
    val name: String,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val live: Color,
    /** Focused buttons, menu entries and guide cells. */
    val focused: Color,
    val onFocused: Color,
    /** The soft light around a focused card. */
    val glow: Color,
    /** Fixed accent, or null to use the accent colour picked in Settings. */
    val accent: Color? = null,
    /** Rounded display face for titles (body text stays Inter). */
    val playful: Boolean = false,
    /** The partner Pokémon when Settings leaves it to the theme. */
    val partner: Int = 25,
    /** Pokémon drawn for placeholders and the daily partner; null = the friendly default set. */
    val pool: IntArray? = null,
    /** Wordmark letters and their shadow. */
    val brand: Color = Color(0xFFFFCB05),
    val brandShadow: Color = Color(0xFF2A75BB),
    /** The Pokédex hand-held's shell (My Media). */
    val shell: Color = Color(0xFFDC0A2D),
    val shellDark: Color = Color(0xFF9E0520),
)

object ThemePresets {
    /** Quiet, near-black greys in the spirit of Apple TV: colour comes from the artwork, not the chrome. */
    val Minimal = ThemePreset(
        id = "minimal", name = "Minimal",
        background = Color(0xFF0A0A0C), surface = Color(0xFF1C1C1E), surfaceRaised = Color(0xFF2C2C2E),
        textPrimary = Color(0xFFF5F5F7), textSecondary = Color(0xFF8E8E93), live = Color(0xFFFF453A),
        focused = Color(0xFFF5F5F7), onFocused = Color(0xFF111114), glow = Color.White.copy(alpha = 0.32f),
    )

    /**
     * Pokémon-inspired, drawn from scratch (no Nintendo artwork): a night-battle indigo, Poké Ball red, and
     * electric yellow for whatever has focus.
     */
    val Pocket = ThemePreset(
        id = "pocket", name = "Pocket Monsters",
        background = Color(0xFF141A3C), surface = Color(0xFF212A5E), surfaceRaised = Color(0xFF2D3878),
        textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0xFFB4BEFF), live = Color(0xFFEE1515),
        focused = Color(0xFFFFCB05), onFocused = Color(0xFF1B1B1F), glow = Color(0xFFFFCB05).copy(alpha = 0.45f),
        accent = Color(0xFFEE1515), playful = true,
    )

    /**
     * Gengar flavour: Ghost-type purples, a grin of lilac for focus, Gengar as the partner and ghosts everywhere
     * sprites appear. Same layout and features as Pocket Monsters.
     */
    val Gengar = ThemePreset(
        id = "gengar", name = "Gengar",
        background = Color(0xFF140B24), surface = Color(0xFF2A1A45), surfaceRaised = Color(0xFF3C2866),
        textPrimary = Color(0xFFF4ECFF), textSecondary = Color(0xFFC9B3F0), live = Color(0xFFFF3B5C),
        focused = Color(0xFFE2C4FF), onFocused = Color(0xFF1B0F2E), glow = Color(0xFFA040A0).copy(alpha = 0.55f),
        accent = Color(0xFFA040A0), playful = true,
        partner = 94,
        pool = intArrayOf(92, 93, 94, 200, 302, 353, 354, 355, 356, 425, 426, 429, 442, 477, 478, 479, 562, 563, 592, 593, 607, 608, 609, 622, 623),
        brand = Color(0xFFE2C4FF), brandShadow = Color(0xFF6B2FA0),
        shell = Color(0xFF5B2A86), shellDark = Color(0xFF3A1A5C),
    )

    val all = listOf(Gengar, Pocket, Minimal)
    val default = Gengar
    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id == id } ?: default
}

/**
 * Shared palette. Feature code should use these (or MaterialTheme.colorScheme) instead of raw colors.
 *
 * Backed by Compose snapshot state, so composables that read them recompose automatically when the theme or
 * accent colour changes in Settings.
 */
object BootColors {
    private val accentState = mutableStateOf(AccentPresets.default.color)
    private val themeState = mutableStateOf(ThemePresets.default)

    val theme: ThemePreset get() = themeState.value
    val Background: Color get() = theme.background
    val Surface: Color get() = theme.surface
    val SurfaceRaised: Color get() = theme.surfaceRaised
    val Accent: Color get() = theme.accent ?: accentState.value
    val AccentSoft: Color get() = Accent.copy(alpha = 0.2f)
    val TextPrimary: Color get() = theme.textPrimary
    val TextSecondary: Color get() = theme.textSecondary
    val Live: Color get() = theme.live
    val Focused: Color get() = theme.focused
    val OnFocused: Color get() = theme.onFocused
    val Glow: Color get() = theme.glow

    internal fun setAccent(color: Color) {
        if (accentState.value != color) accentState.value = color
    }

    internal fun setTheme(preset: ThemePreset) {
        if (themeState.value != preset) themeState.value = preset
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
    LaunchedEffect(Unit) {
        BootPrefs.themeIdFlow(context).collect { BootColors.setTheme(ThemePresets.byId(it)) }
    }
    LaunchedEffect(Unit) {
        BootPrefs.flow(context).collect { Sprites.enabled = it.sprites; Sprites.partnerChoice = it.partner }
    }
    val accent = BootColors.Accent
    val typography = if (BootColors.theme.playful) PlayfulTypography else BootTypography
    MaterialTheme(
        typography = typography,
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondaryContainer = BootColors.Focused,
            onSecondaryContainer = BootColors.OnFocused,
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
        ProvideTextStyle(typography.bodyLarge, content)
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

/** Press Start 2P: the 8-bit face for Dex numbers, labels and badges (SIL Open Font License, see assets/licenses). */
val Pixel = FontFamily(Font(R.font.press_start_2p, FontWeight.Normal))

/** Fredoka: a round, friendly face for the Pocket Monsters theme (SIL Open Font License, see assets/licenses). */
val Fredoka = FontFamily(
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

private fun TextStyle.fredoka(weight: FontWeight) = copy(fontFamily = Fredoka, fontWeight = weight, letterSpacing = 0.sp)

/** Titles in Fredoka, everything you read at length still in Inter. */
private val PlayfulTypography = BootTypography.run {
    copy(
        displayLarge = displayLarge.fredoka(FontWeight.Bold),
        displayMedium = displayMedium.fredoka(FontWeight.Bold),
        displaySmall = displaySmall.fredoka(FontWeight.Bold),
        headlineLarge = headlineLarge.fredoka(FontWeight.Bold),
        headlineMedium = headlineMedium.fredoka(FontWeight.SemiBold),
        headlineSmall = headlineSmall.fredoka(FontWeight.SemiBold),
        titleLarge = titleLarge.fredoka(FontWeight.SemiBold),
        titleMedium = titleMedium.fredoka(FontWeight.SemiBold),
        titleSmall = titleSmall.fredoka(FontWeight.Medium),
        labelLarge = labelLarge.fredoka(FontWeight.SemiBold),
    )
}
