package de.marne.streamplayer.download

import de.marne.streamplayer.detect.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anlass (1.11.0): Die Folgenliste kannte nur „geladen“ oder „nicht“ – was lief, sah man nirgends,
 * und „Laden“ ließ sich doppelt antippen. Ein Zustand für alle Stellen, mit fester Rangfolge.
 */
class OfflineTest {
    private fun item(id: String, key: String?, state: DlState, uri: String? = null) = DownloadItem(
        id = id, url = "https://x/$id.mp4", kind = MediaKind.FILE, title = id, thumbnail = null, pageUrl = "https://x",
        referer = null, userAgent = "ua", quality = null, keys = emptyList(), mime = null, durationMs = null,
        state = state, fileUri = uri, libraryKey = key, bytesDone = 100, bytesTotal = 200,
    )

    @Test fun dateiGewinntImmer() {
        val d = item("a", "m:1", DlState.DONE, uri = "content://1")
        val s = Offline.state("m:1", "content://1", listOf(d, item("b", "m:1", DlState.RUNNING)), mapOf("m:1" to true))
        assertTrue(s is OfflineState.Done)
        assertEquals(d, (s as OfflineState.Done).item)
    }

    @Test fun laufendVorSucheVorAngehalten() {
        val paused = item("p", "e:1:1:2", DlState.PAUSED)
        val running = item("r", "e:1:1:2", DlState.RUNNING)
        assertTrue(Offline.state("e:1:1:2", null, listOf(paused, running), mapOf("e:1:1:2" to false)) is OfflineState.Loading)
        assertEquals(OfflineState.Waiting(false), Offline.state("e:1:1:2", null, listOf(paused), mapOf("e:1:1:2" to false)))
        val stopped = Offline.state("e:1:1:2", null, listOf(item("f", "e:1:1:2", DlState.FAILED)), emptyMap())
        assertTrue(stopped is OfflineState.Stopped && stopped.failed)
    }

    @Test fun fremdeSchluesselZaehlenNicht() {
        assertEquals(OfflineState.None, Offline.state("m:2", null, listOf(item("a", "m:1", DlState.RUNNING)), mapOf("m:1" to true)))
    }

    @Test fun platz() {
        // Fertig zählt die Datei, unterwegs das schon Geladene.
        assertEquals(300L, Offline.bytes(listOf(item("a", null, DlState.DONE), item("b", null, DlState.RUNNING))))
    }
}
