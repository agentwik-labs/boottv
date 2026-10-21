package dev.boottv.remote

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import dev.boottv.core.ui.ThemePresets
import dev.boottv.settings.BootPrefs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches only for the remote buttons assigned in Settings › Remote buttons and opens BootTV there; holding Back in
 * another app opens BootTV's menu on top of it.
 */
class RemoteButtonsService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var menu: QuickMenu
    /** The app in front, from window changes (Back-hold does nothing new inside BootTV, which has its own menu). */
    private var foreground: String? = null
    private var backPending = false
    private var backWasLong = false
    private val longBack = Runnable {
        if (backPending) { backPending = false; backWasLong = true; menu.show() }
    }
    /** Lets tools (adb, which holds DUMP) open the menu for testing; ordinary apps can't send this. */
    private val showReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { menu.show() }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onServiceConnected() {
        RemoteButtons.load(this)
        // The menu follows the chosen theme even if BootTV's screen hasn't been opened since the TV started.
        scope.launch { BootPrefs.themeIdFlow(this@RemoteButtonsService).collect { BootColors.setTheme(ThemePresets.byId(it)) } }
        scope.launch {
            BootPrefs.flow(this@RemoteButtonsService).collect { dev.boottv.core.ui.Sprites.enabled = it.sprites; dev.boottv.core.ui.Sprites.partnerChoice = it.partner }
        }
        menu = QuickMenu(this)
        ContextCompat.registerReceiver(this, showReceiver, IntentFilter(ACTION_SHOW_MENU), "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(showReceiver) }
        if (::menu.isInitialized) menu.hide()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (RemoteButtons.learning.value && RemoteButtons.canAssign(code)) {
            if (event.action == KeyEvent.ACTION_UP) {
                RemoteButtons.learned.value = code
                RemoteButtons.learning.value = false
            }
            return true
        }
        if (::menu.isInitialized && menu.showing) return false // the menu window handles its own keys
        // Like a remote's Back: tap = Back, hold = BootTV's menu. Esc and Backspace too, because keyboards (and the
        // emulator) send those where a remote sends Back, and apps like Spectrum ignore them otherwise.
        val backLike = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_DEL
        if (backLike && foreground != packageName) return holdBack(event)
        // Android TV keeps the Guide and TV buttons for the system before apps see them; here they still arrive.
        val route = RemoteButtons.mappings.value[code]?.route ?: when (code) {
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> Routes.LIVE
            else -> return false
        }
        // Act on release, and swallow the press too so the button's own app doesn't also open.
        if (event.action == KeyEvent.ACTION_UP) {
            if (route == RemoteButtons.QUICK_MENU) menu.show() else RemoteButtons.open(this, route)
        }
        return true
    }

    /** Back held for [LONG_MS] opens the menu; a normal press is passed on as Back. */
    private fun holdBack(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                backPending = true; backWasLong = false
                handler.postDelayed(longBack, LONG_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longBack)
                if (backPending) { backPending = false; performGlobalAction(GLOBAL_ACTION_BACK) }
                backWasLong = false
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Our own menu window isn't BootTV coming to the front; only BootTV's screen is.
            if (pkg == packageName && event.className?.toString()?.endsWith("MainActivity") != true) return
            if (pkg != "com.android.systemui") foreground = pkg
        }
    }

    override fun onInterrupt() {}

    companion object {
        const val ACTION_SHOW_MENU = "dev.boottv.SHOW_QUICK_MENU"
        private const val LONG_MS = 600L
    }
}
