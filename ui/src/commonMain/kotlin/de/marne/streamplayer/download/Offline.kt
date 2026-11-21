package de.marne.streamplayer.download


/**
 * Wie es um einen Film oder eine Folge auf diesem Gerät steht – ein Zustand für alle Stellen der
 * App (Detailseite, Folgen, Downloads, Kurzansicht). Vorher wusste jede Stelle nur einen Teil:
 * die Folgenliste kannte „geladen“ oder „nicht“, die Downloads-Seite nur Dateien – was dazwischen
 * lief, sah man nirgends, und „Laden“ ließ sich ein zweites Mal antippen.
 */
sealed interface OfflineState {
    /** Nichts da, nichts unterwegs. */
    data object None : OfflineState

    /** Das Video wird auf der Seite gesucht ([searching]) oder wartet, bis die Suche davor fertig ist. */
    data class Waiting(val searching: Boolean) : OfflineState

    /** Lädt, wartet auf einen Platz oder aufs WLAN, wird zusammengefügt. */
    data class Loading(val item: DownloadItem) : OfflineState

    /** Angehalten oder fehlgeschlagen – [item].state sagt, welches. */
    data class Stopped(val item: DownloadItem) : OfflineState {
        val failed: Boolean get() = item.state == DlState.FAILED
    }

    /** Liegt auf dem Gerät. [item] fehlt bei Dateien, deren Download-Eintrag es nicht mehr gibt. */
    data class Done(val uri: String, val item: DownloadItem?) : OfflineState
}

object Offline {

    /**
     * [offlineUri]: die Datei aus LibTitle.offline, [pending]: Schlüssel → sucht gerade (true) oder
     * wartet (false). Eine fertige Datei gewinnt immer; danach, was gerade läuft, dann die Suche,
     * zuletzt ein angehaltener Versuch.
     */
    fun state(key: String, offlineUri: String?, downloads: List<DownloadItem>, pending: Map<String, Boolean>): OfflineState {
        if (offlineUri != null) return OfflineState.Done(offlineUri, downloads.firstOrNull { it.fileUri == offlineUri })
        val mine = downloads.filter { it.libraryKey == key }
        mine.firstOrNull { it.active }?.let { return OfflineState.Loading(it) }
        pending[key]?.let { return OfflineState.Waiting(it) }
        mine.firstOrNull { it.state == DlState.PAUSED || it.state == DlState.FAILED }?.let { return OfflineState.Stopped(it) }
        return OfflineState.None
    }

    /** Platz, den ein Download belegt (fertig: die Datei, sonst das schon Geladene). */
    fun bytes(d: DownloadItem): Long = if (d.state == DlState.DONE) d.bytesTotal ?: d.bytesDone else d.bytesDone

    fun bytes(list: List<DownloadItem>): Long = list.sumOf(::bytes)
}
