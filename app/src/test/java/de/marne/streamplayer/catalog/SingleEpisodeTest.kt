package de.marne.streamplayer.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Anlass (1.12.0): Beim Massen-Import wurden Filme, deren Seite wie eine Serie gebaut ist
 * (Filmseite → „Episode 1“), als Serie mit einer Folge übernommen – und TMDB fand sie nie.
 */
class SingleEpisodeTest {
    private fun series(seasons: List<SeasonSpec>) = CatalogTitle(
        id = "x", type = CatalogType.SERIES, title = "Ein Film", template = "https://s.example/film/x/episode-{e}", seasons = seasons,
    )

    @Test fun eineFolgeWirdFilm() {
        val m = series(listOf(SeasonSpec(1, 1))).singleEpisodeAsMovie()
        assertEquals(CatalogType.MOVIE, m.type)
        assertEquals("https://s.example/film/x/episode-1", m.url)
        assertNull(m.template)
        assertEquals(emptyList<SeasonSpec>(), m.seasons)
    }

    @Test fun mehrereFolgenOderUnbekanntBleibenSerie() {
        val two = series(listOf(SeasonSpec(1, 2)))
        assertSame(two, two.singleEpisodeAsMovie())
        // Noch keine Staffeln (TMDB trägt sie später nach): nicht anfassen.
        val none = series(emptyList())
        assertSame(none, none.singleEpisodeAsMovie())
    }
}
