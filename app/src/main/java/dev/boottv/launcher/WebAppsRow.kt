package dev.boottv.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.browser.WebApp
import dev.boottv.browser.WebApps
import dev.boottv.browser.tileColors
import dev.boottv.core.ui.BootButton
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDialog
import dev.boottv.core.ui.BootDimens
import dev.boottv.livetv.TvTextField
import kotlinx.coroutines.delay
import kotlin.math.abs

private val TileShape = RoundedCornerShape(12.dp)

internal fun webAppColor(app: WebApp): Color = tileColors[abs(app.host.hashCode()) % tileColors.size]

/** "Web apps" row: websites pinned to Home. OK opens one full screen, hold OK for options, last tile adds one. */
@Composable
internal fun WebAppsRow(
    apps: List<WebApp>,
    requester: (String) -> FocusRequester,
    onFocused: (key: String, Featured?) -> Unit,
    onOpen: (WebApp) -> Unit,
    onOptions: (WebApp) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Web apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BootColors.TextPrimary)
            Spacer(Modifier.width(12.dp))
            Text(
                if (apps.isEmpty()) "Turn any website into an app" else "Hold OK for options",
                style = MaterialTheme.typography.bodySmall,
                color = BootColors.TextSecondary,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
        ) {
            items(apps, key = { it.url }) { app ->
                val k = "web:${app.url}"
                val color = webAppColor(app)
                WebTile(
                    color = color,
                    modifier = Modifier.focusRequester(requester(k)).onFocusChanged { if (it.isFocused) onFocused(k, Featured.Web(app)) },
                    onClick = { onOpen(app) },
                    onLongClick = { onOptions(app) },
                ) {
                    Text(
                        app.name.trim().take(1).uppercase(),
                        color = Color.White.copy(alpha = 0.25f),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(app.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(app.host, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(key = "add") {
                WebTile(
                    color = BootColors.SurfaceRaised,
                    modifier = Modifier.focusRequester(requester("web:add")).onFocusChanged { if (it.isFocused) onFocused("web:add", null) },
                    onClick = onAdd,
                ) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, null, Modifier.size(34.dp), tint = BootColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("Add website", color = BootColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun WebTile(
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(BootDimens.WideCardWidth * 0.75f).aspectRatio(16f / 9f),
        shape = CardDefaults.shape(TileShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = TileShape)),
        colors = CardDefaults.colors(containerColor = color),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.7f)))), content = content)
    }
}

/** Name + address form for pinning a website to Home. */
@Composable
internal fun AddWebAppDialog(onDismiss: () -> Unit, onSave: (WebApp) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    BootDialog(onDismiss = onDismiss, title = "Add a website", subtitle = "It gets its own tile on Home and opens in the BootTV browser.", width = 560.dp) {
        Box(Modifier.focusRequester(first)) {
            TvTextField("Web address", address, { address = it; error = null }, placeholder = "example.com")
        }
        // Down from the last field goes to the main button, not whichever button is geometrically closer.
        Box(Modifier.focusProperties { down = save }) {
            TvTextField("Name (optional)", name, { name = it }, placeholder = "Shown on the tile", keyboardType = KeyboardType.Text)
        }
        error?.let { Text(it, color = BootColors.Live, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BootButton("Add to Home", onClick = {
                val url = WebApps.normalize(address)
                if (url == null) {
                    error = "Enter a web address like example.com"
                } else {
                    val app = WebApp(name.trim().ifEmpty { WebApp("", url).host }, url)
                    onSave(app)
                }
            }, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(save))
            BootButton("Cancel", onClick = onDismiss)
        }
    }
}

@Composable
internal fun WebAppOptionsDialog(app: WebApp, onDismiss: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    BootDialog(onDismiss = onDismiss, title = app.name, subtitle = app.url) {
        BootButton("Open", onClick = onOpen, icon = Icons.Filled.OpenInNew, modifier = Modifier.fillMaxWidth().focusRequester(first))
        BootButton("Remove from Home", onClick = onRemove, icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth())
    }
}
