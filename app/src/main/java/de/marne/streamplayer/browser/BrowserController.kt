package de.marne.streamplayer.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Der Browser. Eine einzige WebView, die so lange lebt wie die Activity
 * (configChanges im Manifest: Drehen lädt nichts neu).
 *
 * Erkennung auf zwei Ebenen, wie in der Analyse beschrieben:
 *  - unter der Seite: shouldInterceptRequest sieht jede Anfrage, auch aus iframes
 *  - in der Seite: detect.js meldet über einen WebMessageListener
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    context: Context,
    private val hub: DetectionHub,
    private val settings: Settings,
) {
    private val script: String = context.assets.open("detect.js").bufferedReader().use { it.readText() }
    private val docStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /** Startseite statt WebView zeigen (nichts geladen oder „Start“ gedrückt). */
    private val _showStart = MutableStateFlow(true)
    val showStart: StateFlow<Boolean> = _showStart.asStateFlow()

    /** Vollbild-Video der Seite (onShowCustomView). */
    private val _fullscreen = MutableStateFlow<View?>(null)
    val fullscreen: StateFlow<View?> = _fullscreen.asStateFlow()
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Adresse der Hauptseite für Hintergrund-Threads (WebView.url ist nur im Hauptthread erlaubt). */
    @Volatile
    private var currentPage: String = ""

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Pop-ups öffnen nicht in neuen Fenstern, sondern gar nicht – ruhiger und ohne Werbefenster.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            hub.report(url, Source.DOWNLOAD, mime = mimetype, referer = currentPage, title = name)
        }
    }

    /** Einmal im Hauptthread gelesen; Prober und Downloads brauchen ihn aus Hintergrund-Threads. */
    val userAgent: String = webView.settings.userAgentString

    init {
        installBridge()
    }

    private fun installBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*")) { _, message, _, isMainFrame, _ ->
                onBridgeMessage(message.data ?: return@addWebMessageListener, isMainFrame)
            }
        }
        if (docStartScript) {
            // Jeder Frame, jede Herkunft, vor dem ersten Skript der Seite.
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    private fun onBridgeMessage(data: String, isMainFrame: Boolean) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (o.optString("t")) {
            "media" -> {
                val source = when (o.optString("src")) {
                    "play" -> Source.PLAY
                    "xhr" -> Source.XHR
                    "ld" -> Source.LD
                    else -> Source.DOM
                }
                hub.report(
                    url = o.optString("url"),
                    source = source,
                    mime = o.optString("mime").ifBlank { null },
                    referer = o.optString("frame").ifBlank { null },
                    title = o.optString("title").ifBlank { null },
                    poster = o.optString("poster").ifBlank { null },
                )
            }
            "meta" -> if (isMainFrame) hub.updateMeta(o.optString("title"), o.optString("image").ifBlank { null })
        }
    }

    // ---------- Steuerung ----------

    fun load(input: String) {
        val target = toUrl(input) ?: return
        _showStart.value = false
        webView.loadUrl(target)
    }

    fun showStart() {
        _showStart.value = true
    }

    fun resumePage() {
        if (webView.url != null) _showStart.value = false
    }

    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()

    /** true, wenn der Zurück-Schritt verbraucht wurde. */
    fun back(): Boolean {
        if (_fullscreen.value != null) {
            exitFullscreen()
            return true
        }
        if (!_showStart.value && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        if (!_showStart.value) {
            _showStart.value = true
            return true
        }
        return false
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        _fullscreen.value = null
    }

    fun onPause() = webView.onPause()
    fun onResume() = webView.onResume()

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    fun clearBrowsingData() {
        webView.clearHistory()
        webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    // ---------- WebView-Rückrufe ----------

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            val scheme = u.scheme
            if (scheme == "http" || scheme == "https") {
                val referer = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"]
                hub.report(u.toString(), Source.NETWORK, referer = referer ?: currentPage)
            }
            return null // Die Seite lädt alles selbst; wir schauen nur zu.
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            return when (u.scheme) {
                "http", "https" -> false
                "intent" -> {
                    // Nur auf Fingertipp in eine andere App wechseln, nie per automatischer Weiterleitung.
                    if (request.hasGesture()) openIntentUri(view.context, u)
                    true
                }
                else -> {
                    if (request.hasGesture()) openExternal(view.context, u)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentPage = url
            hub.reset(url)
            _url.value = url
            _loading.value = true
            _showStart.value = false
            if (!docStartScript) view.evaluateJavascript(script, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            _loading.value = false
            _canGoBack.value = view.canGoBack()
            if (!docStartScript) view.evaluateJavascript(script, null)
            val t = view.title.orEmpty()
            settings.recordVisit(url, t)
            settings.lastUrl = url
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentPage = url
            hub.reset(url)
            _url.value = url
            _canGoBack.value = view.canGoBack()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _progress.value = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            _title.value = title.orEmpty()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = callback
            _fullscreen.value = view
        }

        override fun onHideCustomView() {
            fullscreenCallback = null
            _fullscreen.value = null
        }
    }

    private fun openIntentUri(context: Context, uri: Uri) {
        val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            intent.getStringExtra("browser_fallback_url")?.let { webView.loadUrl(it) }
        }
    }

    private fun openExternal(context: Context, uri: Uri) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        private const val BRIDGE = "streamplayerBridge"

        /** Adresse oder Suchbegriff → ladbare URL. */
        fun toUrl(input: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
            val looksLikeHost = !s.contains(' ') && s.contains('.') && !s.endsWith('.')
            return if (looksLikeHost) "https://$s"
            else "https://duckduckgo.com/?q=" + Uri.encode(s)
        }
    }
}
