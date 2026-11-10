package de.marne.streamplayer.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {

    fun client(context: Context): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Der User-Agent der eingebauten WebView: Server sollen uns für denselben Browser halten. */
    fun defaultUserAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android) Streamplayer")

    /** Cookies der WebView für genau diese Adresse. Hosts brauchen sie oft, um Medien auszuliefern. */
    fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Die Kopfzeilen, mit denen auch die Seite selbst die Datei angefragt hätte. */
    fun headersFor(url: String, referer: String?, userAgent: String): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            put("Referer", referer)
            originOf(referer)?.let { put("Origin", it) }
        }
        cookiesFor(url)?.let { put("Cookie", it) }
    }

    fun Request.Builder.headers(map: Map<String, String>): Request.Builder = apply {
        map.forEach { (k, v) -> header(k, v) }
    }

    private fun originOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        if (u.scheme == null || u.host == null) null
        else buildString {
            append(u.scheme).append("://").append(u.host)
            if (u.port != -1) append(":").append(u.port)
        }
    }.getOrNull()
}
