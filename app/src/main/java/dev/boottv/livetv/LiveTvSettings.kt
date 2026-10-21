package dev.boottv.livetv

import dev.boottv.settings.BootPrefs
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.boottv.jellyfin.BaseItem
import dev.boottv.jellyfin.JellyfinClient
import kotlinx.coroutines.launch

/** Live TV section embedded in the Settings screen (plain Column; the host provides scrolling). */
@Composable
fun LiveTvSettingsContent() {
    LaunchedEffect(Unit) { LiveTvRepository.init() }
    val state by LiveTvRepository.state.collectAsState()
    val lastRefresh = state.sources.filter { it.enabled }.maxOfOrNull { it.lastRefresh } ?: 0L

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live TV", style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
        Text(
            "${state.channels.size} channels from ${state.sources.count { it.enabled }} of ${state.sources.size} sources" +
                " · guide updated ${relativeAgo(lastRefresh)}" +
                if (state.refreshing.isNotEmpty()) " · refreshing…" else "",
            style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { LiveTvRepository.refreshAll() }, enabled = state.sources.any { it.enabled }) {
                Text(if (state.refreshing.isNotEmpty()) "Refreshing…" else "Refresh all now")
            }
            Button(onClick = { LiveTvRepository.setAutoSkip(!state.autoSkip) }) {
                Text("Auto-skip channels that fail: " + if (state.autoSkip) "On" else "Off")
            }
        }

        ProviderSettings()

        MyChannelsSettings()

        SectionTitle("Your sources")
        if (state.sources.isEmpty()) {
            Text("No sources yet. Add a suggested source or your own playlist below.", color = BootColors.TextSecondary)
        }
        state.sources.forEach { s -> SourceRow(s, s.id in state.refreshing) }

        SectionTitle("Suggested free sources")
        Text(
            "Free, ad-supported channels from official streaming services (playlists maintained by i.mjh.nz).",
            style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
        )
        SuggestedSources.forEach { sug ->
            val added = state.sources.any { it.url.equals(sug.m3u, ignoreCase = true) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sug.name, style = MaterialTheme.typography.titleSmall, color = BootColors.TextPrimary)
                    Text(sug.m3u, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1)
                }
                Button(
                    onClick = { if (!added) LiveTvRepository.addSource(sug.name, SourceKind.M3U, sug.m3u, sug.epg) },
                    enabled = !added,
                ) { Text(if (added) "Added" else "Add") }
            }
        }

        SectionTitle("Add a custom playlist (M3U)")
        var name by remember { mutableStateOf("") }
        var m3u by remember { mutableStateOf("") }
        var epg by remember { mutableStateOf("") }
        TvTextField("Name (optional)", name, { name = it }, "My channels", KeyboardType.Text)
        TvTextField("Playlist URL (M3U / M3U8)", m3u, { m3u = it }, "https://example.com/playlist.m3u8")
        TvTextField("Guide URL (XMLTV, optional, .xml or .xml.gz)", epg, { epg = it }, "https://example.com/guide.xml.gz")
        val m3uOk = m3u.trim().let { it.startsWith("http://") || it.startsWith("https://") }
        Button(
            onClick = {
                LiveTvRepository.addSource(name, SourceKind.M3U, m3u, epg)
                name = ""; m3u = ""; epg = ""
            },
            enabled = m3uOk,
        ) { Text("Add playlist") }

        SectionTitle("Add an HDHomeRun antenna tuner")
        Text(
            "Enter the tuner's IP address (find it in your router or the HDHomeRun app). Channels come from its lineup.",
            style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
        )
        var ip by remember { mutableStateOf("") }
        var hdName by remember { mutableStateOf("") }
        var hdEpg by remember { mutableStateOf("") }
        TvTextField("Tuner IP address", ip, { ip = it }, "192.168.1.50", KeyboardType.Uri)
        TvTextField("Name (optional)", hdName, { hdName = it }, "Antenna", KeyboardType.Text)
        TvTextField("Guide URL (XMLTV, optional)", hdEpg, { hdEpg = it }, "https://example.com/guide.xml")
        Button(
            onClick = {
                LiveTvRepository.addSource(hdName.ifBlank { "Antenna (${ip.trim()})" }, SourceKind.HDHOMERUN, ip, hdEpg)
                ip = ""; hdName = ""; hdEpg = ""
            },
            enabled = ip.isNotBlank() && !ip.contains(' '),
        ) { Text("Add tuner") }
    }
}

/** Spectrum: one press away from Home, the menu and the remote. */
@Composable
private fun ProviderSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val pkg = remember(tick) { TvProvider.installedPackage(context) }
    val settings by remember { BootPrefs.flow(context) }.collectAsState(initial = null)
    val pinned = pkg != null && settings?.favorites?.contains(pkg) == true
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } } // picks up an install from Google Play

    SectionTitle("Your TV provider: ${TvProvider.NAME}")
    Text(
        if (pkg == null) "Get the free Spectrum TV app to watch your Spectrum channels here, then sign in with your Spectrum username."
        else "Your Spectrum channels play in the Spectrum TV app. Open it from here, from Home, or give it a remote button (Settings › Remote buttons). BootTV's Live TV guide stays BootTV's own free channels.",
        style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { TvProvider.open(context) }) { Text(if (pkg == null) "Get Spectrum TV" else "Open Spectrum TV") }
        if (pkg != null) {
            Button(onClick = { scope.launch { BootPrefs.setFavorite(context, pkg, !pinned) } }) {
                Text(if (pinned) "On Home ✓" else "Put on Home")
            }
        }
    }
}

/** Make channels out of your own movies and shows (My Media, or videos on the TV / a USB drive). */
@Composable
private fun MyChannelsSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine by MyChannels.channels.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var libraries by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var signedIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val client = JellyfinClient.current()
        signedIn = client != null
        libraries = runCatching { client?.views().orEmpty() }.getOrDefault(emptyList())
            .filter { it.collectionType in setOf("movies", "tvshows", "homevideos", "musicvideos", null) }
    }
    fun create(content: MyContent) {
        if (busy) return
        busy = true; status = "Making ${content.label}…"
        scope.launch {
            status = runCatching { MyChannels.create(context, content) }
                .fold({ "Added channel ${it.number}: ${it.name} (${it.items.size} titles). It's at the top of the guide." }, { it.message ?: "Couldn't make that channel." })
            busy = false
        }
    }
    val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    val askVideos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) create(MyContent(MyContentKind.DEVICE_VIDEOS, "My Videos"))
        else status = "BootTV needs permission to see the videos on this TV."
    }

    SectionTitle("My channels")
    Text(
        "Turn your own movies and shows into channels that play around the clock, like regular TV. They appear at the " +
            "top of the guide and on Home. Sources: your My Media library, or videos copied to this TV or a USB drive.",
        style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
    )
    mine.forEach { ch ->
        val now = MyChannels.nowPlaying(ch)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${ch.number}  ${ch.name}", style = MaterialTheme.typography.titleSmall, color = BootColors.TextPrimary)
                Text(
                    "${ch.items.size} titles" + (now?.let { " · on now: ${it.item.title}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = {
                scope.launch {
                    status = runCatching { MyChannels.refresh(context, ch.id) }.fold({ "${ch.name} is up to date." }, { it.message ?: "Couldn't update." })
                }
            }) { Text("Update") }
            Button(onClick = { scope.launch { MyChannels.remove(ch.id); status = "Removed ${ch.name}." } }) { Text("Remove") }
        }
    }
    Text("Make a channel from:", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { create(MyContent(MyContentKind.JELLYFIN_MOVIES, "My Movies")) }, enabled = signedIn && !busy) { Text("All my movies") }
        Button(onClick = { create(MyContent(MyContentKind.JELLYFIN_EPISODES, "My Shows")) }, enabled = signedIn && !busy) { Text("All my TV shows") }
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, videoPermission) == PackageManager.PERMISSION_GRANTED) {
                    create(MyContent(MyContentKind.DEVICE_VIDEOS, "My Videos"))
                } else askVideos.launch(videoPermission)
            },
            enabled = !busy,
        ) { Text("Videos on this TV / USB") }
    }
    if (libraries.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            libraries.take(5).forEach { lib ->
                Button(onClick = { create(MyContent(MyContentKind.JELLYFIN_LIBRARY, lib.name ?: "Library", lib.id)) }, enabled = !busy) {
                    Text("Library: ${lib.name}")
                }
            }
        }
    }
    if (!signedIn) {
        Text("Sign in under My Media to make channels from your library.", style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary)
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextPrimary) }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
}

@Composable
private fun SourceRow(s: LiveSource, refreshing: Boolean) {
    var confirmRemove by remember(s.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(BootColors.Surface, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.name + if (!s.enabled) "  (off)" else "",
                style = MaterialTheme.typography.titleSmall,
                color = if (s.enabled) BootColors.TextPrimary else BootColors.TextSecondary,
            )
            Text(
                (if (s.kind == SourceKind.HDHOMERUN) "HDHomeRun · " else "") + s.url,
                style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    refreshing -> "Refreshing…"
                    s.lastRefresh == 0L && s.lastError == null -> "Not loaded yet"
                    else -> "${s.channelCount} channels · ${s.programmeCount} guide entries · updated ${relativeAgo(s.lastRefresh)}"
                },
                style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary,
            )
            s.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = BootColors.Live, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { LiveTvRepository.setSourceEnabled(s.id, !s.enabled) }) { Text(if (s.enabled) "Turn off" else "Turn on") }
            Button(onClick = { LiveTvRepository.refresh(s.id) }, enabled = !refreshing && s.enabled) { Text("Refresh") }
            Button(
                onClick = { if (confirmRemove) LiveTvRepository.removeSource(s.id) else confirmRemove = true },
                modifier = Modifier.onFocusChanged { if (!it.isFocused && !it.hasFocus) confirmRemove = false },
            ) { Text(if (confirmRemove) "Press again to remove" else "Remove") }
        }
    }
}

/**
 * D-pad friendly text field: shows as a focusable surface; OK starts editing (opens the keyboard),
 * Done / Back / Up / Down finishes. Avoids the keyboard popping up while scrolling past fields.
 */
@Composable
internal fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = BootColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            fun finish() {
                keyboard?.hide(); returnFocus = true; editing = false
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = BootColors.TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus) editing = false
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown &&
                            (ev.key == Key.Back || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown)
                        ) { finish(); true } else false
                    }
                    .border(2.dp, BootColors.Accent, RoundedCornerShape(8.dp))
                    .background(BootColors.SurfaceRaised, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            LaunchedEffect(Unit) {
                runCatching { fieldFocus.requestFocus() }
                keyboard?.show()
            }
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(surfaceFocus),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = BootColors.Surface,
                    contentColor = BootColors.TextPrimary,
                    focusedContainerColor = BootColors.SurfaceRaised,
                    focusedContentColor = BootColors.TextPrimary,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, BootColors.Accent)),
                ),
            ) {
                Text(
                    value.ifEmpty { placeholder },
                    color = if (value.isEmpty()) BootColors.TextSecondary.copy(alpha = 0.6f) else BootColors.TextPrimary,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            LaunchedEffect(returnFocus) {
                if (returnFocus) { runCatching { surfaceFocus.requestFocus() }; returnFocus = false }
            }
        }
    }
}
