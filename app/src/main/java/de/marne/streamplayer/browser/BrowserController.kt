package de.marne.streamplayer.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
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
import de.marne.streamplayer.adblock.AdBlocker
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

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
    private val adblock: AdBlocker,
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

    /** Rückkanäle in alle Frames der aktuellen Seite (auch iframes fremder Hosts). */
    private val frames = java.util.concurrent.CopyOnWriteArrayList<androidx.webkit.JavaScriptReplyProxy>()

    /**
     * Die Seite zeigt eine Prüfung („Bist du ein Mensch?“) – Cloudflare, Turnstile, hCaptcha,
     * reCAPTCHA. Gemeldet von detect.js je Frame; vorbei, wenn alle melden „weg“ oder eine neue
     * Hauptseite lädt.
     */
    private val challengeFrames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _challenge = MutableStateFlow(false)
    val challenge: StateFlow<Boolean> = _challenge.asStateFlow()

    private fun updateChallenge() {
        val on = challengeFrames.isNotEmpty()
        // Gelöst: Freigabe-Cookie sofort auf die Platte, sonst fragt die Seite nach einem
        // Neustart der App wieder – Player und Downloads schicken dieselben Cookies mit.
        if (_challenge.value && !on) CookieManager.getInstance().flush()
        _challenge.value = on
    }

    /** Videos auf der Seite stumm starten: Manche Player verraten ihre Adresse erst beim Abspielen. */
    fun nudgePlayback() = postToFrames("{\"t\":\"nudge\"}")

    /** Alles auf der Seite anhalten, bevor der eigene Player übernimmt. */
    fun pausePage() = postToFrames("{\"t\":\"pause\"}")

    private fun postToFrames(msg: String) {
        frames.forEach { runCatching { it.postMessage(msg) } }
    }

    /** Für die Suche im Modus „Nur ansehen“: Wiedergabe ohne Fingertipp erlauben. */
    fun setAutoplay(allowed: Boolean) {
        webView.settings.mediaPlaybackRequiresUserGesture = !allowed
    }

    /** Blockierte Anfragen, Popups und Umleitungen auf der aktuellen Seite. */
    private val blockedCounter = AtomicInteger(0)
    private val _blocked = MutableStateFlow(0)
    val blocked: StateFlow<Int> = _blocked.asStateFlow()

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
            // Neue Fenster laufen über onCreateWindow: nur echte Link-Tipps öffnen etwas,
            // und zwar im selben Fenster. Popunder-Werbung bleibt draußen.
            setSupportMultipleWindows(true)
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
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*")) { _, message, _, isMainFrame, reply ->
                val data = message.data ?: return@addWebMessageListener
                // Jeder Frame meldet sich mit „hello“; über seinen Rückkanal können wir ihn später ansprechen.
                if (data.contains("\"hello\"")) frames += reply
                val answer = onBridgeMessage(data, isMainFrame)
                if (answer != null) reply.postMessage(answer)
            }
        }
        if (docStartScript) {
            // Jeder Frame, jede Herkunft, vor dem ersten Skript der Seite.
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    /** Verarbeitet eine Nachricht aus der Seite; Rückgabe geht als Antwort an genau diesen Frame. */
    private fun onBridgeMessage(data: String, isMainFrame: Boolean): String? {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
        when (o.optString("t")) {
            "hello" -> {
                // Ausblende-Regeln für diesen Frame (Werbeflächen verstecken).
                val css = adblock.cssFor(o.optString("url"), isMainFrame, currentPage) ?: return null
                return JSONObject().put("t", "css").put("css", css).toString()
            }
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
            "sub" -> hub.reportSubtitle(
                o.optString("url"),
                lang = o.optString("lang").ifBlank { null },
                label = o.optString("label").ifBlank { null },
                referer = o.optString("frame").ifBlank { null },
            )
            "challenge" -> {
                val id = o.optString("id")
                if (o.optBoolean("on")) challengeFrames += id else challengeFrames -= id
                updateChallenge()
            }
            "meta" -> if (isMainFrame) hub.updateMeta(o.optString("title"), o.optString("image").ifBlank { null })
        }
        return null
    }

    private fun countBlocked() {
        _blocked.value = blockedCounter.incrementAndGet()
    }

    /** Host der aktuellen Seite ohne www., für die Ausnahmeliste. */
    val pageHost: String? get() = de.marne.streamplayer.adblock.RuleSet.hostOf(currentPage)?.removePrefix("www.")

    /** Werbeblocker auf dieser Seite an/aus, dann neu laden. */
    fun toggleAllowCurrentPage() {
        val host = pageHost ?: return
        val allowed = settings.allowlist.value.any { host == it || host.endsWith(".$it") }
        if (allowed) {
            settings.allowlist.value.filter { host == it || host.endsWith(".$it") }.forEach { settings.setAllowed(it, false) }
        } else {
            settings.setAllowed(host, true)
        }
        webView.reload()
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
                val url = u.toString()
                if (request.isForMainFrame) {
                    frames.clear()
                    // Neue Hauptseite: Zähler, Funde und Seitenkontext hier zurücksetzen – im selben
                    // Thread und vor allen Unter-Anfragen. onPageStarted kommt erst später im
                    // Hauptthread an; bis dahin wären frühe Anfragen schon der alten Seite zugerechnet.
                    if (UrlKey.of(url) != UrlKey.of(currentPage)) {
                        blockedCounter.set(0)
                        _blocked.value = 0
                    }
                    currentPage = url
                    hub.reset(url)
                    // Neue Hauptseite: alte Prüf-Frames gibt es nicht mehr (pagehide kommt nicht immer an).
                    if (challengeFrames.isNotEmpty()) {
                        challengeFrames.clear()
                        updateChallenge()
                    }
                }
                // Werbung zuerst: was blockiert ist, taucht auch nicht als „gefundenes Video“ auf.
                if (!request.isForMainFrame && adblock.shouldBlock(url, currentPage)) {
                    countBlocked()
                    return blockedResponse()
                }
                val referer = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"]
                // Untertitel-Dateien merken; Teilstücke von HLS-Untertiteln (seg-12.vtt) nicht – die
                // bringt der Stream selbst mit.
                if (de.marne.streamplayer.subtitles.Subtitles.looksLike(url) && !de.marne.streamplayer.detect.UrlClassifier.looksLikeSegment(url)) {
                    hub.reportSubtitle(url, referer = referer ?: currentPage)
                }
                hub.report(url, Source.NETWORK, referer = referer ?: currentPage)
            }
            return null // Die Seite lädt alles selbst; wir schauen nur zu.
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            return when (u.scheme) {
                "http", "https" -> {
                    // Umleitung der ganzen Seite auf Werbung (ohne Fingertipp oder Popup-Ziel): verhindern.
                    val stop = request.isForMainFrame && adblock.blocksNavigation(u.toString(), request.hasGesture(), currentPage)
                    if (stop) countBlocked()
                    stop
                }
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

        /**
         * Neues Fenster gewünscht (target=_blank, window.open). Erlaubt nur, wenn der Finger
         * wirklich auf einem Link lag; dann öffnet das Ziel im selben Fenster. Alles andere –
         * Popunder beim ersten Tipp irgendwohin – fällt weg.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val hit = view.hitTestResult.type
            val onLink = hit == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            val strict = adblock.activeFor(pageHost)
            if (!isUserGesture || (strict && !onLink)) {
                if (strict) countBlocked()
                return false
            }
            // Ziel abfangen: ein Wegwerf-WebView nimmt die erste Adresse entgegen.
            val catcher = WebView(view.context)
            var handled = false
            fun take(target: String) {
                if (handled) return
                handled = true
                view.post { catcher.destroy() }
                if (adblock.blocksPopup(target, currentPage)) countBlocked() else webView.loadUrl(target)
            }
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    take(r.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    if (url.startsWith("http")) take(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
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

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private object UrlKey {
        fun of(url: String) = url.substringBefore('#')
    }

    companion object {
        private const val BRIDGE = "streamplayerBridge"

        /** Adresse statt Suchbegriff? */
        fun looksLikeUrl(input: String): Boolean {
            val s = input.trim()
            return s.startsWith("http://", true) || s.startsWith("https://", true) ||
                (!s.contains(' ') && s.contains('.') && !s.endsWith('.'))
        }

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
