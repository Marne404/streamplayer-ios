package de.marne.streamplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Anlass (1.12.0): Banner kam von TMDB, Cover und Beschreibung blieben die der Webseite. */
class SiteInfoTest {
    private val tm = TmdbInfo(1, "Film", "https://image.tmdb.org/t/p/w500/p.jpg", "https://image.tmdb.org/t/p/w1280/b.jpg", "Von TMDB.", 2020, 100)

    @Test fun seitenbildVerliertGegenTmdb() {
        val t = LibTitle("a", TitleKind.MOVIE, "Film", "https://s.example/film", poster = "https://s.example/og.jpg", description = "Von der Seite.", tmdb = tm)
        assertEquals(tm.poster, t.cover)
        assertEquals("Von TMDB.", t.text)
        assertEquals(tm.backdrop, t.wide)
    }

    @Test fun eigenesUndTmdbAusDemKatalogBleiben() {
        val own = LibTitle("b", TitleKind.MOVIE, "Film", null, poster = "file:///data/posters/x.jpg", description = "Eigener Text.", tmdb = tm)
        assertEquals("file:///data/posters/x.jpg", own.cover)
        assertEquals("Eigener Text.", own.text)
        val custom = LibTitle("c", TitleKind.MOVIE, "Film", null, poster = "https://s.example/og.jpg", customPoster = "file:///p.jpg", tmdb = tm)
        assertEquals("file:///p.jpg", custom.cover)
    }

    @Test fun ohneTmdbBleibtDieSeite() {
        val t = LibTitle("d", TitleKind.MOVIE, "Film", null, poster = "https://s.example/og.jpg", description = "Von der Seite.")
        assertEquals("https://s.example/og.jpg", t.cover)
        assertEquals("Von der Seite.", t.text)
    }
}
