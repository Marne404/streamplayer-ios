package de.marne.streamplayer.ui

import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.DownloadItem

/** Zahlen je nach Sprache (Komma bzw. Punkt), Mittelpunkt als Trenner, keine Ausrufezeichen. */
object Format {
    private val loc get() = Lang.locale

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(loc, "%.1f GB", b / 1e9)
        b >= 10_000_000 -> String.format(loc, "%.0f MB", b / 1e6)
        b >= 1_000_000 -> String.format(loc, "%.1f MB", b / 1e6)
        b >= 1_000 -> String.format(loc, "%.0f kB", b / 1e3)
        else -> "$b B"
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(loc, "%d:%02d:%02d", h, m, sec) else String.format(loc, "%d:%02d", m, sec)
    }

    fun percent(p: Float): String = (p * 100).toInt().let { tr("$it %", "$it%") }

    fun kind(kind: MediaKind, mime: String?): String = when (kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.FILE -> when (val m = mime?.substringBefore(';')?.trim()) {
            null -> tr("Datei", "File")
            "audio/mpeg" -> "MP3"
            "video/x-matroska" -> "MKV"
            "video/quicktime" -> "MOV"
            else -> m.substringAfter('/').removePrefix("x-").uppercase(loc)
        }
    }

    /** Metazeile eines Fundes: „HLS · 12:31 · bis 1080p“ */
    fun candidateMeta(c: Candidate): String = buildList {
        add(kind(c.kind, c.mime))
        c.durationMs?.let { add(duration(it)) }
        c.sizeBytes?.let { add(bytes(it)) }
        c.variants.firstOrNull()?.label?.takeIf { it != "Original" && c.variants.size > 1 }?.let { add(tr("bis $it", "up to $it")) }
    }.joinToString(" · ")

    /** Metazeile eines Downloads, passend zum Zustand. */
    fun metaLine(d: DownloadItem): String = when (d.state) {
        DlState.RUNNING -> buildList {
            add(percent(d.progress))
            if (d.kind == MediaKind.FILE && d.bytesTotal != null) add(tr("${bytes(d.bytesDone)} von ${bytes(d.bytesTotal)}", "${bytes(d.bytesDone)} of ${bytes(d.bytesTotal)}"))
            else if (d.bytesDone > 0) add(bytes(d.bytesDone))
            if (d.speedBps > 0) add(speed(d.speedBps))
        }.joinToString(" · ")
        DlState.MERGING -> tr("Wird zusammengefügt · ${percent(d.progress)}", "Merging · ${percent(d.progress)}")
        DlState.QUEUED -> tr("In der Warteschlange", "Queued")
        DlState.WAITING_WIFI -> tr("Wartet auf WLAN", "Waiting for Wi-Fi")
        DlState.PAUSED -> tr("Pausiert · ${percent(d.progress)}", "Paused · ${percent(d.progress)}")
        DlState.FAILED -> d.error ?: tr("Fehlgeschlagen", "Failed")
        DlState.DONE -> buildList {
            d.quality?.let { add(it) }
            if (d.bytesDone > 0) add(bytes(d.bytesDone))
            d.durationMs?.let { add(duration(it)) }
        }.joinToString(" · ").ifBlank { tr("Geladen", "Downloaded") }
    }

    fun host(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
