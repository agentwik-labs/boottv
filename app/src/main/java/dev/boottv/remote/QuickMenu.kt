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
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5000000.toInt(), 0xE6000000.toInt()))
        }
        panel.addView(TextView(service).apply {
            text = "BootTV"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = runCatching { ResourcesCompat.getFont(service, R.font.inter_semibold) }.getOrNull()
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
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFFF5F5F7.toInt()) })
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF111114.toInt(), 0xFFF5F5F7.toInt())))
                setOnClickListener { hide(); RemoteButtons.open(service, route) }
            }
            panel.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            if (first == null) first = item
        }
        panel.addView(TextView(service).apply {
            text = "Back to close"
            setTextColor(0xFF8E8E93.toInt())
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
