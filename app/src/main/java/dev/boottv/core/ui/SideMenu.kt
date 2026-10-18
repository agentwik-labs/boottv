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
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
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

data class MenuItem(val route: String, val label: String, val icon: ImageVector)

val BootMenuItems = listOf(
    MenuItem(Routes.HOME, "Home", Icons.Filled.Home),
    MenuItem(Routes.SEARCH, "Search", Icons.Filled.Search),
    MenuItem(Routes.LIVE, "Live TV", Icons.Filled.LiveTv),
    MenuItem(Routes.MEDIA, "My Media", Icons.Filled.VideoLibrary),
    MenuItem(Routes.FREE, "Free Movies", Icons.Filled.Theaters),
    MenuItem(Routes.BROWSER, "Browser", Icons.Filled.Public),
    MenuItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

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
            .background(
                if (expanded) {
                    Brush.horizontalGradient(listOf(Color(0xF2070710), Color(0xE6070710)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFF0E0E1A), Color(0xFF0E0E1A)))
                },
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                enter = { requesters[BootMenuItems[currentIndex].route] ?: FocusRequester.Default }
            }
            .focusGroup(),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = BootDimens.PagePaddingV),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.height(56.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    if (expanded) "BootTV" else "B",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = BootColors.Accent,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(24.dp))
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
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) BootColors.AccentSoft else Color.Transparent,
            contentColor = if (selected) BootColors.TextPrimary else BootColors.TextSecondary,
            focusedContainerColor = BootColors.Accent,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(24.dp))
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
