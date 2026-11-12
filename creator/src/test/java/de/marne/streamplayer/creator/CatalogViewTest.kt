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

class PickMatchTest {
    private fun r(id: Int, title: String, year: Int?, original: String? = null) =
        de.marne.streamplayer.catalog.Tmdb.Result(id, de.marne.streamplayer.catalog.Tmdb.Kind.TV, title, original, year, null, null, null)

    /** Anlass: 11 007 Serien ohne TMDB – automatisch zuordnen, aber nie das Cover einer fremden Serie. */
    @Test fun onlySafeMatches() {
        // Gleicher Name, Satzzeichen egal.
        assertEquals(2, TmdbFill.pickMatch("Pachinko - Ein einfaches Leben", 2022, listOf(r(1, "Pachinko", 2022), r(2, "Pachinko – Ein einfaches Leben", 2022)))?.id)
        // Originaltitel zählt auch.
        assertEquals(3, TmdbFill.pickMatch("Sabikui Bisco", null, listOf(r(3, "Sabikui Bisco – Der Rostfresser", 2022, original = "Sabikui Bisco")))?.id)
        // Falsches Jahr: nein.
        assertEquals(null, TmdbFill.pickMatch("S.W.A.T.", 2017, listOf(r(4, "S.W.A.T.", 1975))))
        // Ähnlich, aber nicht gleich, mehrere Treffer: lieber von Hand.
        assertEquals(null, TmdbFill.pickMatch("Lost", null, listOf(r(5, "Lost Girl", 2010), r(6, "Lost in Space", 2018))))
        // Einziger Treffer mit genau dem Jahr: ja.
        assertEquals(7, TmdbFill.pickMatch("Paartherapie mal anders", 2023, listOf(r(7, "Couples Therapy", 2023)))?.id)
        // Einziger Treffer, Titel ohne Jahr (Linklisten): ja – so blieben über 4 000 Serien liegen.
        assertEquals(8, TmdbFill.pickMatch("Saber Rider und die Star Sheriffs", null, listOf(r(8, "Saber Rider", 1987)))?.id)
        // Einziger Treffer, Jahr widerspricht: nein.
        assertEquals(null, TmdbFill.pickMatch("Painkiller", 2023, listOf(r(9, "Painkiller", 1999))))
        // Mehrere Treffer, nur einer enthält den Namen: ja.
        assertEquals(10, TmdbFill.pickMatch("Sabiiro no Armor", null, listOf(r(10, "Sabiiro no Armor: Reimei", 2024), r(11, "Armored Trooper", 1983)))?.id)
    }
}
