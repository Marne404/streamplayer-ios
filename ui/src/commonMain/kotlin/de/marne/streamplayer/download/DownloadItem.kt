package de.marne.streamplayer.download

import de.marne.streamplayer.detect.Key
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.ui.nowMillis

enum class DlState { QUEUED, RUNNING, MERGING, WAITING_WIFI, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val thumbnail: String?,
    val pageUrl: String,
    val referer: String?,
    val userAgent: String,
    val quality: String?,
    val keys: List<Key>,
    val mime: String?,
    val durationMs: Long?,
    val state: DlState = DlState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    /** 0..1 über alle Phasen (Laden und Zusammenfügen). */
    val progress: Float = 0f,
    val speedBps: Long = 0,
    val error: String? = null,
    /** content://-Adresse in der Mediathek, sobald fertig. */
    val fileUri: String? = null,
    val fileName: String? = null,
    val createdAt: Long = nowMillis(),
    val finishedAt: Long? = null,
    /** Fortschritts-Schlüssel des Films/der Folge in der Bibliothek; die fertige Datei hängt sich dort an. */
    val libraryKey: String? = null,
    /** Untertitel der Seite; werden nach dem Laden neben die Datei gelegt (SubtitleStore). */
    val subtitles: List<de.marne.streamplayer.subtitles.SubTrack> = emptyList(),
) {
    val active: Boolean get() = state == DlState.QUEUED || state == DlState.RUNNING || state == DlState.MERGING || state == DlState.WAITING_WIFI

    companion object
}
