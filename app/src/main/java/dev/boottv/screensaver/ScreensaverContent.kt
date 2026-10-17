package dev.boottv.screensaver

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Each palette is three aurora blob colors; the screensaver slowly cross-fades between them. */
private val PALETTES = listOf(
    listOf(Color(0xFF7B5CFF), Color(0xFF1E90FF), Color(0xFF00C2A8)),
    listOf(Color(0xFFE5484D), Color(0xFF7B5CFF), Color(0xFFF76B15)),
    listOf(Color(0xFF12A594), Color(0xFF3E63DD), Color(0xFF8E4EC6)),
    listOf(Color(0xFFD6409F), Color(0xFFFFB224), Color(0xFF7B5CFF)),
    listOf(Color(0xFF0090FF), Color(0xFF46A758), Color(0xFF6E56CF)),
)
private const val PALETTE_SECONDS = 24f
private const val CLOCK_MOVE_MS = 40_000L

@Composable
internal fun ScreensaverContent() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Aurora()
        DriftingClock()
    }
}

@Composable
private fun Aurora() {
    // Seconds since start; read only inside the draw lambda so frames redraw without recomposing.
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { time = (it - start) / 1e9f }
            delay(33) // ~30 fps is plenty for motion this slow, and saves power.
        }
    }
    Canvas(Modifier.fillMaxSize().alpha(0.6f)) {
        val t = time
        val phase = t / PALETTE_SECONDS
        val idx = phase.toInt()
        val frac = smooth(phase - idx)
        val from = PALETTES[idx % PALETTES.size]
        val to = PALETTES[(idx + 1) % PALETTES.size]
        val w = size.width
        val h = size.height
        val radius = max(w, h) * 0.55f
        for (i in 0 until 3) {
            val color = lerp(from[i], to[i], frac)
            val speed = 0.021f + i * 0.007f
            val cx = w * (0.5f + 0.38f * sin(t * speed * 2f * PI.toFloat() + i * 2.1f))
            val cy = h * (0.5f + 0.34f * cos(t * speed * 1.3f * PI.toFloat() + i * 1.7f))
            val pulse = 1f + 0.12f * sin(t * 0.05f + i)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius * pulse,
                ),
                radius = radius * pulse,
                center = Offset(cx, cy),
            )
        }
        // Vignette keeps edges dark and the whole picture dim.
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                center = Offset(w / 2f, h / 2f),
                radius = max(w, h) * 0.75f,
            ),
        )
    }
}

private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

@Composable
private fun DriftingClock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000 + 5)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val timeFmt = remember(is24, locale) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", locale) }
    val ampmFmt = remember(locale) { SimpleDateFormat("a", locale) }
    val dateFmt = remember(locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    }
    val date = Date(now)

    // Fractional position (0..1 on each axis) of the clock inside the free space; drifts to a
    // new random spot every CLOCK_MOVE_MS so no pixel shows the same thing for long.
    val fx = remember { Animatable(0.5f) }
    val fy = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) {
        val rnd = Random(System.nanoTime())
        while (true) {
            delay(CLOCK_MOVE_MS / 4)
            val tx = rnd.nextFloat()
            val ty = rnd.nextFloat()
            launch { fx.animateTo(tx, tween((CLOCK_MOVE_MS * 0.75f).toInt(), easing = FastOutSlowInEasing)) }
            fy.animateTo(ty, tween((CLOCK_MOVE_MS * 0.75f).toInt(), easing = FastOutSlowInEasing))
        }
    }
    // Very slow breathing of brightness also helps against burn-in.
    var breath by remember { mutableFloatStateOf(0.85f) }
    LaunchedEffect(Unit) {
        var t = 0f
        while (true) {
            breath = 0.72f + 0.16f * (0.5f + 0.5f * sin(t))
            t += 0.05f
            delay(250)
        }
    }

    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 4f), 16f)
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .onSizeChanged { contentSize = it }
                .offset {
                    IntOffset(
                        ((maxW - contentSize.width).coerceAtLeast(0) * fx.value).roundToInt(),
                        ((maxH - contentSize.height).coerceAtLeast(0) * fy.value).roundToInt(),
                    )
                }
                .alpha(breath),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    timeFmt.format(date),
                    style = TextStyle(fontSize = 104.sp, fontWeight = FontWeight.Light, color = Color.White, shadow = shadow),
                )
                if (!is24) {
                    Text(
                        ampmFmt.format(date),
                        style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.8f), shadow = shadow),
                        modifier = Modifier.padding(start = 10.dp, bottom = 22.dp),
                    )
                }
            }
            Text(
                dateFmt.format(date),
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.85f), shadow = shadow),
            )
        }
    }
}
