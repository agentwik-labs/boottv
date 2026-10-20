package dev.boottv.launcher

import dev.boottv.core.ui.leftToMenu
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.browser.WebApp
import dev.boottv.jellyfin.BaseItem
import dev.boottv.jellyfin.JellyfinClient
import dev.boottv.jellyfin.JfImage
import dev.boottv.jellyfin.MediaCard
import dev.boottv.jellyfin.metaLine
import dev.boottv.livetv.Channel
import dev.boottv.livetv.LiveState
import dev.boottv.livetv.LiveTvRepository
import dev.boottv.livetv.Programme
import dev.boottv.livetv.animationChannels
import dev.boottv.livetv.LiveVideoSurface
import dev.boottv.livetv.PlayerLifecycle
import dev.boottv.livetv.createLivePlayer
import dev.boottv.livetv.tune
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.DateFormat
import java.util.Date

/** One live channel with what's on right now. */
internal data class OnNow(val channel: Channel, val now: Programme?, val next: Programme?)

/** [channels] with what's on now and next. */
internal fun LiveState.onNow(channels: List<Channel>, now: Long = System.currentTimeMillis()): List<OnNow> =
    channels.map { ch -> nowAndNext(ch.id, now).let { (n, x) -> OnNow(ch, n, x) } }

/** What the big banner at the top of Home shows: the focused (or first) item from the feed rows. */
internal sealed interface Featured {
    val key: String
    data class Media(val item: BaseItem, val client: JellyfinClient) : Featured { override val key = "media:${item.id}" }
    data class Live(val onNow: OnNow) : Featured { override val key = "live:${onNow.channel.id}" }
    data class App(val app: FreeApp) : Featured { override val key = "app:${app.pkg}" }
    data class Web(val app: WebApp) : Featured { override val key = "web:${app.url}" }
}

/**
 * Content rows for Home (Continue Watching from My Media, On Now from Live TV).
 * Kept in a process-wide object so coming back to Home shows the last rows instantly while they refresh.
 */
internal object HomeFeed {
    var client by mutableStateOf<JellyfinClient?>(null)
        private set
    var continueWatching by mutableStateOf<List<BaseItem>>(emptyList())
        private set
    var onNow by mutableStateOf<List<OnNow>>(emptyList())
        private set
    var cartoons by mutableStateOf<List<OnNow>>(emptyList())
        private set
    /** True once the first load finished (rows may still be empty). */
    var loaded by mutableStateOf(false)
        private set

    val isEmpty get() = continueWatching.isEmpty() && onNow.isEmpty() && cartoons.isEmpty()

    fun firstFeatured(): Featured? =
        client?.let { c -> continueWatching.firstOrNull()?.let { Featured.Media(it, c) } }
            ?: onNow.firstOrNull()?.let { Featured.Live(it) }
            ?: cartoons.firstOrNull()?.let { Featured.Live(it) }

    suspend fun refresh() = coroutineScope {
        val media = async {
            val c = JellyfinClient.current() ?: return@async null to emptyList<BaseItem>()
            val items = runCatching {
                val resume = async { c.resume(12) }
                val next = async { runCatching { c.nextUp(12) }.getOrDefault(emptyList()) }
                (resume.await() + next.await()).distinctBy { it.id }.take(16)
            }.getOrNull()
            c to (items ?: continueWatching)
        }
        val live = async { LiveTvRepository.awaitLoaded() }
        val (c, items) = media.await()
        client = c
        continueWatching = if (c == null) emptyList() else items
        refreshLive(live.await())
        loaded = true
    }

    /** Recomputes On Now from the current guide (programmes roll over while Home is open). */
    fun refreshLive(state: LiveState) {
        if (!state.loaded) return
        onNow = pickOnNow(state)
        cartoons = pickCartoons(state)
    }

    /** Anime and cartoon channels from the free live feeds (anime first). */
    private fun pickCartoons(state: LiveState): List<OnNow> = state.onNow(state.animationChannels().take(24))

    /** Favorites and the last-watched channel first, then channels that have guide data. */
    private fun pickOnNow(state: LiveState): List<OnNow> {
        if (state.channels.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val preferred = listOfNotNull(state.lastChannelId) + state.favorites
        val ordered = preferred.mapNotNull { state.channel(it) } +
            state.channels.filter { ch -> state.nowAndNext(ch.id, now).first != null }
        return ordered.distinctBy { it.id }.take(14).map { ch ->
            val (n, x) = state.nowAndNext(ch.id, now)
            OnNow(ch, n, x)
        }
    }
}

private val CardShape = RoundedCornerShape(12.dp)

/** Full-bleed artwork behind the top of Home; fades out as the page scrolls down. */
@Composable
internal fun BoxScope.HomeBackdrop(featured: Featured?, alpha: Float, livePreview: Boolean) {
    if (featured is Featured.Live) {
        LiveBackdrop(featured.onNow.channel, alpha)
        if (livePreview) LivePreview(featured.onNow.channel, alpha)
        return
    }
    val url = (featured as? Featured.Media)?.let { it.client.backdropUrl(it.item) } ?: return
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(0.72f)
            .height(430.dp)
            .graphicsAlpha(alpha),
    ) {
        JfImage(url, Modifier.fillMaxSize(), plain = true, alignment = Alignment.TopCenter)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to BootColors.Background, 0.45f to BootColors.Background.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to BootColors.Background)
            )
        )
    }
}

/** No artwork for live channels: a soft glow with the channel's logo, so the banner isn't a bare dark box. */
@Composable
private fun BoxScope.LiveBackdrop(channel: Channel, alpha: Float) {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(0.6f)
            .height(400.dp)
            .graphicsAlpha(alpha)
            .background(Brush.radialGradient(listOf(BootColors.Accent.copy(alpha = 0.22f), Color.Transparent), radius = 900f)),
    ) {
        if (channel.logo != null) {
            JfImage(
                channel.logo,
                Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(180.dp).alpha(0.9f),
                contentScale = ContentScale.Fit,
                plain = true,
            )
        }
    }
}

/**
 * The focused channel playing silently behind the banner, like Roku and Google TV. It starts once focus has rested
 * on the card for a moment (scrolling past channels doesn't tune each one) and is covered until the first frame,
 * so a channel that fails to load just keeps showing its logo.
 */
@Composable
private fun BoxScope.LivePreview(channel: Channel, alpha: Float) {
    val context = LocalContext.current
    val player = remember { createLivePlayer(context, handleAudioFocus = false).apply { volume = 0f } }
    var shown by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(channel.id) {
        shown = false
        player.stop()
        delay(1200)
        player.tune(channel)
        player.volume = 0f
    }
    PlayerLifecycle(player)
    // A SurfaceView ignores Compose alpha, so it's faded with a cover in the page colour instead.
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "preview")
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(0.6f)
            .aspectRatio(16f / 9f),
    ) {
        LiveVideoSurface(player, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(BootColors.Background.copy(alpha = 1f - reveal * alpha.coerceIn(0f, 1f))))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to BootColors.Background, 0.35f to BootColors.Background.copy(alpha = 0.5f), 0.7f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to BootColors.Background)))
    }
}

private fun Modifier.graphicsAlpha(alpha: Float) = this.alpha(alpha.coerceIn(0f, 1f))

/** Title, details and description of the featured item. Not focusable: it follows focus in the rows below. */
@Composable
internal fun HomeHero(featured: Featured) {
    // Fixed height so the rows below never jump when the banner switches between items.
    Column(Modifier.fillMaxWidth().height(176.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
        when (featured) {
            is Featured.Media -> {
                val item = featured.item
                Badge(if (item.progress > 0f) "CONTINUE WATCHING" else "UP NEXT", BootColors.Accent)
                val logo = featured.client.logoUrl(item, 500)
                if (logo != null) {
                    JfImage(logo, Modifier.height(64.dp).widthIn(max = 340.dp).fillMaxWidth(), contentScale = ContentScale.Fit, plain = true, alignment = Alignment.CenterStart)
                } else {
                    HeroTitle(item.cardTitle)
                }
                val meta = listOfNotNull(item.episodeLabel?.let { "$it · ${item.name.orEmpty()}" }, metaLine(item).takeIf { it.isNotBlank() }, item.remainingText)
                Text(meta.joinToString("   ·   "), style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.overview?.let { Overview(it) }
            }
            is Featured.Live -> {
                val (ch, now, next) = featured.onNow
                Badge("LIVE", BootColors.Live)
                HeroTitle(now?.title ?: ch.name)
                val time = DateFormat.getTimeInstance(DateFormat.SHORT)
                val meta = listOfNotNull(
                    listOfNotNull(ch.number, ch.name).joinToString(" "),
                    now?.let { "${time.format(Date(it.start))} – ${time.format(Date(it.stop))}" },
                    next?.takeIf { it.title != now?.title }?.let { "Next: ${it.title}" },
                )
                Text(meta.joinToString("   ·   "), style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (now?.desc ?: now?.subtitle)?.let { Overview(it) }
            }
            is Featured.Web -> {
                Badge("WEB APP", Color(0xFF3A5BD9))
                HeroTitle(featured.app.name)
                Text("${featured.app.host}  ·  Opens in the BootTV browser  ·  Hold OK to remove", style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is Featured.App -> {
                Badge("FREE APP", Color(0xFF1F7A4A))
                HeroTitle(featured.app.name)
                Text("Free · Press OK to install from Google Play", style = MaterialTheme.typography.bodyLarge, color = BootColors.TextSecondary)
                Overview(featured.app.blurb)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HeroTitle(text: String) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = BootColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 720.dp))
}

@Composable
private fun Overview(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp))
}

/** "Continue Watching" row: OK opens the detail page (Resume is the first button there). */
@Composable
internal fun ContinueRow(
    items: List<BaseItem>,
    client: JellyfinClient,
    requester: (String) -> FocusRequester,
    onFocused: (key: String, Featured) -> Unit,
    onOpen: (BaseItem) -> Unit,
) {
    FeedRow("Continue Watching") {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            val k = "feed:media:${item.id}"
            MediaCard(
                modifier = Modifier.leftToMenu(i == 0),
                item = item,
                client = client,
                wide = true,
                onClick = { onOpen(item) },
                focusRequester = requester(k),
                onFocused = { onFocused(k, Featured.Media(item, client)) },
            )
        }
    }
}

/** A row of live channels with the current programme and its progress ("On Now", "Cartoons & Anime"). */
@Composable
internal fun OnNowRow(
    channels: List<OnNow>,
    requester: (String) -> FocusRequester,
    onFocused: (key: String, Featured) -> Unit,
    onWatch: (Channel) -> Unit,
    title: String = "On Now",
    keyPrefix: String = "feed:live:",
) {
    FeedRow(title) {
        itemsIndexed(channels, key = { _, it -> it.channel.id }) { i, entry ->
            val k = "$keyPrefix${entry.channel.id}"
            Column(Modifier.width(BootDimens.WideCardWidth)) {
                Card(
                    onClick = { onWatch(entry.channel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .leftToMenu(i == 0)
                        .focusRequester(requester(k))
                        .onFocusChanged { if (it.isFocused) onFocused(k, Featured.Live(entry)) },
                    shape = CardDefaults.shape(CardShape),
                    scale = CardDefaults.scale(focusedScale = 1.08f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape)),
                    colors = CardDefaults.colors(containerColor = BootColors.SurfaceRaised),
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BootColors.SurfaceRaised, BootColors.Surface)))) {
                        ChannelLogo(entry.channel, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.52f))
                        Text(
                            entry.channel.number ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = BootColors.TextSecondary,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                        Text(
                            "LIVE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(BootColors.Live, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        entry.now?.let { p ->
                            val total = (p.stop - p.start).coerceAtLeast(1)
                            val frac = ((System.currentTimeMillis() - p.start).toFloat() / total).coerceIn(0f, 1f)
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp)
                                    .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))
                            ) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(BootColors.Live, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(entry.now?.title ?: entry.channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary)
                Text(entry.channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
            }
        }
    }
}

/** Channel logo, or the channel's name when it has none or it fails to load. */
@Composable
private fun ChannelLogo(channel: Channel, modifier: Modifier) {
    var failed by remember(channel.logo) { mutableStateOf(channel.logo == null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            Text(
                channel.name,
                color = BootColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            coil.compose.AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FeedRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BootColors.TextPrimary)
        // Bleed the row to the right screen edge so it reads as scrollable; keep cards' focus scale unclipped.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            content = content,
        )
    }
}
