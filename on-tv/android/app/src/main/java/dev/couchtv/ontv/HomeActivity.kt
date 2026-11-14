package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The TV home screen: the same page as the computer-bridge mode (tv-home/web), served by this
 * app's own server at 127.0.0.1. It is the home screen only when "Use as home screen" is switched
 * on in setup (the HOME filter is on an alias that is off by default).
 */
class HomeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val banners = ConcurrentHashMap<String, ByteArray>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val base get() = "http://127.0.0.1:${Store.port}"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setCookie(base, "couchtv_token=${Auth.internalToken}; path=/")
        val w = makeWeb()
        web = w
        shown = java.lang.ref.WeakReference(this)
        setContentView(w)
        hideBars()
        tiles = TilesLayer(this, tilesHost)
        load()
    }

    /**
     * Low-memory TVs (1 GB): BridgeService keeps this process important, so Android never reclaims
     * the home page while another app is in front. Only when Android says memory is critical, and
     * the page is out of sight, drop the WebView; onStart builds it again. No timer, so normal use
     * never pays for a rebuild. The activity stays, so Back still lands here.
     */
    private fun release() {
        val w = web ?: return
        if (front || isFinishing || isDestroyed) return
        web = null; ready = false
        banners.clear(); icons.clear()
        tiles?.release(); detachTiles()
        setContentView(View(this).apply { setBackgroundColor(GROUND) })
        w.stopLoading(); w.removeAllViews(); w.destroy()
        android.util.Log.i("couchtv.home", "page released (memory low)")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the most severe states: RUNNING_CRITICAL (this process runs a service) or COMPLETE (cached).
        // On a 1 GB TV, RUNNING_LOW arrives every time YouTube or Netflix starts; releasing then made every
        // return take 3 to 7 seconds, so it is left alone, as are UI_HIDDEN and RUNNING_MODERATE.
        android.util.Log.i("couchtv.home", "trim level $level")
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) { release(); tiles?.trim() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (web == null) {
            val w = makeWeb()
            web = w
            setContentView(w)
            hideBars()
            load()
        }
        attachTiles()
    }

    override fun onStop() {
        started = false
        detachTiles()   // the tiles' window surface is freed while the home is out of sight
        super.onStop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachTiles()   // the first start: the window token exists only now
    }

    // ---------------------------------------------------------------- smooth tiles (TilesLayer.kt, NATIVE-TILES-PLAN.md)

    private var tiles: TilesLayer? = null
    private var tilesIn = 0              // where the layer is: 0 nowhere, 1 its own window, 2 over the page
    private var tilesFallback = false    // its own window was refused once: over the page from then on
    private var started = false

    private val tilesHost = object : TilesLayer.Host {
        override fun icon(pkg: String, size: Int): Bitmap? = iconBitmap(pkg, size)
        override fun art(id: String): ByteArray? = WatchNext.art(id)
        override fun origin(out: IntArray) { val w = web; if (w != null) w.getLocationOnScreen(out) else { out[0] = 0; out[1] = 0 } }
        override fun ready(n: Int) = js("window.tv && tv.tilesReady && tv.tilesReady($n)")
        override fun resync() = js("window.tv && tv.syncTiles && tv.syncTiles()")
    }

    /**
     * The layer goes in a panel window on top of this activity's own (so its frames never redraw the WebView),
     * once the activity's window has a token, and only once; it is taken out again in onStop, release() and
     * onDestroy, so it never outlives the activity's window. If Android refuses the panel, it goes over the
     * WebView inside the activity instead.
     */
    private fun attachTiles() {
        val t = tiles ?: return
        if (!started || isFinishing || isDestroyed) return
        if (tilesIn == 2 && t.parent == null) tilesIn = 0   // setContentView took it off with the old page
        if (tilesIn != 0 || t.parent != null) return
        if (TilesLayer.IN_WINDOW && !tilesFallback) {
            val token = window.decorView.windowToken ?: return   // onAttachedToWindow comes back here
            try {
                val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT)
                lp.token = token
                lp.gravity = Gravity.TOP or Gravity.START
                lp.windowAnimations = 0
                lp.title = "couchtv.tiles"
                windowManager.addView(t, lp)
                tilesIn = 1
            } catch (e: Exception) {   // WindowManager.BadTokenException and the like
                android.util.Log.w("couchtv.home", "tiles window refused, drawing them over the page: ${e.message}")
                tilesFallback = true
            }
        }
        if (tilesIn == 0 && t.parent == null) {
            addContentView(t, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tilesIn = 2
        }
        js("window.tv && tv.syncTiles && tv.syncTiles()")
    }

    private fun detachTiles() {
        val t = tiles ?: return
        when (tilesIn) {
            1 -> try { windowManager.removeViewImmediate(t) } catch (e: Exception) {}
            2 -> (t.parent as? ViewGroup)?.removeView(t)
        }
        tilesIn = 0
    }

    private var built = 0L
    private fun makeWeb(): WebView = WebView(this.also { built = android.os.SystemClock.uptimeMillis() }).apply {
            setBackgroundColor(GROUND)
            isFocusable = true; isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            addJavascriptInterface(Native(), "TVOS")
            webViewClient = object : WebViewClient() {
                // a new page (first load, a reload, the page's restart()): the tiles of the old one go; the new one sends its own
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { tiles?.clear() }
                override fun onPageFinished(view: WebView, url: String) {
                    ready = true; view.requestFocus()
                    android.util.Log.i("couchtv.home", "page ready in ${android.os.SystemClock.uptimeMillis() - built} ms")
                }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    // The server may still be starting: try again shortly.
                    if (req.isForMainFrame) { ready = false; main.postDelayed({ load() }, 700) }
                }
                // Only the home page itself may load here: its restart() (a new look, scenes, timer)
                // reloads it with location.replace, which was cancelled along with everything else.
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = !req.url.toString().startsWith("$base/tv/")
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val u = req.url
                    if (u.host != "tvos.invalid") return null
                    // a Watch Next card's picture the page cannot load itself (WatchNext.art, #122)
                    if (u.pathSegments.firstOrNull() == "art") {
                        val b = WatchNext.art(u.lastPathSegment ?: "") ?: return null
                        return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(b))
                    }
                    val pkg = u.lastPathSegment ?: return null
                    val isIcon = u.pathSegments.firstOrNull() == "icon"
                    val img = (if (isIcon) iconImage(pkg) else bannerImage(pkg)) ?: return null
                    return WebResourceResponse(if (isIcon) "image/png" else "image/jpeg", null, ByteArrayInputStream(img))
                }
            }
        }

    private fun load() { web?.loadUrl("$base/tv/") }

    override fun onResume() {
        super.onResume(); front = true; hideBars()
        if (ready) { js("window.tv && tv.resume()"); js("window.tv && tv.syncTiles && tv.syncTiles()") }
    }
    // the video background stops while an app is in front
    override fun onPause() { super.onPause(); front = false; js("window.tv && tv.pause && tv.pause()") }
    @Volatile private var front = false
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); js("window.tv && tv.home()") }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP && ready) js("window.tv && tv.back()"); return true }
        // Long press MENU opens setup, so the home screen can always reach it. Android marks a long
        // press on the repeated key down while the button is held (repeat 1, FLAG_LONG_PRESS), never
        // on the key up, so the old check on the key up never matched. The page opens a card's menu
        // on a short MENU press, so the press is held back until it is known: held, setup opens and
        // the page sees nothing; let go before that, the page gets the whole press then.
        if (e.keyCode == KeyEvent.KEYCODE_MENU) {
            when {
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> menuHeld = false
                e.action == KeyEvent.ACTION_DOWN -> if (!menuHeld) {
                    menuHeld = true
                    // Kids mode: a kids profile cannot reach setup; a parent switches profile with the PIN first
                    if (!Kids.isKid(Now.who)) startActivity(Intent(this, SetupActivity::class.java))
                }
                e.action == KeyEvent.ACTION_UP && !menuHeld -> {
                    super.dispatchKeyEvent(KeyEvent.changeAction(e, KeyEvent.ACTION_DOWN))
                    return super.dispatchKeyEvent(e)
                }
            }
            return true
        }
        // Smooth tiles: left and right move the app's own focus straight away; the page still gets the key,
        // decides, and its answer confirms or corrects it (TilesLayer.predict)
        if (e.action == KeyEvent.ACTION_DOWN && ready && (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))
            tiles?.predict(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
        return super.dispatchKeyEvent(e)
    }
    private var menuHeld = false

    private fun js(code: String) { web?.takeIf { ready }?.evaluateJavascript(code, null) }

    private fun hideBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 9 and 10 (many Fire TV sticks and older Android TVs) only have the old flags
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---------------------------------------------------------------- what the page can ask for (window.TVOS)

    inner class Native {
        @JavascriptInterface fun apps(): String {
            val out = JSONArray()
            for (ri in tvApps()) {
                val ai = ri.activityInfo
                out.put(JSONObject().put("package", ai.packageName).put("label", ri.loadLabel(packageManager).toString())
                    .put("hasBanner", ai.banner != 0 || ai.applicationInfo.banner != 0))
            }
            return out.toString()
        }
        @JavascriptInterface fun launch(pkg: String): Boolean {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
            main.post { runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            return true
        }
        @JavascriptInterface fun openSettings(): Boolean = runCatching { Tv.openSettings() }.isSuccess
        /** A Keep watching card from Android's Watch Next rows (#122): the page opens it here, as the TV home app does. */
        @JavascriptInterface fun openWatchNext(id: String): Boolean = runCatching { WatchNext.open(id) }.getOrDefault(false)
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun keep(key: String, value: String) { getSharedPreferences("page", MODE_PRIVATE).edit().putString(key, value).apply() }
        @JavascriptInterface fun kept(key: String): String? = getSharedPreferences("page", MODE_PRIVATE).getString(key, null)
        @JavascriptInterface fun setScreenOff(off: Boolean): Boolean {
            main.post {
                runCatching {
                    val lp = window.attributes
                    lp.screenBrightness = if (off) 0.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                }
            }
            return true
        }
        @JavascriptInterface fun powerOff(): Boolean {
            Thread { runCatching { Tv.powerOff() } }.start()
            return true
        }
        // Smooth tiles (TilesLayer.kt): these run on the WebView's bridge thread, so the JSON is read here and
        // only the result goes to the main thread
        @JavascriptInterface fun tiles(json: String) {
            val m = runCatching { TilesLayer.parse(json) }.getOrNull() ?: return
            main.post { if (!isDestroyed) tiles?.model(m) }
        }
        @JavascriptInterface fun tilesFocus(json: String) {
            val f = runCatching { TilesLayer.parseFocus(json) }.getOrNull() ?: return
            main.post { if (!isDestroyed) tiles?.focus(f) }
        }
        @JavascriptInterface fun tilesShow(show: Boolean, n: Int) { main.post { if (!isDestroyed) tiles?.show(show, n) } }
    }

    private fun tvApps(): List<ResolveInfo> = Tv.tvLaunchables().filter { it.activityInfo.packageName != packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    // ---------------------------------------------------------------- app tiles (same drawing as the computer-bridge launcher)

    private fun bannerImage(pkg: String): ByteArray? {
        banners[pkg]?.let { return it }
        val ri = tvApps().firstOrNull { it.activityInfo.packageName == pkg } ?: return null
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FLAP)
        val banner = sharp(ri.activityInfo.packageName, ri.activityInfo.bannerResource) ?: ri.activityInfo.loadBanner(packageManager)
            ?: ri.activityInfo.applicationInfo.loadBanner(packageManager)
        if (banner != null) { banner.setBounds(0, 0, 640, 360); banner.draw(c) }
        else iconTile(sharp(ri.activityInfo.packageName, ri.activityInfo.iconResource) ?: ri.loadIcon(packageManager), c)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it); bmp.recycle() }.toByteArray()
        banners[pkg] = bytes
        return bytes
    }

    private fun iconTile(icon: Drawable, c: Canvas) {
        val n = 432
        val sq = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sq)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && icon is AdaptiveIconDrawable) {
            icon.background?.apply { setBounds(0, 0, n, n); draw(sc) }; icon.foreground?.apply { setBounds(0, 0, n, n); draw(sc) }
        } else { icon.setBounds(0, 0, n, n); icon.draw(sc) }
        val edge = sq.getPixel(2, n / 2)
        c.drawColor(edge)
        val size = 220
        c.drawBitmap(sq, null, android.graphics.Rect(320 - size / 2, 180 - size / 2, 320 + size / 2, 180 + size / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        sq.recycle()
    }

    // The dock's app icons. Drawn at 256 px from the app's sharpest icon (xxxhdpi where it has one),
    // not the TV's own density: a TV runs at xhdpi, where most icons are 96 px, and those looked soft
    // stretched over a dock tile on a big 4K screen (issue #17).
    private fun iconImage(pkg: String): ByteArray? {
        icons[pkg]?.let { return it }
        val bmp = iconBitmap(pkg, 256) ?: return null
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
        icons[pkg] = bytes
        return bytes
    }

    /** An app's icon drawn at n x n: as a PNG for the page (iconImage), as it is for the app's own tiles (TilesLayer). */
    private fun iconBitmap(pkg: String, n: Int): Bitmap? {
        val ri = tvApps().firstOrNull { it.activityInfo.packageName == pkg }
        val d = ri?.let { sharp(pkg, it.activityInfo.iconResource) }
            ?: try { sharp(pkg, packageManager.getApplicationInfo(pkg, 0).icon) ?: packageManager.getApplicationIcon(pkg) } catch (e: Exception) { return null }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, n, n); d.draw(Canvas(bmp))
        return bmp
    }

    /** An app's drawable at the highest density it ships (null if it has none we can read). */
    private fun sharp(pkg: String, res: Int): Drawable? {
        if (res == 0) return null
        return try {
            packageManager.getResourcesForApplication(pkg).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        if (shown?.get() === this) shown = null
        detachTiles(); tiles?.destroy(); tiles = null
        web?.let { web = null; it.stopLoading(); it.destroy() }
        super.onDestroy()
    }

    companion object {
        @Volatile private var shown: java.lang.ref.WeakReference<HomeActivity>? = null

        /**
         * Shows settings saved by a phone or a setup tool straight away (#96). The home page reads
         * them again only in tv.resume(), when it comes back on screen, so after a save from
         * another device the dock and weather stayed as they were until Home was pressed.
         * Only while the home is on screen: behind another app, tv.resume() would start the
         * background video under it, and onResume reads everything again anyway. Saves that come
         * close together (a phone saving several things) make one refresh, not one each.
         */
        fun refresh() {
            refresher.removeCallbacks(refreshNow)
            refresher.postDelayed(refreshNow, 400)
        }

        private val refresher = Handler(Looper.getMainLooper())
        private val refreshNow = Runnable {
            val a = shown?.get()
            if (a != null && a.front && !a.isFinishing && !a.isDestroyed) a.js("window.tv && tv.resume()")
        }

        private val GROUND = Color.parseColor("#0D1E19")
        private val FLAP = Color.parseColor("#131614")
    }
}
