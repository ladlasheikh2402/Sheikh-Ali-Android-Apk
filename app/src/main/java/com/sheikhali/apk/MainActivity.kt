package com.sheikhali.apk

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

/** Same tokens as the website (site.css "Ivory & Ink": ink background, violet + pink accents). */
private object Ui {
    val INK = Color.parseColor("#070914")
    val CARD = Color.parseColor("#0F1330")
    val CARD2 = Color.parseColor("#181C3C")
    val LINE = Color.parseColor("#1FF1F0FF")
    val LINE2 = Color.parseColor("#3DF1F0FF")
    val TEXT = Color.parseColor("#F1F0FF")
    val MUTED = Color.parseColor("#A7A9C9")
    val VIOLET = Color.parseColor("#6D4AFF")
    val PINK = Color.parseColor("#FF4FA3")
    val RED = Color.parseColor("#FF5D6C")
}

/** Thin gradient progress bar (violet -> pink). Used for page loading and the download sheet. */
private class GradientBar(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var indeterminate = false
        set(v) {
            if (field == v) return
            field = v
            updateAnim()
            invalidate()
        }
    var failed = false
        set(v) { field = v; invalidate() }
    /** soft light sweeping over the filled part (download in progress) */
    var shimmer = false
        set(v) {
            if (field == v) return
            field = v
            updateAnim()
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1FF1F0FF") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun startAnim() {
        stopAnim()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() { anim?.cancel(); anim = null }

    private fun updateAnim() { if ((indeterminate || shimmer) && isAttachedToWindow) startAnim() else stopAnim() }

    override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnim()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        val left: Float
        val right: Float
        if (indeterminate) {
            val seg = w * 0.35f
            val x = (w + seg) * phase - seg
            left = x.coerceAtLeast(0f)
            right = (x + seg).coerceAtMost(w)
        } else {
            left = 0f
            right = w * fraction
        }
        if (right - left > 0.5f) {
            fill.shader = if (failed) LinearGradient(left, 0f, right, 0f, Ui.RED, Ui.PINK, Shader.TileMode.CLAMP)
            else LinearGradient(left, 0f, right, 0f, Ui.VIOLET, Ui.PINK, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(left, 0f, right, h, r, r, fill)
            if (shimmer && !indeterminate && !failed) {
                val band = (right - left) * 0.3f
                val x = (right - left + band) * phase - band + left
                val gl = x.coerceAtLeast(left)
                val gr = (x + band).coerceAtMost(right)
                if (gr - gl > 1f) {
                    glint.shader = LinearGradient(x, 0f, x + band, 0f,
                        intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(gl, 0f, gr, h, r, r, glint)
                }
            }
        }
    }
}

class MainActivity : AppCompatActivity() {

    // ═════════════ SETTINGS ═════════════
    private val startUrl = "https://android-web-apk.vercel.app/"
    /** Sites that open inside the app. Add more hosts here if needed. */
    private val internalHosts = listOf("android-web-apk.vercel.app")
    /** true = every http/https link opens inside the app; false = other websites open in the browser. */
    private val keepInsideApp = false
    /** Removes the "; wv" WebView marker from the user-agent (better for Google / GitHub login). */
    private val chromeLikeUserAgent = true
    /** Added to the user-agent so the website can detect the app (the site hides its "Install app" popup). */
    private val appUserAgentMarker = "LadlaSheikhApp/1.0"
    /** true = hide status + navigation bars (full-screen). false = normal app look with a themed status bar. */
    private val immersiveMode = false
    /** true = "Install" button after an .apk download. Set to false (and remove REQUEST_INSTALL_PACKAGES) for Google Play. */
    private val installApkFromApp = true
    /** true = inspect the page from a PC with chrome://inspect (debug only). */
    private val enableWebDebugging = false
    // ═══════════════════════════════════

    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var topBar: GradientBar
    private lateinit var fullscreenHolder: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())

    private var firstPaint = false
    private var showingError = false
    private var lastFailedUrl: String? = null
    private var clearHistoryNext = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var activeSheet: DownloadSheet? = null

    private var pullToRefreshOn = true      // off on the login / signup screens (their form scrolls inside the page)
    private var pageToken = 0
    private var blankRetries = 0
    private var chromeMajor = 0
    private var askedWebViewUpdate = false

    /** Old site versions ask the browser for an animated cross-page transition; this switches it off inside the app. */
    private val noViewTransitionJs =
        "(function(){if(document.getElementById('__novt'))return;var s=document.createElement('style');s.id='__novt';" +
            "s.textContent='@view-transition{navigation:none}';(document.head||document.documentElement).appendChild(s);})();"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            var uris: Array<Uri>? = null
            if (clipData != null) {
                uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (data?.data != null) {
                uris = arrayOf(data.data!!)
            }
            fileCallback?.onReceiveValue(uris)
        } else {
            fileCallback?.onReceiveValue(null)
        }
        fileCallback = null
    }

    // Android 6-9: storage permission for downloads
    private class PendingDownload(val url: String, val userAgent: String?, val contentDisposition: String?, val mimeType: String?)
    private var pendingDownload: PendingDownload? = null

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingDownload
        pendingDownload = null
        if (granted && p != null) enqueueDownload(p)
        else Toast.makeText(this, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
    }

    // ═════════════ URL helpers ═════════════
    private fun isInternal(host: String?): Boolean {
        if (host == null) return false
        return internalHosts.any { host == it || host.endsWith(".$it") }
    }

    private val strongAuthHosts = listOf(
        "firebaseapp.com", "accounts.google.com", "appleid.apple.com",
        "login.microsoftonline.com", "login.live.com"
    )
    private val weakAuthHosts = listOf("github.com", "facebook.com", "twitter.com", "x.com")

    /** Login pages (Firebase / Google / GitHub / Facebook ...) must stay inside the app. */
    private fun isAuthUrl(uri: Uri): Boolean {
        val h = uri.host?.lowercase() ?: return false
        val p = uri.path?.lowercase() ?: ""
        if (strongAuthHosts.any { h == it || h.endsWith(".$it") }) return true
        if (weakAuthHosts.any { h == it || h.endsWith(".$it") }) {
            return p.startsWith("/login") || p.startsWith("/sessions") || p.contains("oauth") || p.contains("/dialog")
        }
        return false
    }

    private val downloadExt = setOf("apk", "xapk", "apks", "zip", "rar", "7z", "tar", "gz", "exe", "msi", "iso", "obb")

    /** GitHub release files and archive / installer files are downloaded inside the app. */
    private fun isDownloadLink(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""
        if ((host == "github.com" || host == "www.github.com") && path.contains("/releases/download/")) return true
        if (host.endsWith("githubusercontent.com") &&
            (host.startsWith("objects.") || host.startsWith("release-assets.") || host.startsWith("github-releases."))
        ) return true
        val ext = path.substringAfterLast('.', "")
        return ext in downloadExt
    }

    private fun openExternal(uri: Uri) {
        try {
            if (uri.scheme == "intent") {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                startActivity(i)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            if (e is ActivityNotFoundException || e is java.net.URISyntaxException || e is SecurityException) {
                Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** true = this navigation was handled here (do not load it in the WebView). */
    private fun routeMainNavigation(uri: Uri): Boolean {
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (isDownloadLink(uri)) {
                    handleDownloadRequest(uri.toString(), web.settings.userAgentString, null, null)
                    return true
                }
                if (keepInsideApp || isInternal(uri.host) || isAuthUrl(uri)) return false
                openExternal(uri)
                return true
            }
            "blob", "data", "about", "javascript" -> return false
            "file" -> return !uri.toString().startsWith("file:///android_asset/")   // only our own offline page
            else -> {                       // mailto:, tel:, whatsapp:, tg:, intent: ...
                openExternal(uri)
                return true
            }
        }
    }

    // ═════════════ lifecycle ═════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val t0 = SystemClock.uptimeMillis()
        // keep the splash until the first page is visible (max 3.5 s) -> no blank screen while loading
        splash.setKeepOnScreenCondition { !firstPaint && SystemClock.uptimeMillis() - t0 < 3500 }

        frame = FrameLayout(this)
        frame.setBackgroundColor(Ui.INK)

        content = FrameLayout(this)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Ui.VIOLET, Ui.PINK)
            setProgressBackgroundColorSchemeColor(Ui.CARD)
            // WebView can be replaced (render crash recovery), so always ask the current one
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 || !pullToRefreshOn }
            setOnRefreshListener { web.reload() }
        }
        web = createWebView()
        swipeRefresh.addView(web)
        content.addView(swipeRefresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        topBar = GradientBar(this).apply { visibility = View.GONE }
        content.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        frame.addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        fullscreenHolder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        frame.addView(fullscreenHolder, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        setContentView(frame)

        // edge-to-edge (required from Android 15): the page sits between the status bar and the navigation bar / keyboard
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (immersiveMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (enableWebDebugging) WebView.setWebContentsDebuggingEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        watchNetwork()

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(deepLinkOrStart(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val d = intent.data
        if (d != null && (d.scheme == "https" || d.scheme == "http") && isInternal(d.host)) web.loadUrl(d.toString())
    }

    private fun deepLinkOrStart(i: Intent?): String {
        val d = i?.data
        return if (d != null && (d.scheme == "https" || d.scheme == "http") && isInternal(d.host)) d.toString() else startUrl
    }

    override fun onPause() {
        super.onPause()
        // like a browser tab in the background: stop the site's music / videos, resume when you come back
        try {
            web.evaluateJavascript(
                "(function(){var a=[].slice.call(document.querySelectorAll('audio,video')).filter(function(m){return !m.paused;});" +
                    "window.__wasPlaying=a;a.forEach(function(m){m.pause();});})();", null
            )
            web.onPause()
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try {
            web.onResume()
            web.evaluateJavascript(
                "(function(){(window.__wasPlaying||[]).forEach(function(m){var p=m.play();if(p&&p.catch)p.catch(function(){});});window.__wasPlaying=[];})();", null
            )
        } catch (e: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        try {
            netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        ui.removeCallbacksAndMessages(null)
        activeSheet?.dismiss()
        try { swipeRefresh.removeView(web) } catch (e: Exception) {}
        web.destroy()
        super.onDestroy()
    }

    // ═════════════ network: reload by itself when the connection comes back ═════════════
    private fun watchNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ui.post { if (showingError) retryLast() }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {}
    }

    private fun retryLast() {
        showingError = false
        clearHistoryNext = true            // drop the offline page from the back stack once the real page loads
        web.loadUrl(lastFailedUrl ?: startUrl)
    }

    private fun isLiteDevice(): Boolean {
        val lowRam = try { getSystemService(ActivityManager::class.java).isLowRamDevice } catch (e: Exception) { false }
        return lowRam || Build.VERSION.SDK_INT < 28 || (chromeMajor in 1..99)
    }

    /** Old Android System WebView (before v108) cannot draw modern pages properly: ask once to update it. */
    private fun maybeAskToUpdateWebView() {
        if (askedWebViewUpdate || chromeMajor == 0 || chromeMajor >= 108 || isFinishing) return
        askedWebViewUpdate = true
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("webview_prompt_for", 0) == chromeMajor) return
        prefs.edit().putInt("webview_prompt_for", chromeMajor).apply()
        showInfoSheet(
            "Update Android System WebView",
            "Your phone’s WebView is old (version $chromeMajor), so some pages may look broken or load slowly. " +
                "Updating it takes a minute and fixes that.",
            "Update"
        ) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                openExternal(Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))
            }
        }
    }

    /** Small themed message sheet (same look as the download sheet). */
    private fun showInfoSheet(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
        if (isFinishing) return
        val dialog = Dialog(this)
        val titleTv = TextView(this).apply {
            text = title; setTextColor(Ui.TEXT); textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val bodyTv = TextView(this).apply {
            text = body; setTextColor(Ui.MUTED); textSize = 13.5f
            setLineSpacing(0f, 1.25f)
        }
        val later = pillButton("Later", false)
        val action = pillButton(actionLabel, true)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(later)
            addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setColors(intArrayOf(Color.TRANSPARENT, Ui.VIOLET, Ui.PINK, Color.TRANSPARENT))
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(20))
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                setColors(intArrayOf(Ui.CARD2, Ui.CARD))
                cornerRadius = dpf(30f)
                setStroke(dp(1), Ui.LINE)
            }
            addView(glow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { marginStart = dp(14); marginEnd = dp(14) })
            addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(bodyTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        }
        val navBottom = ViewCompat.getRootWindowInsets(frame)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.bottom ?: 0
        val outer = FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12) + navBottom)
            addView(card, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.62f)
        }
        later.setOnClickListener { dialog.dismiss() }
        action.setOnClickListener { dialog.dismiss(); onAction() }
        dialog.show()
    }

    /** If a page "loaded" but drew nothing (solid colour screen): reload once, then show the error page. */
    private fun scheduleBlankCheck(url: String) {
        if (url.startsWith("about:")) return
        val token = ++pageToken
        ui.postDelayed({
            if (token != pageToken || showingError || isFinishing) return@postDelayed
            try {
                web.evaluateJavascript(
                    "(function(){var b=document.body;if(!b)return 0;var t=(b.innerText||'').trim().length;" +
                        "var h=document.documentElement.scrollHeight;return (t>20&&h>150)?1:0;})()"
                ) { r ->
                    if (token != pageToken) return@evaluateJavascript
                    if (r == "1") {
                        blankRetries = 0
                    } else if (blankRetries < 1) {
                        blankRetries++
                        web.reload()
                    } else {
                        blankRetries = 0
                        showErrorPage(web, url, "The page did not display. Check your connection and try again.")
                    }
                }
            } catch (e: Exception) {}
        }, 2200)
    }

    private fun showErrorPage(view: WebView, failedUrl: String?, detail: String) {
        showingError = true
        if (failedUrl != null && !failedUrl.startsWith("file:")) lastFailedUrl = failedUrl
        firstPaint = true
        topBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        view.loadUrl(
            "file:///android_asset/offline.html?u=" + Uri.encode(lastFailedUrl ?: startUrl) + "&e=" + Uri.encode(detail)
        )
    }

    // ═════════════ main WebView ═════════════
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Ui.INK)
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false              // the offline page is loaded from android_asset (not affected)
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setSupportMultipleWindows(true)      // Firebase / Google / GitHub login popups
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100                       // the phone's font-size setting must not break the layout
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            val defaultUa = userAgentString
            chromeMajor = Regex("Chrome/(\\d+)").find(defaultUa)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var ua = defaultUa
            if (chromeLikeUserAgent) ua = ua.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            // " lite" tells the website to switch off heavy effects on weak phones / old WebViews
            userAgentString = "$ua $appUserAgentMarker" + if (isLiteDevice()) " lite" else ""
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)   // Firebase auth needs it
        }

        w.addJavascriptInterface(AppBridge(), "AndroidDownloader")
        w.addJavascriptInterface(AppBridge(), "AndroidApp")

        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownloadRequest(url, userAgent, contentDisposition, mimeType)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return routeMainNavigation(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                topBar.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                firstPaint = true
                if (url != null && !url.startsWith("file:")) view?.evaluateJavascript(noViewTransitionJs, null)
            }

            // fires for every page AND every #/login style route change
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val u = url ?: ""
                val onAuth = u.contains("#/login") || u.contains("#/signup") || u.contains("/login.html") || u.contains("/signup.html")
                pullToRefreshOn = !onAuth
                swipeRefresh.isEnabled = pullToRefreshOn
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                topBar.visibility = View.GONE
                firstPaint = true
                val isOffline = url?.startsWith("file:///android_asset/offline") == true
                if (!isOffline && url != null) {
                    showingError = false
                    if (clearHistoryNext) { clearHistoryNext = false; view?.clearHistory() }
                    view?.evaluateJavascript(noViewTransitionJs, null)
                    scheduleBlankCheck(url)
                    ui.postDelayed({ maybeAskToUpdateWebView() }, 1800)
                }
                web.evaluateJavascript(
                    """
                    (function() {
                        if (window.hasDownloaderInstalled) return;
                        window.hasDownloaderInstalled = true;
                        document.addEventListener('click', function(e) {
                            var target = e.target.closest('a');
                            if (target && target.hasAttribute('download') && target.href.startsWith('blob:')) {
                                e.preventDefault();
                                fetch(target.href)
                                    .then(r => r.blob())
                                    .then(blob => {
                                        var reader = new FileReader();
                                        reader.onload = function() {
                                            var base64 = reader.result.split(',')[1];
                                            var filename = target.getAttribute('download') || 'download';
                                            AndroidDownloader.downloadBase64(filename, base64);
                                        };
                                        reader.readAsDataURL(blob);
                                    });
                            }
                        });
                    })();
                    """.trimIndent(), null
                )
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val desc = error.description?.toString() ?: ""
                if (desc.contains("ERR_ABORTED")) return
                showErrorPage(view, request.url.toString(), "$desc (${error.errorCode})")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    showErrorPage(view, request.url.toString(), "Server error ${errorResponse.statusCode}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showErrorPage(view, error.url, "Secure connection failed")
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreateWebView()      // the page process crashed: start a fresh WebView instead of a blank screen
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                topBar.fraction = newProgress / 100f
                if (newProgress >= 100) topBar.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                return try {
                    chooser.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                return openPopupWindow(resultMsg)
            }

            // full-screen video (course lessons, YouTube embeds)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreenHolder.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                fullscreenHolder.visibility = View.VISIBLE
                content.visibility = View.INVISIBLE
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
        return w
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreenHolder.removeView(v)
        fullscreenHolder.visibility = View.GONE
        content.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        if (!immersiveMode) WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun recreateWebView() {
        try {
            swipeRefresh.removeView(web)
            web.destroy()
        } catch (e: Exception) {}
        web = createWebView()
        swipeRefresh.addView(web)
        Toast.makeText(this, "Reloading page…", Toast.LENGTH_SHORT).show()
        web.loadUrl(startUrl)
    }

    // ═════════════ login popups (Firebase / Google / GitHub) ═════════════
    private fun openPopupWindow(resultMsg: Message): Boolean {
        val popup = WebView(this)
        popup.setBackgroundColor(Ui.INK)
        popup.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = web.settings.userAgentString
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        val hostTv = TextView(this).apply {
            setTextColor(Ui.MUTED); textSize = 12.5f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            text = ""
        }
        val closeTv = TextView(this).apply {
            text = "Close"; setTextColor(Ui.TEXT); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple(GradientDrawable().apply { cornerRadius = dpf(18f); setColor(Color.TRANSPARENT) })
        }
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.CARD)
            setPadding(dp(16), dp(6), dp(8), dp(6))
            addView(hostTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeTv)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.INK)
            addView(barRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(popup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.setBackgroundDrawable(ColorDrawable(Ui.INK))
        }
        dialog.setOnDismissListener { try { popup.destroy() } catch (e: Exception) {} }

        var approved = false   // true = a login popup, shown in the dialog
        var closed = false

        fun showOnce() {
            if (!approved && !closed) {
                approved = true
                if (!isFinishing) dialog.show()
            }
        }
        fun closePopup() {
            if (closed) return
            closed = true
            ui.post { try { if (dialog.isShowing) dialog.dismiss() else popup.destroy() } catch (e: Exception) {} }
        }
        closeTv.setOnClickListener { closePopup() }

        /** true = navigation handled elsewhere, cancel it in the popup */
        fun route(uri: Uri): Boolean {
            if (closed) return true
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript") return false
                openExternal(uri)
                if (!approved) closePopup()
                return true
            }
            if (isDownloadLink(uri)) {
                handleDownloadRequest(uri.toString(), popup.settings.userAgentString, null, null)
                if (!approved) closePopup()
                return true
            }
            if (approved) return false
            return when {
                isAuthUrl(uri) -> { showOnce(); false }
                keepInsideApp || isInternal(uri.host) -> {
                    web.loadUrl(uri.toString())          // a page of our own site -> main WebView
                    closePopup()
                    true
                }
                else -> {
                    openExternal(uri)                    // another website -> browser
                    closePopup()
                    true
                }
            }
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return route(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) hostTv.text = Uri.parse(url).host ?: ""
                if (!approved && !closed && url != null && url != "about:blank") {
                    if (route(Uri.parse(url))) view?.stopLoading()
                }
            }
        }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { closePopup() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                return openPopupWindow(resultMsg)
            }
        }

        ui.postDelayed({ if (!approved && !closed && !isFinishing) showOnce() }, 1500)

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    // ═════════════ downloads ═════════════
    private fun handleDownloadRequest(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Please try this download again", Toast.LENGTH_SHORT).show()
            return
        }
        val p = PendingDownload(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = p
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(p)
    }

    private fun enqueueDownload(p: PendingDownload) {
        try {
            val fileName = URLUtil.guessFileName(p.url, p.contentDisposition, p.mimeType)
            val req = DownloadManager.Request(Uri.parse(p.url)).apply {
                setTitle(fileName)
                setDescription("Downloading…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val mt = p.mimeType
                if (!mt.isNullOrBlank() && mt != "application/octet-stream") setMimeType(mt)
                if (!p.userAgent.isNullOrBlank()) addRequestHeader("User-Agent", p.userAgent)
                CookieManager.getInstance().getCookie(p.url)?.let { addRequestHeader("Cookie", it) }
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            activeSheet?.dismiss()
            if (!isFinishing) {
                val sheet = DownloadSheet(dm, id, fileName) { enqueueDownload(p) }
                activeSheet = sheet
                sheet.show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not start the download", Toast.LENGTH_LONG).show()
        }
    }

    private fun ripple(shape: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)

    private fun pillButton(label: String, filled: Boolean): TextView = TextView(this).apply {
        text = label
        setTextColor(if (filled) Color.WHITE else Ui.TEXT)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isClickable = true
        isFocusable = true
        val shape = GradientDrawable().apply {
            cornerRadius = dpf(26f)
            if (filled) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setColors(intArrayOf(Ui.VIOLET, Ui.PINK))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Ui.LINE2)
            }
        }
        background = ripple(shape)
    }

    /** Bottom sheet in the website's theme: file type badge, progress, speed + time left, Hide / Cancel, then Install / Open. */
    private inner class DownloadSheet(
        private val dm: DownloadManager,
        private val id: Long,
        private val fileName: String,
        private val onRetry: () -> Unit
    ) {
        private val dialog = Dialog(this@MainActivity)
        private val tile = FrameLayout(this@MainActivity)
        private val tileIcon = ImageView(this@MainActivity)
        private val extTv = TextView(this@MainActivity)
        private val titleTv = TextView(this@MainActivity)
        private val nameTv = TextView(this@MainActivity)
        private val pctTv = TextView(this@MainActivity)
        private val bar = GradientBar(this@MainActivity)
        private val statusTv = TextView(this@MainActivity)
        private val metaTv = TextView(this@MainActivity)
        private val hideBtn = pillButton("Hide", false)
        private val cancelBtn = pillButton("Cancel", false)
        private val mainBtn = pillButton("Open", true)
        private var pulse: ObjectAnimator? = null
        private var lastBytes = 0L
        private var lastTime = 0L
        private var speed = 0.0            // bytes per millisecond (smoothed)

        private val poll = object : Runnable {
            override fun run() {
                var again = true
                try {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) { again = false; return@use }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> { showComplete(total.takeIf { it > 0 } ?: got); again = false }
                            DownloadManager.STATUS_FAILED -> { showFailed(); again = false }
                            DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                                bar.indeterminate = true
                                pctTv.text = ""
                                metaTv.text = ""
                                statusTv.text = "Waiting for network…"
                            }
                            else -> showProgress(got, total)
                        }
                    }
                } catch (e: Exception) {
                    again = false
                }
                if (again && dialog.isShowing) ui.postDelayed(this, 450)
            }
        }

        init {
            val pad = dp(20)
            val ext = fileName.substringAfterLast('.', "").uppercase().take(4)

            tile.background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                setColors(intArrayOf(Ui.VIOLET, Ui.PINK))
                cornerRadius = dpf(18f)
            }
            extTv.apply {
                text = if (ext.isEmpty()) "FILE" else ext
                setTextColor(Color.WHITE); textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            tileIcon.visibility = View.GONE
            tile.addView(extTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            tile.addView(tileIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                tile,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f)
            ).apply { duration = 750; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE }

            titleTv.apply {
                text = "Downloading"
                setTextColor(Ui.TEXT); textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            nameTv.apply {
                text = fileName
                setTextColor(Ui.MUTED); textSize = 13f
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            pctTv.apply {
                setTextColor(Ui.PINK); textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            statusTv.apply { text = "Starting…"; setTextColor(Ui.MUTED); textSize = 12.5f }
            metaTv.apply { setTextColor(Ui.TEXT); textSize = 12.5f; gravity = Gravity.END }
            bar.indeterminate = true
            bar.shimmer = true

            val glow = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    setColors(intArrayOf(Color.TRANSPARENT, Ui.VIOLET, Ui.PINK, Color.TRANSPARENT))
                    cornerRadius = dpf(1f)
                }
            }
            val handle = View(this@MainActivity).apply {
                background = GradientDrawable().apply { cornerRadius = dpf(2f); setColor(Ui.LINE2) }
            }

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv)
                addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tile, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(10) })
                addView(pctTv)
            }
            val infoRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(metaTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            }

            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(hideBtn)
                addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                addView(mainBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            }
            mainBtn.visibility = View.GONE

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(10), pad, pad)
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    setColors(intArrayOf(Ui.CARD2, Ui.CARD))
                    cornerRadius = dpf(30f)
                    setStroke(dp(1), Ui.LINE)
                }
                addView(glow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { marginStart = dp(14); marginEnd = dp(14) })
                addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(16) })
                addView(header)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(18) })
                addView(infoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            }

            val outer = FrameLayout(this@MainActivity).apply {
                addView(card, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            val navBottom = ViewCompat.getRootWindowInsets(frame)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.bottom ?: 0
            outer.setPadding(dp(12), dp(12), dp(12), dp(12) + navBottom)

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(outer)
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(android.R.style.Animation_InputMethod)
                setDimAmount(0.62f)
            }
            dialog.setOnDismissListener {
                ui.removeCallbacks(poll)
                pulse?.cancel()
                if (activeSheet === this) activeSheet = null
            }

            hideBtn.setOnClickListener { dialog.dismiss() }       // the download keeps running (notification)
            cancelBtn.setOnClickListener {
                try { dm.remove(id) } catch (e: Exception) {}
                Toast.makeText(this@MainActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        fun show() {
            dialog.show()
            pulse?.start()
            ui.post(poll)
        }

        fun dismiss() {
            ui.removeCallbacks(poll)
            pulse?.cancel()
            if (dialog.isShowing) dialog.dismiss()
        }

        private fun eta(sec: Long): String = when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        }

        private fun showProgress(got: Long, total: Long) {
            val now = SystemClock.elapsedRealtime()
            if (lastTime > 0 && now > lastTime && got >= lastBytes) {
                val inst = (got - lastBytes).toDouble() / (now - lastTime)
                speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
            }
            lastBytes = got
            lastTime = now

            val perSec = (speed * 1000).toLong()
            val speedTxt = if (perSec > 0) "${Formatter.formatShortFileSize(this@MainActivity, perSec)}/s" else ""

            if (total > 0) {
                val f = got.toFloat() / total.toFloat()
                bar.indeterminate = false
                bar.fraction = f
                pctTv.text = "${(f * 100).toInt()}%"
                statusTv.text = "${Formatter.formatShortFileSize(this@MainActivity, got)} of ${Formatter.formatShortFileSize(this@MainActivity, total)}"
                metaTv.text = if (perSec > 0) "$speedTxt · ${eta(((total - got) / perSec).coerceAtLeast(1))} left" else ""
            } else {
                bar.indeterminate = true
                pctTv.text = ""
                statusTv.text = Formatter.formatShortFileSize(this@MainActivity, got)
                metaTv.text = speedTxt
            }
        }

        private fun showComplete(size: Long) {
            pulse?.cancel()
            bar.shimmer = false
            bar.indeterminate = false
            bar.failed = false
            bar.fraction = 1f
            pctTv.text = ""
            metaTv.text = ""
            extTv.visibility = View.GONE
            tileIcon.visibility = View.VISIBLE
            tileIcon.setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_check))
            tile.scaleX = 0.55f; tile.scaleY = 0.55f           // little "pop" when the download finishes
            tile.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2.4f)).start()
            titleTv.text = "Download complete"
            statusTv.text = "${Formatter.formatShortFileSize(this@MainActivity, size)} · Saved to Downloads"
            hideBtn.text = "Close"
            cancelBtn.text = "Downloads"
            cancelBtn.setOnClickListener {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                dialog.dismiss()
            }
            val isApk = fileName.lowercase().endsWith(".apk")
            mainBtn.text = if (isApk && installApkFromApp) "Install" else "Open"
            mainBtn.visibility = View.VISIBLE
            mainBtn.setOnClickListener {
                if (openDownloaded(dm, id, fileName)) dialog.dismiss()
            }
            mainBtn.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }

        private fun showFailed() {
            pulse?.cancel(); tile.scaleX = 1f; tile.scaleY = 1f
            bar.shimmer = false
            bar.indeterminate = false
            bar.failed = true
            bar.fraction = 1f
            pctTv.text = ""
            metaTv.text = ""
            tile.background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                setColors(intArrayOf(Ui.RED, Ui.PINK))
                cornerRadius = dpf(18f)
            }
            extTv.visibility = View.GONE
            tileIcon.visibility = View.VISIBLE
            tileIcon.setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_error))
            titleTv.text = "Download failed"
            statusTv.text = "Check your connection and try again"
            hideBtn.text = "Close"
            cancelBtn.visibility = View.GONE
            mainBtn.text = "Retry"
            mainBtn.visibility = View.VISIBLE
            mainBtn.setOnClickListener { dialog.dismiss(); onRetry() }
        }
    }

    /** returns true when the file was handed to another app (the sheet can close) */
    private fun openDownloaded(dm: DownloadManager, id: Long, fileName: String): Boolean {
        val isApk = fileName.lowercase().endsWith(".apk")
        if (isApk && installApkFromApp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            // like Chrome: ask once to allow installs from this app, then tap Install again
            Toast.makeText(this, "Allow installs from this app, then tap Install again", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {}
            return false
        }
        return try {
            val uri = dm.getUriForDownloadedFile(id) ?: throw IllegalStateException("no uri")
            var mime = dm.getMimeTypeForDownloadedFile(id)
            if (isApk) mime = "application/vnd.android.package-archive"
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            true
        } catch (e: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) {
                Toast.makeText(this, "The file is saved in your Downloads folder", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    // ═════════════ JavaScript bridge (website / offline page -> app) ═════════════
    inner class AppBridge {
        /** blob: downloads created by the website's own JS */
        @JavascriptInterface
        fun downloadBase64(filename: String, base64Data: String) {
            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        val outputStream = contentResolver.openOutputStream(uri)
                        outputStream?.write(decodedBytes)
                        outputStream?.close()
                        runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show() }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, filename)
                    val fos = FileOutputStream(file)
                    fos.write(decodedBytes)
                    fos.close()
                    runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show() }
            }
        }

        /** offline.html: open a page of our own site and drop the offline page from the back stack */
        @JavascriptInterface
        fun open(url: String) {
            val uri = Uri.parse(url)
            if (uri.scheme == "https" && isInternal(uri.host)) {
                runOnUiThread {
                    showingError = false
                    clearHistoryNext = true
                    web.loadUrl(url)
                }
            }
        }

        /** "Try again" button of offline.html */
        @JavascriptInterface
        fun retry() {
            runOnUiThread { retryLast() }
        }
    }
}
