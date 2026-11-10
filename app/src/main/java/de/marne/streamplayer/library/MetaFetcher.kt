package de.marne.streamplayer.library

import de.marne.streamplayer.net.Http
import de.marne.streamplayer.net.Http.headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class PageMeta(val title: String?, val image: String?, val description: String?)

/**
 * Titel, Bild und Beschreibung einer Seite ohne WebView: ein einfacher Abruf, dann
 * og:/twitter:-Angaben aus dem HTML. Reicht für die meisten Seiten; was fehlt, ergänzt
 * das erste Ansehen.
 */
class MetaFetcher(private val http: OkHttpClient, private val userAgent: () -> String) {

    suspend fun fetch(url: String): PageMeta? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).headers(Http.headersFor(url, null, userAgent())).header("Accept", "text/html,*/*").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val type = r.header("Content-Type").orEmpty()
                if (!type.contains("html")) return@use null
                val src = r.body?.source() ?: return@use null
                src.request(MAX_BYTES)
                val html = src.buffer.snapshot().utf8().take(MAX_BYTES.toInt())
                parse(html, r.request.url.toString())
            }
        }.getOrNull()
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024

        fun parse(html: String, base: String): PageMeta {
            fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
                Regex("""<meta[^>]+(?:property|name)=["']$n["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value?.let { tag ->
                    Regex("""content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                }?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val title = meta("og:title", "twitter:title")
                ?: Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val image = meta("og:image", "og:image:url", "twitter:image")?.let { resolve(base, it) }
            val desc = meta("og:description", "description", "twitter:description")
            return PageMeta(title, image, desc)
        }

        private fun resolve(base: String, url: String) = runCatching { java.net.URI(base).resolve(url).toString() }.getOrDefault(url)

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    }
}
