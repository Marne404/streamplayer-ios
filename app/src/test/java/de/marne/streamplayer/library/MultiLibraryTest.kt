package de.marne.streamplayer.library

import de.marne.streamplayer.account.SyncCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anlass (1.13.0): Was in eine neue Bibliothek importiert wurde und schon in einer anderen stand,
 * blieb dort und fehlte in der neuen. Jetzt steht derselbe Titel in mehreren Bibliotheken.
 */
class MultiLibraryTest {
    private val t = LibTitle(id = "a", kind = TitleKind.MOVIE, title = "A", pageUrl = "https://x/a", libraryId = "anime", alsoIn = listOf("main", "kids"))

    @Test fun mitgliedschaft() {
        assertTrue(t.isIn("anime") && t.isIn("main") && t.isIn("kids"))
        assertFalse(t.isIn("docs"))
        assertEquals(listOf("anime", "main", "kids"), t.libraries)
        assertEquals(listOf(Library.MAIN), t.copy(libraryId = null, alsoIn = emptyList()).libraries)
    }

    @Test fun ueberlebtSpeichernUndLaden() {
        assertEquals(t, LibTitle.fromJson(t.toJson()).copy(addedAt = t.addedAt))
        // Ältere Dateien ohne Feld: nur die eine Bibliothek.
        assertEquals(emptyList<String>(), LibTitle.fromJson(t.copy(alsoIn = emptyList()).toJson()).alsoIn)
    }

    @Test fun freundeSehenKeineBibliotheken() {
        val o = JSONObject(SyncCodec.sharedTitleJson(t))
        assertFalse(o.has("library"))
        assertFalse(o.has("alsoIn"))
        // Ins eigene Konto gehen sie mit.
        assertEquals(2, JSONObject(SyncCodec.titleJson(t)).getJSONArray("alsoIn").length())
    }
}
