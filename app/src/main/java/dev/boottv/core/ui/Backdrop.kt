package dev.boottv.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pages are transparent in the Pokémon themes so the one backdrop behind the whole app shows through; Minimal keeps a
 * plain fill per page.
 */
fun Modifier.pageBackground(): Modifier = composed {
    if (BootColors.theme.playful) this else this.background(BootColors.Background)
}

/**
 * The scene behind every screen in the Pokémon themes: a deep gradient, a glow, the theme's partner as large faded
 * artwork in the corner, and (with sprites on) slow drifting wisps. Drawn once at the activity's root.
 */
@Composable
fun BoxScope.ThemeBackdrop() {
    val theme = BootColors.theme
    val accent = theme.accent ?: BootColors.Accent
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(theme.background.lighten(0.06f), theme.background, theme.background.darken(0.25f)),
                start = Offset.Zero, end = Offset(2200f, 1400f),
            )
        )
    )
    // A soft glow where the partner sits.
    Box(
        Modifier.align(Alignment.BottomEnd).width(900.dp).height(700.dp).offset(x = 200.dp, y = 200.dp)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), Color.Transparent))),
    )
    if (Sprites.enabled) {
        val context = LocalContext.current
        val url = Sprites.artwork(theme.partner)
        AsyncImage(
            model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(800).build() },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.BottomEnd).height(560.dp).width(560.dp).offset(x = 70.dp, y = 90.dp).alpha(0.16f),
        )
        Wisps(theme.focused) // the focus colour: lilac in Gengar, electric yellow in Pocket Monsters
    }
    // Vignette so text at the edges stays readable.
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), radius = 1600f)))
}

private data class Wisp(val x: Float, val phase: Float, val speed: Float, val size: Float, val sway: Float)

/** Faint lights drifting upward, like will-o'-the-wisps (or fireflies in a brighter theme). */
@Composable
private fun Wisps(color: Color) {
    val wisps = remember { val r = Random(7); List(14) { Wisp(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat(), 6f + r.nextFloat() * 14f, 20f + r.nextFloat() * 50f) } }
    val t by rememberInfiniteTransition(label = "wisps").animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        for (w in wisps) {
            val p = ((t * w.speed + w.phase) % 1f)
            val y = size.height * (1.1f - p * 1.2f)
            val x = size.width * w.x + sin(p * 6.283f * 2f) * w.sway
            val fade = (if (p < 0.15f) p / 0.15f else if (p > 0.8f) (1f - p) / 0.2f else 1f).coerceIn(0f, 1f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * fade), Color.Transparent), center = Offset(x, y), radius = w.size * 3), w.size * 3, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.35f * fade), w.size * 0.35f, Offset(x, y))
        }
    }
}

private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
