package de.marne.streamplayer.ios.play

import de.marne.streamplayer.ui.nowMillis
import de.marne.streamplayer.ui.res.Res
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * Weiche für detect.js aus der Android-App: window.streamplayerBridge wie in der Android-WebView,
 * Nachrichten der App („anstoßen“, „anhalten“) per postMessage an alle Frames. Dazu, weil WKWebView
 * Netzwerk-Anfragen nicht abfangen kann: Resource Timing meldet geladene Adressen mit Video-Endung.
 */
private const val SHIM = """
(function () {
  var b = {
    postMessage: function (s) { try { window.webkit.messageHandlers.streamplayer.postMessage(String(s)); } catch (e) {} },
    onmessage: null
  };
  window.streamplayerBridge = b;
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || !d.__sp) return;
    try { if (b.onmessage) b.onmessage({ data: d.__sp }); } catch (x) {}
    var fs = document.querySelectorAll('iframe');
    for (var i = 0; i < fs.length; i++) { try { fs[i].contentWindow.postMessage(d, '*'); } catch (x) {} }
  });
  var re = /\.(m3u8|mpd|mp4|m4v|webm|mov)(\?|#|$)/i;
  function check(en) {
    if (re.test(en.name)) b.postMessage(JSON.stringify({ t: 'media', url: en.name, src: 'perf', frame: location.href }));
  }
  try { new PerformanceObserver(function (l) { l.getEntries().forEach(check); }).observe({ type: 'resource', buffered: true }); } catch (e) {}
})();
"""

/**
 * Sucht das Video einer Seite wie der Modus „Nur ansehen“ auf Android (WatchController): Seite
 * laden, detect.js fragen, Funde prüfen, kurz weitersammeln, notfalls stumm anstoßen, das
 * Hauptvideo wählen. Die WebView muss dafür auf dem Bildschirm liegen (unter der Suchansicht).
 */
internal class Resolver(private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Searching(val step: String, val phase: Int) : State
        data class Ready(val found: Found) : State
        data object NotFound : State
        data object Protected : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val found = LinkedHashMap<String, Found>()
    private var loaded = false
    private var job: Job? = null
    private var pageHost: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val handler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val body = didReceiveScriptMessage.body as? String ?: return
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
            if ((o["t"] as? JsonPrimitive)?.content != "media") return
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            val url = s("url") ?: return
            if (!url.startsWith("http")) return
            val old = found[url]
            found[url] = (old ?: Found(url, Probe.classify(url, s("mime")), referer = s("frame"))).let {
                it.copy(sources = it.sources + (s("src") ?: "?"), title = it.title ?: s("title"), poster = it.poster ?: s("poster"))
            }
            if (old == null) scope.launch {
                val checked = Probe.check(found[url] ?: return@launch)
                found[url] = checked.copy(sources = found[url]?.sources ?: checked.sources)
            }
        }
    }

    /** Werbung leitet gern auf fremde Seiten um: oben nur auf derselben Seite bleiben. */
    private val navigation = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val target = decidePolicyForNavigationAction.request.URL?.host
            val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: false
            val allow = !mainFrame || target == null || pageHost == null || site(target) == site(pageHost!!)
            decisionHandler(if (allow) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            loaded = true
        }
    }

    private fun site(host: String) = host.removePrefix("www.").split('.').takeLast(2).joinToString(".")

    private var detectJs: String? = null
    private var detectAdded = false

    val webView: WKWebView by lazy {
        val cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.allowsPictureInPictureMediaPlayback = false
        cfg.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
        val ucc = cfg.userContentController
        ucc.addScriptMessageHandler(handler, name = "streamplayer")
        val start = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart
        ucc.addUserScript(WKUserScript(source = SHIM, injectionTime = start, forMainFrameOnly = false))
        detectJs?.let { ucc.addUserScript(WKUserScript(source = it, injectionTime = start, forMainFrameOnly = false)); detectAdded = true }
        WKWebView(frame = CGRectMake(0.0, 0.0, 800.0, 600.0), configuration = cfg).also {
            it.navigationDelegate = navigation
            it.customUserAgent = SAFARI_UA
        }
    }

    private fun send(msg: String) {
        webView.evaluateJavaScript("window.postMessage({ __sp: '$msg' }, '*')", completionHandler = null)
    }

    fun pausePage() = send("{\"t\":\"pause\"}")

    suspend fun start(pageUrl: String) {
        if (detectJs == null) detectJs = runCatching { Res.readBytes("files/detect.js").decodeToString() }.getOrNull()
        // Die WebView kann schon vor dem Lesen entstanden sein (Ansicht zuerst): dann jetzt nachreichen.
        if (!detectAdded) detectJs?.let {
            webView.configuration.userContentController.addUserScript(
                WKUserScript(source = it, injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, forMainFrameOnly = false),
            )
            detectAdded = true
        }
        job?.cancel()
        found.clear()
        loaded = false
        pageHost = NSURL.URLWithString(pageUrl)?.host
        val url = NSURL.URLWithString(pageUrl) ?: run { _state.value = State.NotFound; return }
        webView.loadRequest(NSURLRequest.requestWithURL(url))
        _state.value = State.Searching(tr("Seite lädt …", "Loading page …"), 0)
        job = scope.launch {
            val t0 = nowMillis()
            var nudged = false
            var firstHit = 0L
            while (isActive) {
                delay(300)
                val now = nowMillis()
                val elapsed = now - t0
                val all = found.values.filter { it.ok == true }
                val usable = all.filter { !it.drm }
                if (usable.isNotEmpty()) {
                    if (firstHit == 0L) {
                        firstHit = now
                        _state.value = State.Searching(tr("Gefunden. Player startet …", "Found. Starting player …"), 3)
                    }
                    // Kurz weitersammeln: oft kommen Manifest und Qualitäten gleich hinterher.
                    if (now - firstHit >= 1500) {
                        pausePage()
                        _state.value = State.Ready(Probe.pick(usable))
                        return@launch
                    }
                } else if (!nudged && ((loaded && elapsed > 4000) || elapsed > 10_000)) {
                    nudged = true
                    send("{\"t\":\"nudge\"}")
                    _state.value = State.Searching(tr("Starte das Video stumm …", "Starting the video muted …"), 2)
                } else if (!nudged && loaded) {
                    _state.value = State.Searching(tr("Frage den Player …", "Asking the player …"), 1)
                }
                if (elapsed > 30_000) {
                    pausePage()
                    _state.value = if (all.any { it.drm }) State.Protected else State.NotFound
                    return@launch
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Idle
        webView.stopLoading()
        webView.loadHTMLString("<html></html>", baseURL = null)
    }
}
