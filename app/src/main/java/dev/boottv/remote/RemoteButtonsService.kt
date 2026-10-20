package dev.boottv.remote

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.boottv.core.Routes
import dev.boottv.livetv.TvProvider

/** Watches only for the remote buttons assigned in Settings › Remote buttons and opens BootTV there. */
class RemoteButtonsService : AccessibilityService() {
    override fun onServiceConnected() {
        RemoteButtons.load(this)
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
        // Android TV keeps the Guide and TV buttons for the system before apps see them; here they still arrive.
        val route = RemoteButtons.mappings.value[code]?.route ?: when (code) {
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> if (TvProvider.liveOpensProvider(this)) TvProvider.ROUTE else Routes.LIVE
            else -> return false
        }
        // Act on release, and swallow the press too so the button's own app doesn't also open.
        if (event.action == KeyEvent.ACTION_UP) RemoteButtons.open(this, route)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
