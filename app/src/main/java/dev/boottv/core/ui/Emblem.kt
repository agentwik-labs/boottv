package dev.boottv.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** A red-and-white capture ball, drawn here (the Pocket Monsters theme's emblem). */
@Composable
fun BallEmblem(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        val line = r * 0.16f
        drawArc(Color(0xFFEE1515), 180f, 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
        drawArc(Color.White, 0f, 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
        drawRect(Color(0xFF1B1B1F), topLeft = Offset(c.x - r, c.y - line / 2), size = Size(r * 2, line))
        drawCircle(Color(0xFF1B1B1F), radius = r, center = c, style = Stroke(width = line * 0.9f))
        drawCircle(Color(0xFF1B1B1F), radius = r * 0.34f, center = c)
        drawCircle(Color.White, radius = r * 0.22f, center = c)
    }
}

/** "BootTV" in the Pocket Monsters theme: bold yellow letters with a blue outline-style shadow, and the ball. */
@Composable
fun PocketWordmark(fontSize: TextUnit = 26.sp, ball: Dp = 28.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BallEmblem(ball)
        Spacer(Modifier.width(10.dp))
        Text(
            "BootTV",
            style = TextStyle(
                fontFamily = Fredoka, fontWeight = FontWeight.Bold, fontSize = fontSize, color = Color(0xFFFFCB05),
                shadow = Shadow(Color(0xFF2A75BB), offset = Offset(3f, 3f), blurRadius = 0f),
            ),
            maxLines = 1,
        )
    }
}
