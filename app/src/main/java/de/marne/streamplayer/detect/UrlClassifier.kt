package de.marne.streamplayer.detect

import java.util.Locale

enum class MediaKind { FILE, HLS, DASH }

/**
 * Entscheidet allein anhand von Adresse und (falls bekannt) MIME-Typ, ob eine Anfrage
 * ein Video sein könnte. Billig, weil es für jede Ressource jeder Seite läuft.
 */
object UrlClassifier {

    private val videoExt = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp", "wmv", "ogv", "mpg", "mpeg")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")

    /** Teile eines Streams, nie ein ganzes Video. */
    private val segmentExt = setOf("ts", "m4s", "cmfv", "cmfa", "m4f", "aac_seg")

    private val segmentName = Regex("""(^|[/_\-.])(seg|segment|frag|fragment|chunk)[-_]?\d+""", RegexOption.IGNORE_CASE)

    private val hlsMime = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
    private const val DASH_MIME = "application/dash+xml"

    fun classify(url: String, mime: String? = null): MediaKind? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (m != null) {
            if (m in hlsMime) return MediaKind.HLS
            if (m == DASH_MIME) return MediaKind.DASH
            if (m == "video/mp2t" || m.contains("segment")) return null
            if (m.startsWith("video/") || m.startsWith("audio/")) return MediaKind.FILE
        }
        val ext = extension(url)
        return when {
            ext == "m3u8" -> MediaKind.HLS
            ext == "mpd" -> MediaKind.DASH
            ext in videoExt || ext in audioExt -> MediaKind.FILE
            // Manifeste ohne Endung im Pfad, aber im Dateinamen-Parameter (…/play?file=x.m3u8)
            url.contains(".m3u8", true) -> MediaKind.HLS
            url.contains(".mpd?", true) || url.endsWith(".mpd", true) -> MediaKind.DASH
            else -> null
        }
    }

    /** Teilstück eines adaptiven Streams oder gestückelte Byte-Bereiche. */
    fun looksLikeSegment(url: String): Boolean {
        val ext = extension(url)
        if (ext in segmentExt) return true
        val q = url.substringAfter('?', "").lowercase(Locale.ROOT)
        if (q.contains("range=") || q.contains("bytestart=") || q.contains("&rn=")) return true
        return segmentName.containsMatchIn(lastSegment(url))
    }

    fun isAudio(url: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || extension(url) in audioExt

    /** Adresse ohne Fragment: gleiche Datei, gleicher Schlüssel. */
    fun key(url: String): String = url.substringBefore('#')

    /** Ordner einer Adresse, um viele gleichartige Teile zu erkennen. */
    fun dir(url: String): String = url.substringBefore('?').substringBeforeLast('/')

    fun extension(url: String): String? {
        val name = lastSegment(url)
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT).takeIf { it.length <= 5 }
    }

    fun fileName(url: String): String =
        runCatching { java.net.URLDecoder.decode(lastSegment(url), "UTF-8") }.getOrDefault(lastSegment(url))

    private fun lastSegment(url: String): String =
        url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
}
