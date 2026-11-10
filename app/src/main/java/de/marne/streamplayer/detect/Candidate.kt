package de.marne.streamplayer.detect

import androidx.media3.common.StreamKey

/** Ein StreamKey ohne Parcelable-Ballast, damit er sich als JSON speichern lässt. */
data class Key(val period: Int, val group: Int, val stream: Int) {
    fun toStreamKey() = StreamKey(period, group, stream)
}

/** Eine wählbare Qualität eines adaptiven Streams. */
data class Variant(
    val label: String,
    val height: Int?,
    val bitrate: Int?,
    val keys: List<Key>,
    val estimatedBytes: Long?,
)

enum class ProbeStatus { PENDING, OK, FAILED }

/** Wo die Adresse herkam. Nur für Filter und Fehlersuche. */
enum class Source { NETWORK, PLAY, DOM, XHR, LD, DOWNLOAD }

/**
 * Eine gefundene Adresse auf der aktuellen Seite. Erst nach der Prüfung (status = OK)
 * taucht sie in der Liste auf.
 */
data class Candidate(
    val url: String,
    val kind: MediaKind,
    val source: Source,
    val pageUrl: String,
    val referer: String?,
    val title: String? = null,
    val thumbnail: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val variants: List<Variant> = emptyList(),
    val drm: Boolean = false,
    val live: Boolean = false,
    val status: ProbeStatus = ProbeStatus.PENDING,
    /** Teil-Playlists eines Masters; tauchen sie einzeln auf, sind sie Dubletten. */
    val children: Set<String> = emptySet(),
    val foundAt: Long = System.currentTimeMillis(),
) {
    val downloadable: Boolean get() = status == ProbeStatus.OK && !drm && !live
}

/** Titel und Vorschaubild der Seite (Hauptframe). */
data class PageMeta(val title: String = "", val image: String? = null)
