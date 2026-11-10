package de.marne.streamplayer.creator

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import de.marne.streamplayer.importer.FetchException
import de.marne.streamplayer.importer.Fetched
import de.marne.streamplayer.importer.Fetcher
import de.marne.streamplayer.importer.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Schlichter Abruf. Gleiche Kennung wie die WebView, damit Seiten beide gleich behandeln.
 * Cookies leben nur für eine Analyse (Einwilligungs- und Sitzungs-Cookies zwischen den Staffeln).
 */
class HttpFetcher(base: OkHttpClient, private val userAgent: String) : Fetcher {
    private val jar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { c -> list.removeAll { it.name == c.name }; list += c }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty().filter { it.matches(url) }
    }
    private val http = base.newBuilder().cookieJar(jar).build()

    override suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        val req = runCatching {
            Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.7")
                .build()
        }.getOrElse { throw FetchException("Die Adresse ist ungültig.") }
        try {
            http.newCall(req).execute().use { r ->
                val final = r.request.url.toString()
                val body = r.body ?: return@use Fetched(final, r.code, "")
                val type = r.header("Content-Type").orEmpty().lowercase()
                // Ein Video oder Bild statt einer Seite: nichts zu lesen, aber kein Fehler.
                if (type.isNotEmpty() && "html" !in type && "xml" !in type && "text" !in type) return@use Fetched(final, r.code, "")
                val src = body.source()
                src.request(MAX_BYTES)
                val bytes = src.buffer.readByteArray(minOf(src.buffer.size, MAX_BYTES))
                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                Fetched(final, r.code, String(bytes, charset))
            }
        } catch (e: UnknownHostException) {
            throw FetchException("Den Server gibt es nicht, oder das Netz fehlt.")
        } catch (e: SocketTimeoutException) {
            throw FetchException("Die Seite antwortet nicht.")
        } catch (e: SSLException) {
            throw FetchException("Die Verbindung ist nicht sicher (Zertifikat).")
        }
    }

    companion object {
        private const val MAX_BYTES = 3L * 1024 * 1024
    }
}

/**
 * Für Seiten, die ihre Folgen erst per JavaScript zeigen oder eine Vorschaltseite haben: eine
 * WebView lädt die Seite, wartet, bis sich die Zahl der Verweise nicht mehr ändert, und gibt das
 * fertige HTML zurück. Will die Seite eine Bestätigung (Captcha), wird die WebView sichtbar
 * ([waiting]), bis „Weiter“ getippt ist.
 *
 * Verdeckt darf die Seite nicht woandershin navigieren (Werbe-Umleitungen); sichtbar schon, weil
 * Bestätigungen oft über eine Zwischenadresse laufen. Neue Fenster gibt es nie.
 */
class BrowserFetcher(private val context: Context) : Fetcher {
    private val _view = MutableStateFlow<WebView?>(null)
    val view: StateFlow<WebView?> = _view.asStateFlow()
    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private var proceed: CompletableDeferred<Unit>? = null
    private var finished: CompletableDeferred<Unit>? = null
    @Volatile private var allowedHost: String? = null
    @Volatile private var mainStatus = 200

    /** „Weiter“ nach der Bestätigung. */
    fun proceed() { proceed?.complete(Unit) }

    override suspend fun fetch(url: String): Fetched {
        val wv = withContext(Dispatchers.Main) { _view.value ?: create().also { _view.value = it } }
        withContext(Dispatchers.Main) {
            allowedHost = hostOf(url)
            mainStatus = 200
            finished = CompletableDeferred()
            wv.settings.blockNetworkImage = true   // Bilder braucht die Analyse nicht; spart Zeit.
            wv.loadUrl(url)
        }
        withTimeoutOrNull(25_000) { finished?.await() } ?: run {
            withContext(Dispatchers.Main) { wv.stopLoading() }
        }
        settle(wv)
        var html = outerHtml(wv)
        var status = mainStatus
        val page = withContext(Dispatchers.Default) { Page.parse(html, url) }
        if (page.challenge) {
            val p = CompletableDeferred<Unit>()
            proceed = p
            withContext(Dispatchers.Main) {
                wv.settings.blockNetworkImage = false
                _waiting.value = true
            }
            try {
                p.await()
            } finally {
                withContext(Dispatchers.Main) {
                    _waiting.value = false
                    wv.settings.blockNetworkImage = true
                }
            }
            settle(wv)
            html = outerHtml(wv)
            status = 200
        }
        val final = withContext(Dispatchers.Main) { wv.url } ?: url
        return Fetched(final, status, html)
    }

    /** WebView abbauen – nach jeder Analyse. Cookies (bestandene Bestätigungen) bleiben. */
    suspend fun release() = withContext(Dispatchers.Main) {
        proceed?.cancel()
        _waiting.value = false
        _view.value?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        _view.value = null
    }

    /** Warten, bis die Seite nichts mehr nachlädt: Zahl der Verweise dreimal gleich, höchstens 8 s. */
    private suspend fun settle(wv: WebView) {
        delay(1200)
        var last = -1
        var same = 0
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until && same < 3) {
            val n = eval(wv, "document.links.length")?.toIntOrNull() ?: 0
            if (n == last) same++ else { same = 0; last = n }
            delay(400)
        }
    }

    private suspend fun outerHtml(wv: WebView): String =
        eval(wv, "document.documentElement.outerHTML") ?: throw FetchException("Die Seite ließ sich im Browser nicht lesen.")

    private suspend fun eval(wv: WebView, js: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        wv.evaluateJavascript(js) { raw ->
            result.complete(runCatching { JSONTokener(raw).nextValue()?.toString() }.getOrNull())
        }
        withTimeoutOrNull(5000) { result.await() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = WebSettings.getDefaultUserAgent(context)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme != "http" && u.scheme != "https") return true
                if (_waiting.value || !request.isForMainFrame) return false
                val host = u.host?.removePrefix("www.") ?: return true
                val allowed = allowedHost ?: return false
                return !(host == allowed || host.endsWith(".$allowed") || allowed.endsWith(".$host"))
            }

            // Kein Zurücksetzen des Status in onPageStarted: Anlass war ein 404, der als Film „Error
            // response“ durchging – onReceivedHttpError kommt für die Hauptseite *vor* onPageStarted.
            // Zurückgesetzt wird nur in fetch(), vor loadUrl.

            override fun onPageFinished(view: WebView, url: String?) {
                finished?.complete(Unit)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) mainStatus = errorResponse.statusCode
            }
        }
    }

    private fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
}
