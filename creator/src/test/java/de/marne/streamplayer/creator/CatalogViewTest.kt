package de.marne.streamplayer.creator

import de.marne.streamplayer.catalog.Catalog
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.TmdbRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anlass: Nach einem Import von 11 000 Serien stürzte der Creator ab und ließ sich nicht mehr
 * öffnen – die Liste stand komplett in einer Karte. Jetzt wird sie im Hintergrund aufbereitet und
 * Zeile für Zeile gezeichnet; das hier prüft Tempo, Gliederung und eindeutige Schlüssel.
 */
class CatalogViewTest {
    private fun big(n: Int) = Catalog(List(n) { i ->
        val name = listOf("Ärger", "Zorro", "1899", "Breaking Bad", "the office", "Дом", "Lost")[i % 7] + " $i"
        CatalogTitle(
            "s$i", if (i % 10 == 0) CatalogType.MOVIE else CatalogType.SERIES, name, 2000 + i % 25,
            tmdb = if (i % 3 == 0) null else TmdbRef(i), template = "https://x.to/s$i/{s}/{e}", seasons = listOf(SeasonSpec(1, 10)),
        )
    } + CatalogTitle("s5", CatalogType.SERIES, "Doppelte ID"))

    @Test fun largeCatalog() {
        val c = big(11_000)
        CatalogView.of(c, CatalogType.SERIES, CatalogView.Filter.ALL, CatalogView.Sort.AZ, "") // warm
        val t0 = System.nanoTime()
        val v = CatalogView.of(c, CatalogType.SERIES, CatalogView.Filter.ALL, CatalogView.Sort.AZ, "")
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("11 000 Titel: $ms ms")
        assertTrue("zu langsam: $ms ms", ms < 1_500)
        assertEquals(9_901, v.total)
        assertEquals(1_100, v.movies)
        // Eindeutige Schlüssel, auch bei doppelter ID.
        assertEquals(v.rows.size, v.rows.map { it.key }.toSet().size)
        // „#“ (Ziffern, andere Schriften) zuerst und nur einmal, dann Buchstaben in Reihenfolge.
        val labels = v.letters.map { it.first }
        assertEquals("#", labels.first())
        assertEquals(labels, labels.distinct())
        assertEquals(labels.drop(1), labels.drop(1).sorted())
        assertTrue("Ärger unter A", v.rows.filterIsInstance<CatalogView.Row.Item>().first { it.title.title.startsWith("Ärger") }.let { item ->
            val i = v.rows.indexOf(item)
            v.letters.last { it.second <= i }.first == "A"
        })
        // Abschnitte: erster und letzter Eintrag je Buchstabe gerundet.
        val firstA = v.rows[v.letters.first { it.first == "A" }.second + 1] as CatalogView.Row.Item
        assertTrue(firstA.first)

        val found = CatalogView.of(c, CatalogType.SERIES, CatalogView.Filter.ALL, CatalogView.Sort.NEW, "arger 12")
        assertTrue(found.rows.all { (it as CatalogView.Row.Item).title.title.startsWith("Ärger 12") })
        val unlinked = CatalogView.of(c, CatalogType.SERIES, CatalogView.Filter.NO_TMDB, CatalogView.Sort.NEW, "")
        assertEquals(v.unlinked, unlinked.rows.size)
    }
}
