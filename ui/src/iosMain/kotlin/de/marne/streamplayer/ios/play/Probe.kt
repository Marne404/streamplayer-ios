package de.marne.streamplayer.ios.play

import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.ios.http
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import platform.Foundation.NSURL

/** Safari auf dem iPad – so sehen Seiten und Hoster dieselbe Anfrage wie im Browser. */
internal const val SAFARI_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

/** Ein Fund der Seite, nach dem Prüfen mit Länge, Größe und Schutz. */
internal data class Found(
    val url: String,
    val kind: MediaKind,
    /** Seite bzw. Frame, aus dem die Adresse kam – als Referer zum Abspielen. */
    val referer: String?,
    val title: String? = null,
    val poster: String? = null,
    val sources: Set<String> = emptySet(),
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val drm: Boolean = false,
    val live: Boolean = false,
)

internal object Probe {
    private const val MIN_FILE_BYTES = 100L * 1024

    /** Wie UrlClassifier auf Android, nur das Nötige: Endung bzw. MIME-Typ. */
    fun classify(url: String, mime: String? = null): MediaKind {
        val m = mime?.lowercase().orEmpty()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            "mpegurl" in m || path.endsWith(".m3u8") || path.endsWith(".m3u") -> MediaKind.HLS
            "dash+xml" in m || path.endsWith(".mpd") -> MediaKind.DASH
            else -> MediaKind.FILE
        }
    }

    private fun resolve(base: String, ref: String): String =
        NSURL.URLWithString(ref, relativeToURL = NSURL.URLWithString(base))?.absoluteString ?: ref

    private suspend fun text(url: String, referer: String?): Pair<String, String>? {
        val r = http.get(url) {
            referer?.let { header("Referer", it) }
            header("User-Agent", SAFARI_UA)
        }
        if (!r.status.isSuccess()) return null
        return r.call.request.url.toString() to r.bodyAsText()
    }

    suspend fun check(f: Found): Found = runCatching {
        when (f.kind) {
            MediaKind.HLS -> hls(f)
            // AVPlayer spielt kein DASH – solche Funde zählen hier nicht.
            MediaKind.DASH -> f.copy(ok = false)
            MediaKind.FILE -> file(f)
        }
    }.getOrElse { f.copy(ok = false) }

    private suspend fun file(f: Found): Found {
        val r = http.get(f.url) {
            f.referer?.let { header("Referer", it) }
            header("User-Agent", SAFARI_UA)
            header("Range", "bytes=0-0")
        }
        if (!r.status.isSuccess()) return f.copy(ok = false)
        val mime = r.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
        when (classify(f.url, mime)) {
            MediaKind.HLS -> return hls(f.copy(kind = MediaKind.HLS))
            MediaKind.DASH -> return f.copy(kind = MediaKind.DASH, ok = false)
            else -> Unit
        }
        if (mime != null && (mime.startsWith("text/") || mime.startsWith("image/") || "json" in mime || "javascript" in mime || mime == "video/mp2t")) {
            return f.copy(ok = false)
        }
        val total = if (r.status.value == 206) r.headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull()
        else r.headers["Content-Length"]?.toLongOrNull()
        if (total != null && total < MIN_FILE_BYTES) return f.copy(ok = false)
        return f.copy(ok = true, sizeBytes = total)
    }

    private fun drm(playlist: String): Boolean = playlist.lineSequence().any { l ->
        (l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY")) &&
            ("SAMPLE-AES" in l) && ("streamingkeydelivery" in l || "widevine" in l || "playready" in l || "KEYFORMAT" in l && "identity" !in l)
    }

    private suspend fun hls(f: Found): Found {
        val (finalUrl, body) = text(f.url, f.referer) ?: return f.copy(ok = false)
        if (!body.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")) return f.copy(ok = false)
        var media = body
        var mediaUrl = finalUrl
        if ("#EXT-X-STREAM-INF" in body) {
            // Die Variante mit der höchsten Bitrate einmal laden: Dauer, Live oder nicht, Schutz.
            val lines = body.lines()
            val best = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") }.maxByOrNull { (_, l) ->
                Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } ?: return f.copy(ok = false)
            val ref = lines.drop(best.index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return f.copy(ok = false)
            val sub = text(resolve(finalUrl, ref.trim()), f.referer)
            if (sub == null) return f.copy(ok = true, drm = drm(body))
            mediaUrl = sub.first
            media = sub.second
        }
        val ended = "#EXT-X-ENDLIST" in media
        val seconds = media.lineSequence().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0 }
        return f.copy(
            ok = true,
            live = !ended,
            durationMs = if (ended) (seconds * 1000).toLong() else null,
            drm = drm(body) || drm(media),
            url = f.url,
        ).also { mediaUrl.length }
    }

    /** Das Hauptvideo, nicht der Werbeclip: Streams vor Dateien, dann das längste bzw. größte. */
    fun pick(list: List<Found>): Found = list.sortedWith(
        compareByDescending<Found> { it.kind != MediaKind.FILE }
            .thenByDescending { it.durationMs ?: 0 }
            .thenByDescending { it.sizeBytes ?: 0 },
    ).first()
}
