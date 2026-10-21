package dev.boottv.jellyfin

import dev.boottv.core.ui.BootCard
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFramePercent
import dev.boottv.core.ui.SpriteArt
import dev.boottv.core.ui.LoadingIndicator
import dev.boottv.core.ui.PokeSprite
import dev.boottv.core.ui.Sprites
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens

private val CardShape = RoundedCornerShape(10.dp)

@Composable
internal fun JfImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: ImageVector = Icons.Filled.Movie,
    plain: Boolean = false,
    alignment: Alignment = Alignment.Center,
) {
    Box(if (plain) modifier else modifier.background(BootColors.SurfaceRaised), contentAlignment = Alignment.Center) {
        if (!plain) {
            // Pocket Monsters theme: a Pokémon on a coloured card until (or instead of) the picture.
            if (Sprites.on) SpriteArt(url ?: fallback.name, Modifier.fillMaxSize())
            else Icon(fallback, contentDescription = null, tint = BootColors.TextSecondary, modifier = Modifier.size(36.dp))
        }
        if (url != null) {
            val ctx = LocalContext.current
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(ctx).data(url).crossfade(true)
                        // Your own videos: a frame from a little way in, not the black first frame.
                        .apply { if (url.startsWith("content://") && "/video/" in url) videoFramePercent(0.2) }
                        .build()
                },
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(BootColors.Accent, RoundedCornerShape(2.dp))
        )
    }
}

/** A focusable media card; poster (2:3) or wide (16:9). */
@Composable
internal fun MediaCard(
    item: BaseItem,
    client: JellyfinClient,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    showText: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val width: Dp = if (wide) BootDimens.WideCardWidth else BootDimens.PosterCardWidth
    val url = if (wide) client.wideUrl(item) else client.posterUrl(item)
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
            shape = CardDefaults.shape(CardShape),
            scale = BootCard.scale,
            border = BootCard.border,
            glow = BootCard.glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                JfImage(url, Modifier.fillMaxSize(), fallback = if (item.isBrowsableFolder) Icons.Filled.Folder else Icons.Filled.Movie)
                if (url == null || item.isBrowsableFolder && wide) {
                    Box(
                        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            item.cardTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (item.userData?.played == true && !item.isBrowsableFolder) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).background(BootColors.Accent, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                } else if ((item.userData?.unplayedItemCount ?: 0) > 0 && item.isSeries) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).background(BootColors.Accent, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("${item.userData?.unplayedItemCount}", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
                if (item.progress > 0f) {
                    ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
                }
            }
        }
        if (showText) {
            Spacer(Modifier.height(6.dp))
            Text(
                item.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary
            )
            item.cardSubtitle?.let {
                Text(
                    it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary
                )
            }
        }
    }
}

@Composable
internal fun MediaRow(
    title: String,
    items: List<BaseItem>,
    client: JellyfinClient,
    wide: Boolean,
    onClick: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((BaseItem) -> Unit)? = null,
    onFocused: ((BaseItem) -> Unit)? = null,
    focusKey: String? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = BootColors.TextPrimary, modifier = Modifier.padding(start = BootDimens.PagePaddingH, bottom = 10.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(horizontal = BootDimens.PagePaddingH, vertical = 8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    client = client,
                    wide = wide,
                    onClick = { onClick(item) },
                    onLongClick = onLongClick?.let { { it(item) } },
                    onFocused = onFocused?.let { { it(item) } },
                    focusRequester = if (focusKey != null && item.id == focusKey) focusRequester else null,
                )
            }
        }
    }
}

@Composable
internal fun JfButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1)
    }
    if (primary) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** Small selectable pill (season tabs, sort options). */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BootColors.AccentSoft else BootColors.Surface,
            contentColor = if (selected) Color.White else BootColors.TextSecondary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, BootColors.Accent), shape = RoundedCornerShape(50)) else Border.None,
        ),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun CenteredMessage(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    /** The Pokémon shown above the title in the Pocket Monsters theme (Psyduck, puzzled, by default). */
    sprite: Int = 54,
    actions: @Composable (FocusRequester) -> Unit = {},
) {
    val fr = remember { FocusRequester() }
    Box(modifier.fillMaxSize().padding(horizontal = BootDimens.PagePaddingH, vertical = BootDimens.PagePaddingV), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 640.dp)) {
            if (Sprites.on) PokeSprite(sprite, 128.dp, animated = true)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions(fr) }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
internal fun LoadingMessage(text: String = "Loading…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(text) }
}

/** Human readable error for exceptions from the API. */
internal fun Throwable.friendly(server: String?): String = when (this) {
    is JellyfinException -> when (code) {
        401, 403 -> "Your sign-in has expired. Sign in again in Settings."
        404 -> "That item isn't on the server anymore."
        else -> "The server returned an error (HTTP $code)."
    }
    is kotlinx.serialization.SerializationException -> "The server sent a response BootTV didn't understand."
    else -> "Can't reach your media server${server?.let { " at $it" } ?: ""}. Make sure your Mac is awake and Jellyfin is running."
}

internal val Throwable.isAuthError get() = this is JellyfinException && (code == 401 || code == 403)

internal fun metaLine(item: BaseItem): String = listOfNotNull(
    item.productionYear?.toString(),
    item.officialRating,
    item.communityRating?.let { "★ %.1f".format(it) },
    item.runtimeText,
    item.episodeLabel,
).joinToString("  ·  ")

