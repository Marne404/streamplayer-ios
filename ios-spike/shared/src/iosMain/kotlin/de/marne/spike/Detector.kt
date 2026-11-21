package de.marne.spike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

data class Found(val url: String, val sources: Set<String>, val page: String?, val title: String?)

/**
 * Weiche für detect.js: Das Skript ruft window.streamplayerBridge.postMessage(json) auf,
 * genau wie in der Android-WebView. Hier landet das bei WebKit.
 *
 * Zusatz nur für iOS: Android fängt Netzwerk-Anfragen nativ ab (shouldInterceptRequest),
 * das kann WKWebView nicht. Ersatz zum Ausprobieren: Resource Timing der Seite – jede geladene
 * Adresse mit Video-Endung wird als Fund „perf“ gemeldet.
 */
private const val SHIM = """
window.streamplayerBridge = {
  postMessage: function (s) {
    try { window.webkit.messageHandlers.streamplayer.postMessage(String(s)); } catch (e) {}
  }
};
(function () {
  var re = /\.(m3u8|mpd|mp4|m4v|webm|mov)(\?|#|$)/i;
  function check(e) {
    if (re.test(e.name)) {
      window.streamplayerBridge.postMessage(JSON.stringify({ t: 'media', url: e.name, src: 'perf', frame: location.href }));
    }
  }
  try {
    new PerformanceObserver(function (list) { list.getEntries().forEach(check); })
      .observe({ type: 'resource', buffered: true });
  } catch (e) {}
})();
"""

class Detector(private val onChange: (List<Found>) -> Unit) {
    private val found = LinkedHashMap<String, Found>()
    private val json = Json { ignoreUnknownKeys = true }
    var messages = 0
        private set

    private val handler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            val body = didReceiveScriptMessage.body as? String ?: return
            messages++
            val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
            if (obj["t"]?.jsonPrimitive?.content != "media") return
            val url = obj["url"]?.jsonPrimitive?.content ?: return
            val src = obj["src"]?.jsonPrimitive?.content ?: "?"
            val page = obj["frame"]?.jsonPrimitive?.content
            val title = obj["title"]?.jsonPrimitive?.content
            val old = found[url]
            found[url] = Found(url, (old?.sources ?: emptySet()) + src, old?.page ?: page, old?.title ?: title)
            onChange(found.values.toList())
        }
    }

    private var webView: WKWebView? = null

    fun view(detectJs: String): WKWebView {
        webView?.let { return it }
        val cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
        val ucc = cfg.userContentController
        ucc.addScriptMessageHandler(handler, name = "streamplayer")
        val start = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart
        ucc.addUserScript(WKUserScript(source = SHIM, injectionTime = start, forMainFrameOnly = false))
        ucc.addUserScript(WKUserScript(source = detectJs, injectionTime = start, forMainFrameOnly = false))
        return WKWebView(frame = CGRectMake(0.0, 0.0, 400.0, 300.0), configuration = cfg).also { webView = it }
    }

    fun open(url: String) {
        found.clear()
        messages = 0
        onChange(emptyList())
        val nsUrl = NSURL.URLWithString(url) ?: return
        webView?.loadRequest(NSURLRequest.requestWithURL(nsUrl))
    }
}
