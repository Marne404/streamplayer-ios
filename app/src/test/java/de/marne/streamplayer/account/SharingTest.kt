package de.marne.streamplayer.account

import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.TitleKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anlass (1.12.0): Bibliotheken teilen – Einladungscodes erkennen, nichts Privates mitschicken. */
class SharingTest {
    @Test fun codeAusNachrichtLinkOderAllein() {
        val code = Sharing.newCode()
        assertEquals(22, code.length)
        assertEquals(code, Sharing.codeIn(Sharing.inviteText(Library("x", "Anime"), code)))
        assertEquals(code, Sharing.codeIn("streamplayer://join/$code"))
        assertEquals(code, Sharing.codeIn("  $code "))
        assertNull(Sharing.codeIn("hallo, wie gehts?"))
    }

    /** Anlass (1.13.2): Beim Abtippen ging die Einladung verloren – Groß/Klein, Leerzeichen dazwischen. */
    @Test fun neueCodesSindAbtippbar() {
        repeat(50) { assertTrue(Sharing.newCode().all { it.isUpperCase() || it.isDigit() }) }
        val code = Sharing.newCode()
        val typed = code.lowercase().chunked(4).joinToString(" ")
        // Erkannt wird der getippte Code; join() versucht ihn dann auch in Großbuchstaben.
        assertEquals(code, Sharing.codeIn(typed)?.uppercase())
        assertEquals(code, Sharing.codeIn(code.chunked(4).joinToString("-")))
        // Alte Codes mit Kleinbuchstaben bleiben gültig.
        assertEquals("cjwUabcdefghijkmnpqrst", Sharing.codeIn("Code: cjwUabcdefghijkmnpqrst"))
    }

    @Test fun geteilterTitelOhneDownloadsUndGeraetebilder() {
        val t = LibTitle(
            id = "t1", kind = TitleKind.MOVIE, title = "Film", pageUrl = "https://x/film",
            customPoster = "file:///data/posters/a.jpg", poster = "https://image.tmdb.org/t/p/w500/p.jpg",
            offline = mapOf("m:t1" to "content://1"), libraryId = "anime",
        )
        val o = JSONObject(SyncCodec.sharedTitleJson(t))
        assertFalse(o.has("offline"))
        assertFalse(o.has("library"))
        assertFalse(o.has("customPoster") && !o.isNull("customPoster"))
        assertTrue(o.getString("poster").startsWith("https://"))
    }

    @Test fun bibliothekMitTeilenUeberlebtJson() {
        val l = Library("a", "Anime", 2, shared = true, invite = "abc")
        assertEquals(l, Library.fromJson(l.toJson()))
        val j = Library("b", "Serien", 1, joined = true, owner = "Alex")
        assertEquals(j, Library.fromJson(j.toJson()))
        assertTrue(j.readOnly)
    }
}
