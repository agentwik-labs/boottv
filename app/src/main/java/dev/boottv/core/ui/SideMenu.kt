package dev.boottv.core.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.core.Routes

/** A side menu entry; [sprite] is the Pokémon (Pokédex number) that stands in for [icon] in the Pocket Monsters theme. */
data class MenuItem(val route: String, val label: String, val icon: ImageVector, val sprite: Int)

val BootMenuItems = listOf(
    MenuItem(Routes.HOME, "Home", Icons.Filled.Home, sprite = 25),            // Pikachu, your partner
    MenuItem(Routes.SEARCH, "Search", Icons.Filled.Search, sprite = 164),     // Noctowl, sharp-eyed
    MenuItem(Routes.LIVE, "Live TV", Icons.Filled.LiveTv, sprite = 479),      // Rotom lives in TVs
    MenuItem(Routes.MEDIA, "My Media", Icons.Filled.VideoLibrary, sprite = 235), // Smeargle, your own creations
    MenuItem(Routes.FREE, "Free Movies", Icons.Filled.Theaters, sprite = 52), // Meowth, free stuff
    MenuItem(Routes.TOONS, "Cartoons", Icons.Filled.Animation, sprite = 39),  // Jigglypuff
    MenuItem(Routes.BROWSER, "Browser", Icons.Filled.Public, sprite = 137),   // Porygon, made of data
    MenuItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings, sprite = 81), // Magnemite, nuts and bolts
)

/** The side menu, so content rows can send Left there explicitly (null where there is no menu). */
val LocalSideMenuFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * For the first card of a row: Left goes to the side menu. Without it, Android's focus search can pick a card in
 * another row that happens to sit a few pixels further left.
 */
fun Modifier.leftToMenu(first: Boolean = true): Modifier = composed {
    val menu = LocalSideMenuFocus.current
    if (!first || menu == null) return@composed this
    // Remembered so Right from the menu comes back to this card, not whichever row lines up with the menu item.
    val me = remember { FocusRequester() }
    DisposableEffect(me) { onDispose { if (MenuReturn.target === me) MenuReturn.target = null } }
    this.focusProperties { left = menu }
        .focusRequester(me)
        .onFocusChanged { if (it.isFocused) MenuReturn.target = me }
}

/** The row-start card the side menu was entered from. */
private object MenuReturn {
    var target: FocusRequester? = null
}

/**
 * Roku-style left rail: icons only while collapsed, expands with labels when it holds focus.
 * Focus entering the rail lands on the item for [currentRoute].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SideMenu(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        if (expanded) BootDimens.SideMenuExpanded else BootDimens.SideMenuCollapsed,
        label = "sideMenuWidth",
    )
    val requesters = remember { BootMenuItems.associate { it.route to FocusRequester() } }
    val currentIndex = BootMenuItems.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            // Collapsed it's just a column of quiet icons on the page; open, a dark scrim keeps the labels legible
            // and fades out past the menu's edge instead of ending in a hard line.
            .drawBehind {
                if (expanded) {
                    val fade = 320.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color(0xF5000000),
                            size.width / (size.width + fade) to Color(0xD9000000),
                            1f to Color.Transparent,
                            endX = size.width + fade,
                        ),
                        size = Size(size.width + fade, size.height),
                    )
                }
            }
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                enter = { requesters[BootMenuItems[currentIndex].route] ?: FocusRequester.Default }
                exit = { dir -> MenuReturn.target?.takeIf { dir == FocusDirection.Right } ?: FocusRequester.Default }
            }
            .focusGroup(),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            // Eight entries have to fit a 540 dp tall TV screen.
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.height(48.dp).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    BootColors.theme.playful && expanded -> PocketWordmark(fontSize = 22.sp, ball = 24.dp)
                    BootColors.theme.playful -> BallEmblem(24.dp)
                    expanded -> Text("BootTV", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BootColors.TextPrimary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            BootMenuItems.forEach { item ->
                SideMenuEntry(
                    item = item,
                    selected = item.route == currentRoute,
                    expanded = expanded,
                    modifier = Modifier.focusRequester(requesters.getValue(item.route)),
                    onClick = { onSelect(item.route) },
                )
            }
        }
    }
}

@Composable
private fun SideMenuEntry(
    item: MenuItem,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected && expanded) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            contentColor = if (selected) BootColors.TextPrimary else BootColors.TextSecondary.copy(alpha = 0.8f),
            focusedContainerColor = BootColors.Focused,
            focusedContentColor = BootColors.OnFocused,
        ),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (Sprites.on) {
                // The sprite's own canvas has a lot of empty space: draw it larger than its slot, centred.
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    PokeSprite(item.sprite, if (focused) 72.dp else 64.dp, animated = focused)
                }
            } else {
                Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp))
            }
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
