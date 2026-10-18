package dev.boottv.jellyfin

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.boottv.core.ui.BootColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * D-pad friendly text field: the outer Surface takes focus; pressing OK starts editing and opens the TV keyboard.
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }

    fun finish(moveNext: Boolean) {
        editing = false
        keyboard?.hide()
        runCatching { surfaceFocus.requestFocus() }
        if (moveNext) focusManager.moveFocus(FocusDirection.Down)
    }

    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = BootColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = { editing = true },
            modifier = Modifier.widthIn(min = 360.dp, max = 560.dp).fillMaxWidth().focusRequester(surfaceFocus),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = BootColors.Surface,
                focusedContainerColor = BootColors.SurfaceRaised,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, if (editing) BootColors.Accent else BootColors.SurfaceRaised), shape = RoundedCornerShape(8.dp)),
                focusedBorder = Border(BorderStroke(2.dp, BootColors.Accent), shape = RoundedCornerShape(8.dp)),
            ),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = BootColors.TextSecondary.copy(alpha = 0.6f), fontSize = 18.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = BootColors.TextPrimary, fontSize = 18.sp),
                    cursorBrush = SolidColor(BootColors.Accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { finish(true) },
                        onDone = { finish(false); onDone() },
                        onGo = { finish(false); onDone() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        // Once the keyboard is closed, Up/Down/Back must leave the field like any other control.
                        .onPreviewKeyEvent { e ->
                            if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { finish(true); true }
                                Key.DirectionUp -> { finish(false); focusManager.moveFocus(FocusDirection.Up); true }
                                Key.Back, Key.Escape -> { finish(false); true }
                                else -> false
                            }
                        }
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { if (!it.isFocused && editing) editing = false },
                )
            }
        }
    }
}

private enum class SetupStep { Server, Login, QuickConnect }

/** Shown inside the Settings screen: connect to a Jellyfin server and sign in. */
@Composable
internal fun JellyfinSettingsContent() {
    val session by JellyfinStore.sessionFlow.collectAsState(initial = null)
    val lastServer by JellyfinStore.lastServerFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SetupStep.Server) }
    var serverInput by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var quickConnectAvailable by remember { mutableStateOf(false) }
    var qcCode by remember { mutableStateOf<String?>(null) }
    var qcSecret by remember { mutableStateOf<String?>(null) }

    // When the step changes (connected, signed in, Quick Connect...), the control that had focus
    // disappears; put focus on the new step's first control instead of losing it to the category list.
    val stepFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    val signedIn = session != null
    var stepChanges by remember { mutableIntStateOf(0) }
    LaunchedEffect(step, signedIn) {
        if (stepChanges++ == 0) return@LaunchedEffect
        repeat(10) {
            delay(80)
            if (runCatching { stepFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    LaunchedEffect(lastServer) {
        if (serverInput.isEmpty() && !lastServer.isNullOrBlank()) serverInput = lastServer!!
    }

    suspend fun finishLogin(url: String, name: String, result: AuthResult) {
        val token = result.accessToken
        val user = result.user
        if (token.isNullOrBlank() || user == null || user.id.isBlank()) {
            error = "Sign-in failed: the server didn't return a session."
            return
        }
        JellyfinStore.saveLogin(url, name, user.id, user.name ?: "", token)
        password = ""
        step = SetupStep.Server
        status = null
        error = null
    }

    fun connect(input: String) {
        val url = normalizeServerUrl(input)
        if (url.isBlank()) { error = "Enter your server address, e.g. 192.168.1.20:8096"; return }
        scope.launch {
            busy = true; error = null; status = "Connecting to $url…"
            try {
                val info = JellyfinAuth.publicInfo(url)
                server = url
                serverName = info.serverName ?: url
                serverInput = url
                JellyfinStore.saveServer(url, info.serverName)
                quickConnectAvailable = JellyfinAuth.quickConnectEnabled(url)
                step = SetupStep.Login
                status = null
            } catch (e: Exception) {
                status = null
                error = "Couldn't find a Jellyfin server at $url. Check the address and that Jellyfin is running on your Mac."
            } finally {
                busy = false
            }
        }
    }

    // Quick Connect polling.
    LaunchedEffect(step, qcSecret) {
        val url = server ?: return@LaunchedEffect
        val secret = qcSecret ?: return@LaunchedEffect
        if (step != SetupStep.QuickConnect) return@LaunchedEffect
        var failures = 0
        while (true) {
            delay(3000)
            try {
                val state = JellyfinAuth.quickConnectState(url, secret)
                if (state.authenticated) {
                    val result = JellyfinAuth.quickConnectAuthenticate(url, secret)
                    finishLogin(url, serverName ?: url, result)
                    qcSecret = null; qcCode = null
                    break
                }
                failures = 0
            } catch (e: JellyfinException) {
                if (e.code == 404 || e.code == 401) {
                    error = "Quick Connect code expired. Start again."
                    qcSecret = null; qcCode = null
                    break
                }
            } catch (e: Exception) {
                if (++failures >= 5) { error = "Lost connection to the server."; break }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("My Media (Jellyfin)", style = MaterialTheme.typography.titleLarge, color = BootColors.TextPrimary, fontWeight = FontWeight.SemiBold)

        val s = session
        if (s != null) {
            Box(Modifier.background(BootColors.Surface, RoundedCornerShape(10.dp)).padding(16.dp)) {
                Column {
                    Text("Connected", color = BootColors.Accent, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("${s.serverName}", style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary)
                    Text(s.server, style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
                    Text("Signed in as ${s.userName}", style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary)
                }
            }
            JfButton("Sign out", Icons.AutoMirrored.Filled.Logout, modifier = Modifier.focusRequester(stepFocus), onClick = {
                scope.launch { JellyfinStore.signOut(); step = SetupStep.Server }
            })
            return@Column
        }

        Text(
            "Watch the movies and shows stored on your Mac. Install the free Jellyfin Server on the Mac (jellyfin.org/downloads), " +
                "add your media folders as libraries, then connect here.",
            style = MaterialTheme.typography.bodyMedium, color = BootColors.TextSecondary,
            modifier = Modifier.widthIn(max = 720.dp),
        )

        when (step) {
            SetupStep.Server -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    JfButton(
                        if (discovering) "Searching…" else "Find servers on my network",
                        Icons.Filled.Wifi,
                        enabled = !discovering,
                        onClick = {
                            scope.launch {
                                discovering = true; error = null
                                discovered = ServerDiscovery.discover()
                                discovering = false
                            }
                        },
                    )
                }
                discovered?.let { list ->
                    if (list.isEmpty()) {
                        Text("No servers found automatically. Enter the address shown in Jellyfin's Dashboard instead.",
                            color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            list.forEach { srv ->
                                JfButton("${srv.name ?: "Jellyfin"} · ${srv.address}", Icons.Filled.Dns, onClick = {
                                    serverInput = srv.address
                                    connect(srv.address)
                                })
                            }
                        }
                    }
                }
                TvTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = "Server address",
                    modifier = Modifier.focusRequester(stepFocus),
                    placeholder = "192.168.1.20:8096",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onDone = { connect(serverInput) },
                )
                JfButton("Connect", Icons.Filled.Link, primary = true, enabled = !busy, onClick = { connect(serverInput) })
            }

            SetupStep.Login -> {
                Text("Server: ${serverName ?: server}", color = BootColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                TvTextField(value = username, onValueChange = { username = it }, label = "Username", placeholder = "Your Jellyfin user",
                    modifier = Modifier.focusRequester(stepFocus))
                TvTextField(
                    value = password, onValueChange = { password = it }, label = "Password", password = true,
                    placeholder = "Leave empty if none", imeAction = ImeAction.Done,
                )
                // Moving down into this row lands on Sign in, not whichever button is geometrically closest.
                Row(
                    Modifier.focusProperties { enter = { signInFocus } }.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JfButton("Sign in", Icons.AutoMirrored.Filled.Login, modifier = Modifier.focusRequester(signInFocus), primary = true, enabled = !busy && username.isNotBlank(), onClick = {
                        val url = server ?: return@JfButton
                        scope.launch {
                            busy = true; error = null; status = "Signing in…"
                            try {
                                finishLogin(url, serverName ?: url, JellyfinAuth.authenticateByName(url, username.trim(), password))
                            } catch (e: JellyfinException) {
                                error = if (e.code == 401) "Wrong username or password." else "Sign-in failed (HTTP ${e.code})."
                            } catch (e: Exception) {
                                error = "Couldn't reach the server."
                            } finally {
                                busy = false; status = null
                            }
                        }
                    })
                    if (quickConnectAvailable) {
                        JfButton("Use Quick Connect", Icons.Filled.QrCode, enabled = !busy, onClick = {
                            val url = server ?: return@JfButton
                            scope.launch {
                                busy = true; error = null
                                try {
                                    val r = JellyfinAuth.quickConnectInitiate(url)
                                    qcCode = r.code; qcSecret = r.secret
                                    step = SetupStep.QuickConnect
                                } catch (e: Exception) {
                                    error = "Quick Connect isn't available on this server."
                                } finally {
                                    busy = false
                                }
                            }
                        })
                    }
                    JfButton("Change server", Icons.Filled.Dns, onClick = { step = SetupStep.Server; error = null })
                }
            }

            SetupStep.QuickConnect -> {
                Text("On your phone or Mac, open Jellyfin, go to your profile → Quick Connect, and enter this code:",
                    color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 720.dp))
                Box(Modifier.background(BootColors.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 32.dp, vertical = 16.dp)) {
                    Text(qcCode ?: "------", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 8.sp)
                }
                Text("Waiting for approval…", color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                JfButton("Cancel", null, modifier = Modifier.focusRequester(stepFocus), onClick = { step = SetupStep.Login; qcSecret = null; qcCode = null })
            }
        }

        status?.let { Text(it, color = BootColors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, color = BootColors.Live, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 720.dp)) }
        Spacer(Modifier.width(1.dp))
    }
}
