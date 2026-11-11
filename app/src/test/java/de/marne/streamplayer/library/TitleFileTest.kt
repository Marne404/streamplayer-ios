package de.marne.streamplayer.library

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Anlass (1.13.4): OutOfMemoryError beim Speichern einer Bibliothek mit 2500 Titeln. */
class TitleFileTest {
    private val titles = (1..50).map { i ->
        LibTitle(
            id = "t$i", kind = TitleKind.MOVIE, title = "Titel $i, mit „Anführung“\nund Umbruch", pageUrl = "https://x/$i",
            description = "Zeile 1\nZeile 2 – ümlaut, \"quote\", [klammer], {geschweift}", alsoIn = listOf("main"),
            episodeInfo = mapOf("1:1" to EpInfo("Folge\n1", "Inhalt,\nmit Komma]", null, 20)), addedAt = i.toLong(),
        )
    }

    @Test fun zeilenweiseHinUndZurueck() {
        val f = File.createTempFile("titles", ".json")
        f.bufferedWriter().use { LibraryStore.writeTitles(titles, it) }
        assertEquals(titles, LibraryStore.readTitles(f))
        // Gültiges JSON bleibt es auch (Sicherungen, ältere Versionen).
        assertEquals(50, JSONArray(f.readText()).length())
        f.delete()
    }

    @Test fun alteDateiInEinerZeile() {
        val f = File.createTempFile("titles", ".json")
        f.writeText(JSONArray().apply { titles.forEach { put(it.toJson()) } }.toString())
        assertEquals(titles, LibraryStore.readTitles(f))
        f.delete()
    }

    @Test fun leer() {
        val f = File.createTempFile("titles", ".json")
        f.bufferedWriter().use { LibraryStore.writeTitles(emptyList(), it) }
        assertEquals(emptyList<LibTitle>(), LibraryStore.readTitles(f))
        f.delete()
    }
}
