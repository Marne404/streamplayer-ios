package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.SeasonSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    private val show = LibTitle(
        "s", TitleKind.SERIES, "Show", null, template = "https://x.to/{s}/{e}",
        seasons = listOf(SeasonSpec(1, 3), SeasonSpec(2, 2, start = 4)),
        episodeInfo = mapOf("1:1" to EpInfo("Pilot", null, "https://img/1.jpg", 47)),
    )

    @Test fun episodesWithInfo() {
        val eps = show.episodes()
        assertEquals(5, eps.size)
        assertEquals("Pilot", eps[0].title)
        assertEquals("https://img/1.jpg", eps[0].still)
        assertEquals(listOf(4, 5), eps.filter { it.season == 2 }.map { it.number })
        assertEquals(Episode(2, 4, 4, "https://x.to/2/4"), show.next(eps[2]))
        assertNull(show.next(eps.last()))
    }

    @Test fun continueAndNextUp() {
        val movie = LibTitle("m", TitleKind.MOVIE, "Film", "https://x.to/film")
        val progress = mapOf(
            "e:s:1:1" to Progress(2_400_000, 2_400_000, finished = true, updatedAt = 10),
            "m:m" to Progress(600_000, 6_000_000, finished = false, updatedAt = 20),
        )
        val shelf = Shelves.continueWatching(listOf(show, movie), progress)
        assertEquals(2, shelf.size)
        assertEquals("m", shelf[0].title.id)
        assertTrue(shelf[1].isNextUp)
        assertEquals(2, shelf[1].episode!!.number)
        val (next, p) = Shelves.nextFor(show, progress)!!
        assertEquals(2, next.number)
        assertNull(p)
        assertEquals(1, Shelves.nextFor(show, emptyMap())!!.first.number)
    }

    @Test fun imagesPreferCatalogOverTmdb() {
        val t = LibTitle("x", TitleKind.MOVIE, "X", null, poster = null, backdrop = "b.jpg",
            tmdb = TmdbInfo(1, "X", "tp.jpg", "tb.jpg", "TMDB-Text", 2001, 90), description = null)
        assertEquals("tp.jpg", t.cover)             // kein Katalog-Poster → TMDB
        assertEquals("b.jpg", t.wide)               // Katalog-Hintergrund gewinnt
        assertEquals("TMDB-Text", t.text)
        assertEquals(90 * 60_000L, t.runtimeMs)
        assertEquals("eigen.jpg", t.copy(customPoster = "eigen.jpg", poster = "p.jpg").cover)
    }

    @Test fun jsonRoundTripAndLegacy() {
        val again = LibTitle.fromJson(show.toJson())
        assertEquals(show.copy(addedAt = again.addedAt), again)
        // Stand 1.0: Staffeln als Zahlen, Bild als „image“
        val legacy = LibTitle.fromJson(JSONObject("""{"id":"a","kind":"SERIES","title":"T","template":"https://x.to/{e}","seasons":[2,3],"image":"i.jpg"}"""))
        assertEquals(listOf(SeasonSpec(1, 2), SeasonSpec(2, 3)), legacy.seasons)
        assertEquals("i.jpg", legacy.backdrop)
    }

    @Test fun keys() {
        assertEquals(Triple("abc", 2, 5), Keys.parse("e:abc:2:5"))
        assertEquals(Triple("abc", null, null), Keys.parse("m:abc"))
        assertNull(Keys.parse("x"))
    }

    @Test fun metaParse() {
        val m = MetaFetcher.parse(
            """<html><head><title>Fallback</title><meta property="og:title" content="Folge 1 &amp; mehr">
               <meta content="/img/p.jpg" property="og:image"><meta name="description" content="Kurz."></head></html>""",
            "https://x.to/a/b",
        )
        assertEquals("Folge 1 & mehr", m.title)
        assertEquals("https://x.to/img/p.jpg", m.image)
        assertFalse(MetaFetcher.parse("<title>T</title>", "https://x.to").title.isNullOrEmpty())
    }
}
