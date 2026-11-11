package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.SeasonSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anlass (1.12.1): Nach einem Import von ein paar tausend Links stockte die Suche bei jedem
 * Tastendruck – alles wurde jedes Mal neu normalisiert, alle Folgen neu erzeugt.
 */
class SearchIndexTest {

    private fun lib(n: Int): List<LibTitle> = (0 until n).map { i ->
        val series = i % 2 == 0
        LibTitle(
            id = "t$i", kind = if (series) TitleKind.SERIES else TitleKind.MOVIE, title = "Titel Nummer $i Ärger",
            pageUrl = if (series) null else "https://x/$i", template = if (series) "https://x/$i/{s}/{e}" else null,
            seasons = if (series) listOf(SeasonSpec(1, 30), SeasonSpec(2, 30)) else emptyList(),
            description = "Eine längere Beschreibung mit Wörtern wie Insel, Mörder, Café und Nummer $i. ".repeat(4),
            episodeInfo = if (series) (1..30).associate { "1:$it" to EpInfo("Folge $it von $i", null, null, null) } else emptyMap(),
            tmdb = TmdbInfo(i, null, null, null, null, null, null, genres = listOf("Drama", "Krimi"), cast = listOf("Darsteller ${i % 300}", "Zoë Šimić"), votes = 500),
        )
    }

    @Test fun faltetAkzenteUndZeichen() {
        assertEquals("amelie s fabelhafte welt", SearchIndex.fold("Amélie’s  fabelhafte – Welt"))
        assertEquals(" ab 2", SearchIndex.fold("¡Ab 2!").trimEnd())
        assertEquals("тест", SearchIndex.fold("Тест"))
    }

    @Test fun grosseBibliothekBleibtSchnell() {
        val titles = lib(3000)
        val t0 = System.nanoTime()
        val idx = SearchIndex.of(titles)
        val build = (System.nanoTime() - t0) / 1_000_000
        // Ein Titel geändert: nur er wird neu aufbereitet.
        val changed = titles.mapIndexed { i, t -> if (i == 7) t.copy(title = "Neu") else t }
        val t1 = System.nanoTime()
        SearchIndex.of(changed)
        val rebuild = (System.nanoTime() - t1) / 1_000_000
        val t2 = System.nanoTime()
        var hits = 0
        for (q in listOf("t", "ti", "tit", "titel", "titel n", "titel nu", "insel", "darsteller 12", "folge 3 von 4", "zoe")) hits += SearchIndex.of(changed).searchAll(q).titles.size
        val search = (System.nanoTime() - t2) / 1_000_000 / 10
        println("Aufbau $build ms, Neuaufbau $rebuild ms, je Suche $search ms")
        assertTrue(hits > 0)
        assertTrue("Suche zu langsam: $search ms", search < 150)
        assertEquals(idx.source.size, changed.size)
    }
}
