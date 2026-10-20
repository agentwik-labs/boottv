package dev.boottv.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import dev.boottv.MainActivity
import dev.boottv.core.Routes
import dev.boottv.livetv.TvProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something a remote button can open. [route] is a BootTV route, or [LAST_CHANNEL]. */
data class ButtonAction(val label: String, val route: String)

/**
 * Hot buttons: remote keys the viewer has assigned to BootTV sections. They work from any app once the
 * Remote buttons accessibility service is on; inside BootTV the Guide and Channel keys work regardless.
 */
object RemoteButtons {
    const val LAST_CHANNEL = "live:last"
    const val EXTRA_ROUTE = "dev.boottv.ROUTE"
    private const val PREFS = "remote_buttons"

    val fixedActions = listOf(
        ButtonAction("Home", Routes.HOME),
        ButtonAction("Live TV guide", Routes.LIVE),
        ButtonAction("Last channel watched", LAST_CHANNEL),
        ButtonAction(TvProvider.NAME, TvProvider.ROUTE),
        ButtonAction("Cartoons", Routes.TOONS),
        ButtonAction("Free Movies", Routes.FREE),
        ButtonAction("My Media", Routes.MEDIA),
        ButtonAction("Search", Routes.SEARCH),
    )

    /** Keys never taken over: navigation, OK, Back, Home, power, volume and the mic. */
    private val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE,
    )

    fun canAssign(keyCode: Int) = keyCode !in reserved && keyCode != KeyEvent.KEYCODE_UNKNOWN &&
        !(keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) && !(keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

    private val _mappings = MutableStateFlow<Map<Int, ButtonAction>>(emptyMap())
    val mappings: StateFlow<Map<Int, ButtonAction>> = _mappings.asStateFlow()

    /** While Settings is waiting for "press the button to use", the next assignable key goes here instead. */
    val learning = MutableStateFlow(false)
    val learned = MutableStateFlow<Int?>(null)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mappings.value = p.all.mapNotNull { (k, v) ->
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val (label, route) = (v as? String)?.split('\u0001')?.takeIf { it.size == 2 } ?: return@mapNotNull null
            code to ButtonAction(label, route)
        }.toMap()
    }

    fun assign(context: Context, keyCode: Int, action: ButtonAction) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$keyCode", "${action.label}\u0001${action.route}").apply()
        _mappings.value = _mappings.value + (keyCode to action)
    }

    fun unassign(context: Context, keyCode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$keyCode").apply()
        _mappings.value = _mappings.value - keyCode
    }

    fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_PROG_RED -> "red button"
        KeyEvent.KEYCODE_PROG_GREEN -> "green button"
        KeyEvent.KEYCODE_PROG_YELLOW -> "yellow button"
        KeyEvent.KEYCODE_PROG_BLUE -> "blue button"
        KeyEvent.KEYCODE_GUIDE -> "Guide button"
        KeyEvent.KEYCODE_TV -> "TV button"
        KeyEvent.KEYCODE_BOOKMARK -> "Bookmark button"
        KeyEvent.KEYCODE_SETTINGS -> "Settings button"
        KeyEvent.KEYCODE_ALL_APPS -> "Apps button"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            .split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) } + " button"
    }

    /** Brings BootTV to the front on [route]. */
    fun open(context: Context, route: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    fun serviceEnabled(context: Context): Boolean {
        val me = ComponentName(context, RemoteButtonsService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) || it.equals(me.replace("/dev.boottv.", "/."), ignoreCase = true) }
    }
}
