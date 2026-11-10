package de.marne.streamplayer.ui

import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.DownloadItem
import java.util.Locale

/** Zahlen deutsch: Komma, Mittelpunkt als Trenner, keine Ausrufezeichen. */
object Format {
    private val de = Locale.GERMANY

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(de, "%.1f GB", b / 1e9)
        b >= 10_000_000 -> String.format(de, "%.0f MB", b / 1e6)
        b >= 1_000_000 -> String.format(de, "%.1f MB", b / 1e6)
        b >= 1_000 -> String.format(de, "%.0f kB", b / 1e3)
        else -> "$b B"
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(de, "%d:%02d:%02d", h, m, sec) else String.format(de, "%d:%02d", m, sec)
    }

    fun percent(p: Float): String = "${(p * 100).toInt()} %"

    fun kind(kind: MediaKind, mime: String?): String = when (kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.FILE -> when (val m = mime?.substringBefore(';')?.trim()) {
            null -> "Datei"
            "audio/mpeg" -> "MP3"
            "video/x-matroska" -> "MKV"
            "video/quicktime" -> "MOV"
            else -> m.substringAfter('/').removePrefix("x-").uppercase(de)
        }
    }

    /** Metazeile eines Fundes: „HLS · 12:31 · bis 1080p“ */
    fun candidateMeta(c: Candidate): String = buildList {
        add(kind(c.kind, c.mime))
        c.durationMs?.let { add(duration(it)) }
        c.sizeBytes?.let { add(bytes(it)) }
        c.variants.firstOrNull()?.label?.takeIf { it != "Original" && c.variants.size > 1 }?.let { add("bis $it") }
    }.joinToString(" · ")

    /** Metazeile eines Downloads, passend zum Zustand. */
    fun metaLine(d: DownloadItem): String = when (d.state) {
        DlState.RUNNING -> buildList {
            add(percent(d.progress))
            if (d.kind == MediaKind.FILE && d.bytesTotal != null) add("${bytes(d.bytesDone)} von ${bytes(d.bytesTotal)}")
            else if (d.bytesDone > 0) add(bytes(d.bytesDone))
            if (d.speedBps > 0) add(speed(d.speedBps))
        }.joinToString(" · ")
        DlState.MERGING -> "Wird zusammengefügt · ${percent(d.progress)}"
        DlState.QUEUED -> "In der Warteschlange"
        DlState.WAITING_WIFI -> "Wartet auf WLAN"
        DlState.PAUSED -> "Pausiert · ${percent(d.progress)}"
        DlState.FAILED -> d.error ?: "Fehlgeschlagen"
        DlState.DONE -> buildList {
            d.quality?.let { add(it) }
            if (d.bytesDone > 0) add(bytes(d.bytesDone))
            d.durationMs?.let { add(duration(it)) }
        }.joinToString(" · ").ifBlank { "Geladen" }
    }

    fun host(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
