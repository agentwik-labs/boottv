package dev.boottv.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.boottv.R
import dev.boottv.core.Routes
import dev.boottv.core.ui.BootColors
import androidx.compose.ui.graphics.toArgb
import dev.boottv.livetv.TvProvider

/**
 * BootTV's menu drawn over whatever app is on screen (Spectrum, Netflix…), opened by holding Back. Plain views in an
 * accessibility overlay window, so it needs no extra permission and works on top of any app.
 */
class QuickMenu(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: View? = null

    val showing get() = root != null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    private fun entries(): List<Pair<String, String>> = buildList {
        add("Home" to Routes.HOME)
        add("Live TV guide" to Routes.LIVE)
        if (TvProvider.installedPackage(service) != null) add(TvProvider.NAME to TvProvider.ROUTE)
        add("Cartoons" to Routes.TOONS)
        add("Free Movies" to Routes.FREE)
        add("My Media" to Routes.MEDIA)
        add("Search" to Routes.SEARCH)
        add("Settings" to Routes.SETTINGS)
    }

    fun show() {
        if (root != null) return
        val font = runCatching { ResourcesCompat.getFont(service, R.font.inter_medium) }.getOrNull()
        val panel = object : LinearLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
            val bg = BootColors.Background
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg.copy(alpha = 0.97f).toArgb(), bg.copy(alpha = 0.9f).toArgb()))
        }
        panel.addView(TextView(service).apply {
            text = if (BootColors.theme.playful) "◓  BootTV" else "BootTV"
            setTextColor(if (BootColors.theme.playful) 0xFFFFCB05.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = runCatching { ResourcesCompat.getFont(service, if (BootColors.theme.playful) R.font.fredoka_bold else R.font.inter_semibold) }.getOrNull()
            setPadding(dp(14), 0, 0, dp(16))
        })
        var first: View? = null
        for ((label, route) in entries()) {
            val item = TextView(service).apply {
                text = label
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(18), dp(11), dp(18), dp(11))
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(BootColors.Focused.toArgb()) })
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(BootColors.OnFocused.toArgb(), BootColors.TextPrimary.toArgb())))
                setOnClickListener { hide(); RemoteButtons.open(service, route) }
            }
            panel.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            if (first == null) first = item
        }
        panel.addView(TextView(service).apply {
            text = "Back to close · hold Back in any app to open"
            setTextColor(BootColors.TextSecondary.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = font
            setPadding(dp(18), dp(14), 0, 0)
        })
        val lp = WindowManager.LayoutParams(
            dp(280), ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            dimAmount = 0.35f
        }
        runCatching { wm.addView(panel, lp) }.onSuccess {
            root = panel
            first?.post { first?.requestFocus() }
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        runCatching { wm.removeView(v) }
    }
}
