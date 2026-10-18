package dev.boottv.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** A free, legal streaming app suggested on Home until it's installed (then it lives in the Apps grid). */
internal data class FreeApp(val pkg: String, val name: String, val blurb: String, val color: Color)

internal val FreeApps = listOf(
    FreeApp("com.tubitv", "Tubi", "Thousands of free movies and shows, with ads.", Color(0xFFE8430A)),
    FreeApp("tv.pluto.android", "Pluto TV", "Hundreds of free live channels and on-demand movies.", Color(0xFF2B2B8C)),
    FreeApp("com.roku.web.trc", "The Roku Channel", "Roku's free movies, shows and live TV, without a Roku.", Color(0xFF6C3C97)),
    FreeApp("com.plexapp.android", "Plex", "Free movies, shows and live channels, plus your own media.", Color(0xFF1F1F1F)),
    FreeApp("com.kanopy", "Kanopy", "Free films and series with your library card.", Color(0xFF1E5E8C)),
    FreeApp("com.hoopladigital.android", "hoopla", "Free movies and TV with your library card.", Color(0xFF1C6B4A)),
)

/** Opens the Play Store page for [app] (falls back to the web listing). */
internal fun openStoreListing(context: Context, app: FreeApp) {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    Toast.makeText(context, "No app store found to install ${app.name}", Toast.LENGTH_LONG).show()
}

private val TileShape = RoundedCornerShape(12.dp)

/** "Free to watch" row: OK opens the store listing. */
@Composable
internal fun FreeAppsRow(
    apps: List<FreeApp>,
    requester: (String) -> FocusRequester,
    onFocused: (key: String, Featured) -> Unit,
    onOpen: (FreeApp) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Free to watch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = BootColors.TextPrimary)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BootDimens.CardSpacing),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
        ) {
            items(apps, key = { it.pkg }) { app ->
                val k = "free:${app.pkg}"
                Card(
                    onClick = { onOpen(app) },
                    modifier = Modifier
                        .width(BootDimens.WideCardWidth * 0.75f)
                        .aspectRatio(16f / 9f)
                        .focusRequester(requester(k))
                        .onFocusChanged { if (it.isFocused) onFocused(k, Featured.App(app)) },
                    shape = CardDefaults.shape(TileShape),
                    scale = CardDefaults.scale(focusedScale = 1.08f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = TileShape)),
                    colors = CardDefaults.colors(containerColor = app.color),
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(app.color, app.color.copy(alpha = 0.7f))))) {
                        Text(
                            app.name,
                            color = Color.White,
                            fontSize = 19.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
                        )
                        Text(
                            "FREE · GET",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                .background(Color(0x55000000), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
