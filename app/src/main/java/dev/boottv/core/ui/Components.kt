package dev.boottv.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Focusable surfaces show focus by turning white (Apple TV style), so no outline. */
@Composable
fun bootFocusBorder(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)) =
    ClickableSurfaceDefaults.border(focusedBorder = Border.None)

/**
 * A D-pad friendly button/row: a faint translucent surface that turns white with dark text when focused.
 */
@Composable
fun BootButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    trailing: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = bootFocusBorder(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BootColors.AccentSoft else Color.White.copy(alpha = 0.08f),
            contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused,
            focusedContentColor = BootColors.OnFocused,
            disabledContainerColor = BootColors.Surface,
            disabledContentColor = BootColors.TextSecondary,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = trailing != null))
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Text(trailing, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Dialogs are often opened by a long-press of OK; the matching key-up then lands on the dialog's
 * first button and would click it. This drops OK key-ups that had no key-down inside the dialog.
 */
@Composable
fun Modifier.swallowOrphanKeyUp(): Modifier {
    var sawDown by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { ev ->
        val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
        if (!isOk) return@onPreviewKeyEvent false
        if (ev.type == KeyEventType.KeyDown) {
            // Auto-repeat downs from a held key (the long-press that opened us) don't count.
            if (ev.nativeKeyEvent.repeatCount == 0) sawDown = true
            !sawDown
        } else {
            !sawDown
        }
    }
}

/** A centered modal panel for TV. Initial focus should be requested by the caller's content. */
@Composable
fun BootDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: androidx.compose.ui.unit.Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .swallowOrphanKeyUp()
                .width(width)
                .background(BootColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, BootColors.SurfaceRaised, RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/**
 * On-screen 4-digit PIN pad (also accepts the remote's number keys).
 * [onComplete] is called once 4 digits are entered; return false to reject (shows [errorText]).
 */
@Composable
fun PinPadDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    onComplete: (String) -> Boolean,
    errorText: String = "Incorrect PIN. Try again.",
    initialError: String? = null,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember(initialError) { mutableStateOf(initialError) }
    val firstKey = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstKey.requestFocus() }
    }

    fun type(d: Char) {
        if (entered.length >= 4) return
        entered += d
        error = null
        if (entered.length == 4) {
            val pin = entered
            if (!onComplete(pin)) {
                error = errorText
            }
            entered = ""
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(420.dp)
                .background(BootColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, BootColors.SurfaceRaised, RoundedCornerShape(18.dp))
                .padding(28.dp)
                .swallowOrphanKeyUp()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = when (ev.key) {
                        Key.Zero, Key.NumPad0 -> '0'
                        Key.One, Key.NumPad1 -> '1'
                        Key.Two, Key.NumPad2 -> '2'
                        Key.Three, Key.NumPad3 -> '3'
                        Key.Four, Key.NumPad4 -> '4'
                        Key.Five, Key.NumPad5 -> '5'
                        Key.Six, Key.NumPad6 -> '6'
                        Key.Seven, Key.NumPad7 -> '7'
                        Key.Eight, Key.NumPad8 -> '8'
                        Key.Nine, Key.NumPad9 -> '9'
                        else -> null
                    }
                    if (digit != null) { type(digit); true } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BootColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < entered.length
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(if (filled) BootColors.Accent else Color.Transparent, CircleShape)
                            .border(2.dp, if (filled) BootColors.Accent else BootColors.TextSecondary, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                error ?: " ",
                color = BootColors.Live,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            val rows = listOf("123", "456", "789", "<0x")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c ->
                            val mod = if (c == '1') Modifier.focusRequester(firstKey) else Modifier
                            PinKey(
                                modifier = mod,
                                label = c.takeIf { it.isDigit() }?.toString(),
                                icon = when (c) {
                                    '<' -> Icons.AutoMirrored.Filled.Backspace
                                    'x' -> Icons.Filled.Close
                                    else -> null
                                },
                                onClick = {
                                    when (c) {
                                        '<' -> { entered = entered.dropLast(1); error = null }
                                        'x' -> onDismiss()
                                        else -> type(c)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(modifier: Modifier, label: String?, icon: ImageVector?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 96.dp, height = 56.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = bootFocusBorder(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BootColors.SurfaceRaised,
            contentColor = BootColors.TextPrimary,
            focusedContainerColor = BootColors.Focused,
            focusedContentColor = BootColors.OnFocused,
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(4.dp).align(Alignment.Center), contentAlignment = Alignment.Center) {
            if (label != null) {
                Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Two-step "enter new PIN / confirm PIN" flow. */
@Composable
fun PinSetupDialog(onDismiss: () -> Unit, onPinSet: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val step = first
    if (step == null) {
        PinPadDialog(
            title = "Create a PIN",
            message = "Choose a 4-digit parental PIN.",
            onDismiss = onDismiss,
            initialError = if (mismatch) "PINs didn't match. Start again." else null,
            onComplete = { pin -> first = pin; mismatch = false; true },
        )
    } else {
        // Keyed so the pad's internal state resets for the confirmation step.
        androidx.compose.runtime.key("confirm") {
            PinPadDialog(
                title = "Confirm PIN",
                message = "Enter the same PIN again.",
                onDismiss = onDismiss,
                onComplete = { pin ->
                    if (pin == step) {
                        onPinSet(pin)
                    } else {
                        mismatch = true
                        first = null
                    }
                    true
                },
            )
        }
    }
}
