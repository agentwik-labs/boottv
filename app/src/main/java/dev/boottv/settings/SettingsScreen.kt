package dev.boottv.settings

import dev.boottv.core.ui.ThemePresets
import dev.boottv.core.ui.Sprites
import dev.boottv.core.ui.BootDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.AdminPanelSettings
import dev.boottv.owner.DeviceControl
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.boottv.livetv.MyChannels
import dev.boottv.remote.ButtonAction
import dev.boottv.remote.RemoteButtons
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.BuildConfig
import dev.boottv.core.ui.AccentPresets
import dev.boottv.core.ui.BootButton
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.BootDimens
import dev.boottv.core.ui.PinPadDialog
import dev.boottv.core.ui.PinSetupDialog
import dev.boottv.jellyfin.JellyfinSettingsSection
import dev.boottv.launcher.AppEntry
import dev.boottv.launcher.AppRepository
import dev.boottv.livetv.LiveTvSettingsSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

private enum class Category(val title: String, val icon: ImageVector) {
    Network("Network", Icons.Filled.Wifi),
    DisplaySound("Display & sound", Icons.Filled.DisplaySettings),
    HomeScreen("Home screen", Icons.Filled.ViewModule),
    Parental("Parental controls", Icons.Filled.Lock),
    Screensaver("Screensaver", Icons.Filled.Nightlight),
    MyMedia("My Media", Icons.Filled.VideoLibrary),
    LiveTv("Live TV", Icons.Filled.LiveTv),
    Remote("Remote buttons", Icons.Filled.SettingsRemote),
    Device("Device control", Icons.Filled.AdminPanelSettings),
    DefaultHome("Default home app", Icons.Filled.Home),
    System("System", Icons.Filled.Settings),
    About("About", Icons.Filled.Info),
}

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val settings by remember { BootPrefs.flow(context) }.collectAsState(initial = null)
    var unlocked by rememberSaveable { mutableStateOf(false) }

    val s = settings ?: run {
        Box(Modifier.fillMaxSize().background(BootColors.Background))
        return
    }

    if (s.pinForSettings && s.hasPin && !unlocked) {
        Box(Modifier.fillMaxSize().background(BootColors.Background), contentAlignment = Alignment.Center) {
            Text("Settings are locked", color = BootColors.TextSecondary)
        }
        PinPadDialog(
            title = "Enter PIN",
            message = "A PIN is required to open Settings.",
            onDismiss = { nav.popBackStack() },
            onComplete = { pin -> BootPrefs.checkPin(s, pin).also { if (it) unlocked = true } },
        )
        return
    }

    SettingsContent(s)
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsContent(s: BootSettings) {
    var selected by rememberSaveable { mutableStateOf(Category.Network) }
    val requesters = remember { Category.entries.associateWith { FocusRequester() } }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { requesters.getValue(selected).requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(BootColors.Background)
            .padding(vertical = BootDimens.PagePaddingV),
    ) {
        // Categories (left pane). Focusing a category shows its details on the right, like Roku.
        Column(
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(start = BootDimens.PagePaddingH / 2, end = 16.dp)
                .focusProperties { enter = { requesters.getValue(selected) } }
                .focusGroup()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = BootColors.TextPrimary,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
            Category.entries.forEach { cat ->
                BootButton(
                    text = cat.title,
                    icon = cat.icon,
                    selected = cat == selected,
                    onClick = { selected = cat },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requesters.getValue(cat))
                        .onFocusChanged { if (it.isFocused) selected = cat },
                )
            }
        }

        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(BootColors.SurfaceRaised),
        )

        // Details (right pane).
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 32.dp, end = BootDimens.PagePaddingH)
                .focusRestorer()
                .focusGroup(),
        ) {
            when (selected) {
                Category.MyMedia -> ExternalSection("My Media") { JellyfinSettingsSection() }
                Category.LiveTv -> ExternalSection("Live TV") { LiveTvSettingsSection() }
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(selected.title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    when (selected) {
                        Category.Network -> NetworkSection()
                        Category.DisplaySound -> DisplaySoundSection()
                        Category.HomeScreen -> HomeScreenSection(s)
                        Category.Parental -> ParentalSection(s)
                        Category.Screensaver -> ScreensaverSection()
                        Category.DefaultHome -> DefaultHomeSection()
                        Category.System -> SystemSection()
                        Category.About -> AboutSection()
                        Category.Remote -> RemoteButtonsSection()
                        Category.Device -> DeviceControlSection()
                        else -> Unit
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ExternalSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = BootColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        // These sections are plain Columns, so the pane scrolls them. (They must not use a
        // vertically-scrolling lazy list at their root, which can't be nested in verticalScroll.)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

/** Starts the first of [actions] that resolves on this device. Returns false if none did. */
private fun openSystemSettings(context: Context, vararg actions: String): Boolean {
    for (action in actions) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, "That setting isn't available on this device", Toast.LENGTH_SHORT).show()
    return false
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
}

@Composable
private fun SubHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
}

@Composable
private fun SettingButton(text: String, trailing: String? = null, onClick: () -> Unit) {
    BootButton(text = text, trailing = trailing, onClick = onClick, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BootColors.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = BootColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, color = BootColors.TextPrimary)
    }
}

private fun ipAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${iface.name})" } }
}.getOrDefault(emptyList())

// ---------------------------------------------------------------- sections

/** BootTV as the stick's device owner: permanent Home, stock home screen hidden, lockdown, update schedule. */
@Composable
private fun DeviceControlSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // re-read the device's state after each change
    var confirmRelease by remember { mutableStateOf(false) }
    val owner = remember(tick) { DeviceControl.isOwner(context) }

    if (!owner) {
        Note(
            "BootTV can run the whole TV, not just its home screen: Home always opens BootTV, Google's home screen and " +
                "ad services stay off even after updates, and you decide when system updates install. Netflix, YouTube " +
                "and other apps keep working."
        )
        SubHeader("How to turn it on")
        Note(
            "1. Reset the stick to factory settings (Settings › System › About › Reset).\n" +
                "2. During setup, skip signing in to Google (choose \"Basic TV\" or \"Skip\").\n" +
                "3. Install BootTV, then on the Mac run:  tools/owner.sh\n" +
                "4. Sign in to Google Play afterwards if you want it."
        )
        SubHeader("Status")
        InfoRow("Device owner", "Not set up")
        return
    }

    InfoRow("Device owner", "BootTV controls this TV")
    if (remember(tick) { DeviceControl.needsRepair(context) }) {
        Note("⚠ Android has switched off BootTV's control (this can happen after a restore). On the Mac, run tools/owner.sh to repair it.")
    }
    Note("Home always opens BootTV, even if another launcher is installed or an update brings one back.")

    val hidden = remember(tick) { DeviceControl.hiddenPackages(context) }
    SubHeader("Google's home screen and ad services")
    SettingButton("Hidden", trailing = if (hidden.isNotEmpty()) "On (${hidden.size})" else "Off") {
        DeviceControl.setStockHidden(context, hidden.isEmpty()); tick++
    }
    Note("Hidden, not deleted: switch this off to bring them back.")

    SubHeader("Lockdown")
    val locked = remember(tick) { DeviceControl.isLocked(context) }
    SettingButton("Block factory reset, safe mode and new users", trailing = if (locked) "On" else "Off") {
        DeviceControl.setLocked(context, !locked); tick++
    }

    SubHeader("System updates")
    val mode = remember(tick) { DeviceControl.updateMode(context) }
    DeviceControl.UpdateMode.entries.forEach { m ->
        SettingButton(m.label, trailing = if (m == mode) "✓" else null) { DeviceControl.setUpdateMode(context, m); tick++ }
    }
    Note("Postponing holds updates for up to 30 days; Android then installs them anyway, so the TV stays secure.")

    SubHeader("Give control back")
    SettingButton("Release device control") { confirmRelease = true }
    if (confirmRelease) {
        BootDialog(onDismiss = { confirmRelease = false }, title = "Release device control?",
            subtitle = "Google's home screen and services come back, the lockdown and update schedule are removed, and BootTV becomes an ordinary app. You can set it up again later.") {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            BootButton("Cancel", modifier = Modifier.fillMaxWidth().focusRequester(fr), onClick = { confirmRelease = false })
            BootButton("Release", modifier = Modifier.fillMaxWidth(), onClick = {
                runCatching { DeviceControl.release(context) }
                confirmRelease = false; tick++
            })
        }
    }
}

/** Hot buttons: assign spare remote buttons to BootTV sections and your own channels. */
@Composable
private fun RemoteButtonsSection() {
    val context = LocalContext.current
    val mappings by RemoteButtons.mappings.collectAsState()
    val learning by RemoteButtons.learning.collectAsState()
    val learned by RemoteButtons.learned.collectAsState()
    val myChannels by MyChannels.channels.collectAsState()
    var everywhere by remember { mutableStateOf(RemoteButtons.serviceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        everywhere = RemoteButtons.serviceEnabled(context)
        onPauseOrDispose { RemoteButtons.learning.value = false }
    }
    val actions = RemoteButtons.fixedActions +
        myChannels.map { ButtonAction("Channel ${it.number}: ${it.name}", "live/play/${MyChannels.ID_PREFIX}${it.id}") }

    Note("Built in: Channel up/down jumps to the last channel you watched. The Guide and TV buttons open the guide (or Spectrum) " +
        "once \"Use them in any app\" below is on, because Android keeps those two buttons for itself otherwise.")
    SubHeader("Your buttons")
    if (mappings.isEmpty()) Note("None yet. Pick a button you don't use, like an app shortcut button, and choose what it opens.")
    mappings.forEach { (code, action) ->
        SettingButton("${RemoteButtons.keyName(code)}  →  ${action.label}", trailing = "Remove") { RemoteButtons.unassign(context, code) }
    }
    val key = learned
    // The step's first button takes focus as the steps change, so it doesn't fall back to the category list.
    val stepFocus = remember { FocusRequester() }
    var opened by remember { mutableStateOf(false) } // not when merely scrolling past this section
    LaunchedEffect(key, learning) {
        if (!opened) { opened = true; return@LaunchedEffect }
        repeat(10) { delay(60); if (runCatching { stepFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    val first = Modifier.fillMaxWidth().focusRequester(stepFocus)
    when {
        key != null -> {
            SubHeader("What should the ${RemoteButtons.keyName(key)} open?")
            actions.forEachIndexed { i, a ->
                BootButton(a.label, modifier = if (i == 0) first else Modifier.fillMaxWidth(), onClick = {
                    RemoteButtons.assign(context, key, a); RemoteButtons.learned.value = null
                })
            }
            SettingButton("Cancel") { RemoteButtons.learned.value = null }
        }
        learning -> {
            Note("Press the remote button you want to use now…")
            BootButton("Cancel", modifier = first, onClick = { RemoteButtons.learning.value = false })
        }
        else -> BootButton("Add a button", modifier = first, onClick = { RemoteButtons.learned.value = null; RemoteButtons.learning.value = true })
    }

    SubHeader("BootTV menu over any app")
    Note("With the setting below on, hold Back for a second in any app (Spectrum, Netflix, YouTube…) and BootTV's menu " +
        "opens on top of it. A normal press of Back still works as Back.")

    SubHeader("Use them in any app")
    Note(
        if (everywhere) "On: your buttons work everywhere, even while another app is open."
        else "Off: your buttons work while BootTV is open. To use them in any app, turn on \"BootTV remote buttons\" " +
            "in Accessibility settings (Android asks once; BootTV only listens for the buttons you chose)."
    )
    if (!everywhere) SettingButton("Open Accessibility settings") { openSystemSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
}

@Composable
private fun NetworkSection() {
    val context = LocalContext.current
    val ips = remember { ipAddresses() }
    InfoRow("IP address", ips.firstOrNull() ?: "Not connected")
    ips.drop(1).forEach { InfoRow("", it) }
    SettingButton("Wi-Fi & network") {
        openSystemSettings(context, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
    }
    SettingButton("Network & Internet (all)") {
        openSystemSettings(context, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
    }
    SettingButton("Bluetooth & remotes") {
        openSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)
    }
}

@Composable
private fun DisplaySoundSection() {
    val context = LocalContext.current
    SettingButton("Display & resolution") {
        openSystemSettings(context, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)
    }
    SettingButton("Sound & audio output") {
        openSystemSettings(context, Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS)
    }
    SettingButton("Captions") {
        openSystemSettings(context, Settings.ACTION_CAPTIONING_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}

@Composable
private fun ScreensaverSection() {
    val context = LocalContext.current
    Note("BootTV includes its own ad-free screensaver. Choose \"BootTV Screensaver\" in the system screensaver settings and set when it starts.")
    SettingButton("Screensaver settings") {
        openSystemSettings(context, Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)
    }
}

@Composable
private fun HomeScreenSection(s: BootSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps = AppRepository.loadApps(context) }

    SubHeader("Look")
    SettingButton("Theme", trailing = ThemePresets.byId(s.themeId).name) {
        val all = ThemePresets.all
        val next = all[(all.indexOf(ThemePresets.byId(s.themeId)) + 1) % all.size]
        scope.launch { BootPrefs.setTheme(context, next.id) }
    }
    if (ThemePresets.byId(s.themeId).playful) {
        SettingButton("Pokémon sprites", trailing = if (s.sprites) "On" else "Off") {
            scope.launch { BootPrefs.setSprites(context, !s.sprites) }
        }
        if (s.sprites) {
            SettingButton("Partner Pokémon", trailing = Sprites.partnerName(s.partner)) {
                val ids = Sprites.partners.map { it.first }
                scope.launch { BootPrefs.setPartner(context, ids[(ids.indexOf(s.partner) + 1).mod(ids.size)]) }
            }
        }
        Note(
            "Sprites are © Nintendo / Creatures / GAME FREAK. BootTV doesn't include them: the TV downloads them for your " +
                "own use from the PokeAPI sprite collection. Pocket Monsters and Gengar use their own colours; accent colours apply to Minimal."
        )
    } else {
    SubHeader("Accent color")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        AccentPresets.all.forEach { preset ->
            val chosen = preset.id == s.accentId
            Surface(
                onClick = { scope.launch { BootPrefs.setAccent(context, preset.id) } },
                modifier = Modifier.size(56.dp),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                border = ClickableSurfaceDefaults.border(
                    border = if (chosen) Border(BorderStroke(2.dp, BootColors.TextSecondary), shape = CircleShape) else Border.None,
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = preset.color,
                    focusedContainerColor = preset.color,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (chosen) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White)
                }
            }
        }
    }
    Note("Current: ${AccentPresets.byId(s.accentId).name}")
    }

    SubHeader("Layout")
    SettingButton("Apps per row", trailing = "${s.gridColumns}") {
        scope.launch { BootPrefs.setGridColumns(context, if (s.gridColumns == 5) 4 else 5) }
    }
    SettingButton("Favorites row", trailing = if (s.showFavorites) "On" else "Off") {
        scope.launch { BootPrefs.setShowFavorites(context, !s.showFavorites) }
    }
    SettingButton("Live preview on Home", trailing = if (s.livePreview) "On" else "Off") {
        scope.launch { BootPrefs.setLivePreview(context, !s.livePreview) }
    }
    SettingButton("Reset app order (alphabetical)") {
        scope.launch { BootPrefs.setOrder(context, emptyList()) }
        Toast.makeText(context, "App order reset", Toast.LENGTH_SHORT).show()
    }

    SubHeader("Hidden apps")
    val hidden = apps.filter { it.packageName in s.hidden }
    if (hidden.isEmpty()) {
        Note("No hidden apps. Hide an app from the home screen with its options (hold OK or press ☰).")
    } else {
        Note("Select an app to show it on the home screen again.")
        hidden.forEach { app ->
            SettingButton(app.label, trailing = "Unhide") {
                scope.launch { BootPrefs.setHidden(context, app.packageName, false) }
            }
        }
    }
}

private sealed interface ParentalDialog {
    data object Setup : ParentalDialog
    data class Verify(val title: String, val onOk: () -> Unit) : ParentalDialog
}

@Composable
private fun ParentalSection(s: BootSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<ParentalDialog?>(null) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps = AppRepository.loadApps(context) }

    Note("A 4-digit PIN protects locked apps and, optionally, Settings. Lock an app from its options on the home screen (hold OK or press ☰).")
    if (!s.hasPin) {
        SettingButton("Create PIN") { dialog = ParentalDialog.Setup }
    } else {
        SettingButton("Change PIN") {
            dialog = ParentalDialog.Verify("Enter current PIN") { dialog = ParentalDialog.Setup }
        }
        SettingButton("Require PIN for Settings", trailing = if (s.pinForSettings) "On" else "Off") {
            if (s.pinForSettings) {
                dialog = ParentalDialog.Verify("Enter PIN") {
                    dialog = null
                    scope.launch { BootPrefs.setPinForSettings(context, false) }
                }
            } else {
                scope.launch { BootPrefs.setPinForSettings(context, true) }
            }
        }
        SettingButton("Remove PIN and all locks") {
            dialog = ParentalDialog.Verify("Enter PIN") {
                dialog = null
                scope.launch { BootPrefs.setPin(context, null) }
            }
        }

        SubHeader("Locked apps")
        val locked = apps.filter { it.packageName in s.locked }
        if (locked.isEmpty()) {
            Note("No apps are locked.")
        } else {
            locked.forEach { app ->
                SettingButton(app.label, trailing = "Unlock") {
                    dialog = ParentalDialog.Verify("Unlock ${app.label}") {
                        dialog = null
                        scope.launch { BootPrefs.setLocked(context, app.packageName, false) }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        ParentalDialog.Setup -> PinSetupDialog(
            onDismiss = { dialog = null },
            onPinSet = { pin ->
                dialog = null
                scope.launch { BootPrefs.setPin(context, pin) }
                Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
            },
        )
        is ParentalDialog.Verify -> PinPadDialog(
            title = d.title,
            message = null,
            onDismiss = { dialog = null },
            onComplete = { pin -> BootPrefs.checkPin(s, pin).also { if (it) d.onOk() } },
        )
        null -> Unit
    }
}

@Composable
private fun DefaultHomeSection() {
    val context = LocalContext.current
    val isDefault = remember {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }
    val homeSettingsAvailable = remember {
        Intent(Settings.ACTION_HOME_SETTINGS).resolveActivity(context.packageManager) != null
    }
    InfoRow("BootTV is the default home", if (isDefault) "Yes" else "No")
    if (homeSettingsAvailable) {
        SettingButton("Choose default home app") {
            openSystemSettings(context, Settings.ACTION_HOME_SETTINGS)
        }
    }
    Note(
        "Google TV devices (like the Onn 4K / Google TV Streamer) don't offer a home-app picker. " +
            "To make BootTV the home screen, run the debloat script from a computer on the same network:",
    )
    Note("1. On the TV: Settings › System › About › tap \"Android TV OS build\" 7 times, then enable USB/Wireless debugging in Developer options.")
    Note("2. On your computer: tools/connect.sh <TV-IP>, then tools/install.sh")
    Note("3. Run tools/debloat.sh --dry-run to preview, then tools/debloat.sh --allow-launcher-swap to disable ads and the stock launcher so Home opens BootTV.")
    Note("Everything is reversible with tools/debloat.sh --restore.")
}

@Composable
private fun SystemSection() {
    val context = LocalContext.current
    SettingButton("All system settings") { openSystemSettings(context, Settings.ACTION_SETTINGS) }
    SettingButton("Apps") {
        openSystemSettings(context, Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS)
    }
    SettingButton("Date & time") { openSystemSettings(context, Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SETTINGS) }
    SettingButton("Language") { openSystemSettings(context, Settings.ACTION_LOCALE_SETTINGS, Settings.ACTION_SETTINGS) }
    SettingButton("Keyboard & input") { openSystemSettings(context, Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_SETTINGS) }
    SettingButton("Storage") { openSystemSettings(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS) }
    SettingButton("Accessibility") { openSystemSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS) }
    SettingButton("Developer options") {
        openSystemSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS)
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val ips = remember { ipAddresses() }
    InfoRow("BootTV version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    InfoRow("Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
    InfoRow("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    InfoRow("IP address", ips.firstOrNull()?.substringBefore(' ') ?: "Not connected")
    Spacer(Modifier.height(4.dp))
    Note("BootTV is an ad-free home screen. No ads, no tracking, no analytics.")
    SettingButton("Device info") {
        openSystemSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)
    }
}
