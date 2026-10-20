package dev.boottv.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import dev.boottv.core.ui.BootColors

/** Round icon button for toolbars. [active] tints it to show a toggle that is on. */
@Composable
internal fun BrowserIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    onFocused: (String) -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .onFocusChanged { if (it.isFocused) onFocused(label) },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) BootColors.AccentSoft else Color.Transparent,
            contentColor = if (enabled) BootColors.TextPrimary else BootColors.TextSecondary.copy(alpha = 0.5f),
            focusedContainerColor = BootColors.Focused,
            focusedContentColor = BootColors.OnFocused,
            pressedContainerColor = BootColors.Accent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** Rectangular focusable surface with Boot styling (accent fill + white border when focused). */
@Composable
internal fun BrowserSurfaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    container: Color = BootColors.Surface,
    focusedScale: Float = 1.04f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused,
            focusedContentColor = BootColors.OnFocused,
            pressedContainerColor = BootColors.Accent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
    ) { content() }
}
