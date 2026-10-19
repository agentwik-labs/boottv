package dev.boottv.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** WebView that lets the browser screen look at every key before the page does. */
@SuppressLint("ViewConstructor")
class TvWebView(context: Context) : WebView(context) {
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    fun dispatchKeyToPage(event: KeyEvent): Boolean = super.dispatchKeyEvent(event)
}

/** Holds the HTML5 fullscreen video view; OK / play-pause / left / right control the video. */
@SuppressLint("ViewConstructor")
private class FullscreenHolder(context: Context, private val onKey: (KeyEvent) -> Boolean) : FrameLayout(context) {
    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
}

private const val DIR_UP = 1
private const val DIR_DOWN = 2
private const val DIR_LEFT = 4
private const val DIR_RIGHT = 8

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

/** YouTube's TV ("leanback") site only serves its 10-foot UI to TV user agents. */
private const val TV_UA =
    "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36"

private const val VIDEO_JS_PREFIX =
    "(function(){var f=document.fullscreenElement||document.webkitFullscreenElement;" +
        "var v=(f&&f.tagName==='VIDEO')?f:((f&&f.querySelector('video'))||document.querySelector('video'));" +
        "if(!v)return;"

/**
 * All mutable browser state plus the logic that drives the WebView (cursor, keys, fullscreen, UA).
 * Lives as long as the browser destination is composed.
 */
@Stable
class BrowserState(
    val context: Context,
    val store: BrowserStore,
    private val scope: CoroutineScope,
    private val onExit: () -> Unit,
) {
    private val activity: Activity? = context.findActivity()
    private val density = context.resources.displayMetrics.density
    private val defaultUa: String = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)

    var cursorMode by mutableStateOf(true)
    var desktopMode by mutableStateOf(false)
    var adBlock by mutableStateOf(true)

    var toolbarVisible by mutableStateOf(false)
    var editingUrl by mutableStateOf(false)
    var panelVisible by mutableStateOf(false)
    var startVisible by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
    var messageSeq by mutableIntStateOf(0)

    var cursor by mutableStateOf(Offset.Zero)
    var areaSize by mutableStateOf(IntSize.Zero)
        private set
    var heldMask by mutableIntStateOf(0)
        private set

    /** Bumped whenever the WebView should take focus back from Compose UI. */
    var focusWebSeq by mutableIntStateOf(0)

    private var okDownTime = 0L
    private var okHeld = false
    private var fullscreenHolder: FullscreenHolder? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingJsDx = 0f
    private var pendingJsDy = 0f
    private var lastJsScroll = 0L

    val webView: TvWebView = TvWebView(context).also { setup(it) }

    fun toast(text: String) {
        message = text
        messageSeq++
    }

    // ---------------------------------------------------------------- WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(w: TvWebView) {
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.setBackgroundColor(android.graphics.Color.BLACK)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // New windows go through onCreateWindow, where pop-ups are blocked or opened in place.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, false)
        }
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
        w.keyInterceptor = ::onWebKey
        w.setOnFocusChangeListener { _, hasFocus ->
            // Moving focus down from the toolbar into the page hides the toolbar.
            if (hasFocus && toolbarVisible && !editingUrl) toolbarVisible = false
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame && AdBlocker.isBlocked(request.url.host)) {
                val n = AdBlocker.blockedCount.incrementAndGet()
                view.post { blocked = n }
                return AdBlocker.emptyResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                // Ad scripts redirect the whole page to a pop-up network on any click.
                if (request.isForMainFrame && AdBlocker.isBlocked(uri.host)) {
                    toast("Blocked a redirect to an ad")
                    return true
                }
                if (request.isForMainFrame && uaFor(uri.toString()) != view.settings.userAgentString) {
                    loadUrl(uri.toString())
                    return true
                }
                return false
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            handleExternalScheme(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            AdBlocker.blockedCount.set(0)
            blocked = 0
            loading = true
            this@BrowserState.url = url
            if (isYouTubeTv(url) && cursorMode) {
                // The TV site is driven entirely by D-pad keys, so hand keys to the page.
                cursorMode = false
                toast("Scroll / focus mode for TV site")
            }
            updateNav()
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            progress = 100
            updateNav()
            if (url.startsWith("http")) {
                val t = view.title.orEmpty()
                scope.launch { store.addHistory(t, url) }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            this@BrowserState.url = url
            updateNav()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            // The renderer crashed or was killed for memory; this WebView is unusable now.
            view.post { onExit() }
            return true
        }
    }

    /**
     * Pop-up handling. With the ad blocker on, a new window to another site is blocked; one to the same
     * site (login windows, "open in new tab") loads in place, since the TV browser has a single window.
     */
    private fun onPopup(target: String, userGesture: Boolean) {
        val sameSite = siteOf(target) == siteOf(url)
        if (!adBlock || (userGesture && sameSite)) {
            loadUrl(target)
        } else {
            blockedPopups++
            toast("Pop-up blocked")
        }
    }

    var blockedPopups by mutableIntStateOf(0)
        private set

    private fun siteOf(u: String): String {
        val host = runCatching { Uri.parse(u).host }.getOrNull()?.lowercase() ?: return ""
        return host.split('.').takeLast(2).joinToString(".")
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // The page never gets a real window: a throwaway WebView catches the address it tries to open.
            val probe = WebView(context)
            var handled = false
            fun capture(target: String) {
                if (handled || !target.startsWith("http")) return
                handled = true
                onPopup(target, isUserGesture)
                probe.post { probe.stopLoading(); probe.destroy() }
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    capture(request.url.toString()); return true
                }
                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) { capture(u) }
            }
            // Blank or script-written pop-ups never navigate anywhere; count them as blocked and clean up.
            probe.postDelayed({
                if (!handled) {
                    handled = true
                    if (adBlock) { blockedPopups++; toast("Pop-up blocked") }
                    probe.destroy()
                }
            }, 3000)
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            this@BrowserState.title = title.orEmpty()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            hideFullscreen(notifyPage = false)
        }

        override fun getDefaultVideoPoster(): Bitmap =
            // Avoids the grey "play" placeholder WebView draws before a video starts.
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    private fun handleExternalScheme(uri: Uri) {
        try {
            if (uri.scheme.equals("intent", true)) {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    intent.getStringExtra("browser_fallback_url")?.let { loadUrl(it) }
                        ?: toast("No app can open this link")
                }
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            toast("No app can open this link")
        }
    }

    private fun updateNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun uaFor(url: String): String = when {
        isYouTubeTv(url) -> TV_UA
        desktopMode -> DESKTOP_UA
        else -> defaultUa
    }

    // ---------------------------------------------------------------- navigation actions

    fun loadUrl(target: String) {
        val ua = uaFor(target)
        if (webView.settings.userAgentString != ua) webView.settings.userAgentString = ua
        startVisible = false
        panelVisible = false
        editingUrl = false
        url = target
        webView.loadUrl(target)
        requestWebFocus()
    }

    fun submitInput(text: String) {
        hideToolbar() // also hands focus back to the page, not the side menu
        loadUrl(urlFromInput(text))
    }

    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reloadOrStop() { if (loading) webView.stopLoading() else webView.reload() }
    fun zoomIn() { if (!webView.zoomIn()) toast("Maximum zoom") }
    fun zoomOut() { if (!webView.zoomOut()) toast("Minimum zoom") }

    fun showHome() {
        startVisible = true
        panelVisible = false
        toolbarVisible = true
    }

    fun toggleDesktop() {
        desktopMode = !desktopMode
        scope.launch { store.setDesktopMode(desktopMode) }
        toast(if (desktopMode) "Desktop site on" else "Desktop site off")
        val current = webView.url
        if (!current.isNullOrEmpty()) {
            webView.settings.userAgentString = uaFor(current)
            webView.reload()
        }
    }

    fun toggleCursor() {
        cursorMode = !cursorMode
        scope.launch { store.setCursorMode(cursorMode) }
        toast(if (cursorMode) "Cursor mode: arrows move the pointer" else "Scroll / focus mode: arrows go to the page")
    }

    fun toggleAdBlock() {
        adBlock = !adBlock
        AdBlocker.enabled = adBlock
        scope.launch { store.setAdBlock(adBlock) }
        toast(if (adBlock) "Ad blocker on" else "Ad blocker off")
        if (!webView.url.isNullOrEmpty()) webView.reload()
    }

    fun showToolbar() {
        heldMask = 0
        toolbarVisible = true
    }

    fun hideToolbar() {
        toolbarVisible = false
        editingUrl = false
        requestWebFocus()
    }

    fun requestWebFocus() {
        focusWebSeq++
    }

    /** Back key priority: fullscreen video → overlays → page history → leave the browser. */
    fun handleBack() {
        when {
            fullscreen -> hideFullscreen(notifyPage = true)
            editingUrl -> editingUrl = false
            panelVisible -> panelVisible = false
            startVisible && !webView.url.isNullOrEmpty() -> { startVisible = false; hideToolbar() }
            toolbarVisible && !startVisible -> hideToolbar()
            webView.canGoBack() -> webView.goBack()
            else -> onExit()
        }
    }

    // ---------------------------------------------------------------- fullscreen video

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenHolder != null) {
            callback.onCustomViewHidden()
            return
        }
        val decor = activity?.window?.decorView as? ViewGroup
        if (decor == null) {
            callback.onCustomViewHidden()
            return
        }
        val holder = FullscreenHolder(context, ::onFullscreenKey)
        (view.parent as? ViewGroup)?.removeView(view)
        holder.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        decor.addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        holder.requestFocus()
        fullscreenHolder = holder
        customViewCallback = callback
        fullscreen = true
    }

    fun hideFullscreen(notifyPage: Boolean) {
        val holder = fullscreenHolder ?: return
        (holder.parent as? ViewGroup)?.removeView(holder)
        holder.removeAllViews()
        fullscreenHolder = null
        val cb = customViewCallback
        customViewCallback = null
        fullscreen = false
        if (notifyPage) cb?.onCustomViewHidden()
        requestWebFocus()
    }

    private fun onFullscreenKey(e: KeyEvent): Boolean {
        val js = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> "v.paused?v.play():v.pause();"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "v.play();"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "v.pause();"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "v.currentTime=Math.max(0,v.currentTime-10);"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "v.currentTime=v.currentTime+10;"
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) webView.evaluateJavascript("$VIDEO_JS_PREFIX$js})()", null)
        return true
    }

    // ---------------------------------------------------------------- keys & virtual cursor

    fun onAreaSize(size: IntSize) {
        val first = areaSize == IntSize.Zero
        areaSize = size
        cursor = if (first) Offset(size.width / 2f, size.height / 2f) else clampToArea(cursor)
    }

    private fun clampToArea(o: Offset) = Offset(
        o.x.coerceIn(0f, max(0f, areaSize.width - 1f)),
        o.y.coerceIn(0f, max(0f, areaSize.height - 1f)),
    )

    private fun dirFor(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> DIR_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DIR_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DIR_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DIR_RIGHT
        else -> 0
    }

    private fun onWebKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> {
                if (down && e.repeatCount == 0) {
                    if (toolbarVisible) hideToolbar() else showToolbar()
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) webView.pageDown(false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (down) webView.evaluateJavascript("$VIDEO_JS_PREFIX v.paused?v.play():v.pause();})()", null)
                return true
            }
        }
        if (!cursorMode) {
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && down) {
                val handled = webView.dispatchKeyToPage(e)
                if (!handled && e.repeatCount == 0 && !webView.canScrollVertically(-1)) showToolbar()
                return true
            }
            return false
        }
        val dir = dirFor(e.keyCode)
        if (dir != 0) {
            if (down) {
                if (e.repeatCount == 0) {
                    if (dir == DIR_UP && cursor.y <= 2f && !webView.canScrollVertically(-1)) {
                        showToolbar()
                        return true
                    }
                    heldMask = heldMask or dir
                    val step = 6f * density
                    moveCursor(
                        when (dir) { DIR_LEFT -> -step; DIR_RIGHT -> step; else -> 0f },
                        when (dir) { DIR_UP -> -step; DIR_DOWN -> step; else -> 0f },
                    )
                } else if (heldMask and dir == 0) {
                    heldMask = heldMask or dir
                }
            } else if (e.action == KeyEvent.ACTION_UP) {
                heldMask = heldMask and dir.inv()
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0 && !okHeld) {
                    okHeld = true
                    okDownTime = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN)
                } else if (e.action == KeyEvent.ACTION_UP && okHeld) {
                    okHeld = false
                    touch(MotionEvent.ACTION_UP)
                }
                return true
            }
        }
        return false
    }

    /**
     * Frame-driven cursor movement with acceleration. [heldSeconds] is how long the current
     * direction set has been held, [dt] the frame delta.
     */
    fun stepCursor(heldSeconds: Float, dt: Float) {
        val mask = heldMask
        if (mask == 0) return
        val minSpeed = 260f * density
        val maxSpeed = 1500f * density
        val t = min(1f, heldSeconds / 1.4f)
        val speed = minSpeed + (maxSpeed - minSpeed) * t * t
        var dx = 0f
        var dy = 0f
        if (mask and DIR_LEFT != 0) dx -= 1f
        if (mask and DIR_RIGHT != 0) dx += 1f
        if (mask and DIR_UP != 0) dy -= 1f
        if (mask and DIR_DOWN != 0) dy += 1f
        moveCursor(dx * speed * dt, dy * speed * dt)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        if (areaSize == IntSize.Zero) return
        val target = Offset(cursor.x + dx, cursor.y + dy)
        val clamped = clampToArea(target)
        cursor = clamped
        val overX = target.x - clamped.x
        val overY = target.y - clamped.y
        if (overX != 0f || overY != 0f) scrollPage(overX * 1.5f, overY * 1.5f)
        if (okHeld) touch(MotionEvent.ACTION_MOVE)
    }

    /** Scrolls the document, or the scrollable element under the cursor when the document can't. */
    private fun scrollPage(dx: Float, dy: Float) {
        val canV = dy != 0f && webView.canScrollVertically(if (dy > 0) 1 else -1)
        val canH = dx != 0f && webView.canScrollHorizontally(if (dx > 0) 1 else -1)
        if (canV || canH) {
            webView.scrollBy(if (canH) dx.roundToInt() else 0, if (canV) dy.roundToInt() else 0)
            return
        }
        pendingJsDx += dx / density
        pendingJsDy += dy / density
        val now = SystemClock.uptimeMillis()
        if (now - lastJsScroll < 40) return
        lastJsScroll = now
        val x = cursor.x / density
        val y = cursor.y / density
        val sx = pendingJsDx
        val sy = pendingJsDy
        pendingJsDx = 0f
        pendingJsDy = 0f
        webView.evaluateJavascript(
            "(function(x,y,dx,dy){var e=document.elementFromPoint(x,y);" +
                "while(e&&e!==document.body&&e!==document.documentElement){var s=getComputedStyle(e);" +
                "if((dy&&e.scrollHeight>e.clientHeight&&/(auto|scroll)/.test(s.overflowY))||" +
                "(dx&&e.scrollWidth>e.clientWidth&&/(auto|scroll)/.test(s.overflowX))){e.scrollBy(dx,dy);return;}" +
                "e=e.parentElement;}window.scrollBy(dx,dy);})($x,$y,$sx,$sy)",
            null,
        )
    }

    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(okDownTime, now, action, cursor.x, cursor.y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun destroy() {
        hideFullscreen(notifyPage = true)
        webView.keyInterceptor = null
        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
