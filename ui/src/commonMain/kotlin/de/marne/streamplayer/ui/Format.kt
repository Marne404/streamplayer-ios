package de.marne.streamplayer.ui

import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.DownloadItem

/** Zahlen je nach Sprache (Komma bzw. Punkt), Mittelpunkt als Trenner, keine Ausrufezeichen. */
object Format {

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> "${decimal(b / 1e9, 1)} GB"
        b >= 10_000_000 -> "${decimal(b / 1e6, 0)} MB"
        b >= 1_000_000 -> "${decimal(b / 1e6, 1)} MB"
        b >= 1_000 -> "${decimal(b / 1e3, 0)} kB"
        else -> "$b B"
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
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
            else -> m.substringAfter('/').removePrefix("x-").uppercase()
        }
    }

    /** Metazeile eines Downloads, passend zum Zustand. */
    fun metaLine(d: DownloadItem): String = when (d.state) {
        DlState.RUNNING -> buildList {
            add(percent(d.progress))
            val total = d.bytesTotal
            if (d.kind == MediaKind.FILE && total != null) add(tr("${bytes(d.bytesDone)} von ${bytes(total)}", "${bytes(d.bytesDone)} of ${bytes(total)}"))
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

    fun host(url: String): String = de.marne.streamplayer.library.hostOf(url) ?: url

    /**
     * Zahl mit [places] Nachkommastellen, kaufmännisch gerundet (wie früher mit Formatstring) –
     * auf Deutsch mit Komma („7,4“), auf Englisch mit Punkt („7.4“).
     */
    fun decimal(x: Double, places: Int): String {
        var f = 1L
        repeat(places) { f *= 10 }
        val neg = x < 0
        val n = kotlin.math.floor(kotlin.math.abs(x) * f + 0.5).toLong()
        val whole = (n / f).toString()
        val sign = if (neg && n != 0L) "-" else ""
        if (places == 0) return sign + whole
        val frac = (n % f).toString().padStart(places, '0')
        return sign + whole + (if (Lang.isGerman) "," else ".") + frac
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')
}
