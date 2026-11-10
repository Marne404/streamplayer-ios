package de.marne.streamplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    @Test fun deriveNamedParts() {
        assertEquals("https://x.to/serie/abc/staffel-{s}/episode-{e}", Pattern.derive("https://x.to/serie/abc/staffel-1/episode-1"))
        assertEquals("https://x.to/watch/show/season-{s}/folge-{e}?lang=de", Pattern.derive("https://x.to/watch/show/season-2/folge-7?lang=de"))
        assertEquals("https://x.to/show-s{ss}e{ee}.html", Pattern.derive("https://x.to/show-s01e05.html"))
    }

    @Test fun deriveBareNumbers() {
        assertEquals("https://x.to/anime/naruto/{s}/{e}", Pattern.derive("https://x.to/anime/naruto/1/3"))
        assertEquals("https://x.to/stream/{e}", Pattern.derive("https://x.to/stream/12"))
        assertNull(Pattern.derive("https://x.to/watch/movie"))
        assertNull("Host bleibt unberührt", Pattern.derive("https://cdn2.x.to"))
    }

    @Test fun fillAndEpisodes() {
        val t = LibTitle("id", TitleKind.SERIES, "Show", null, template = "https://x.to/s{ss}e{ee}", seasons = listOf(2, 3))
        val eps = t.episodes()
        assertEquals(5, eps.size)
        assertEquals("https://x.to/s01e01", eps.first().url)
        assertEquals("https://x.to/s02e03", eps.last().url)
        assertEquals(Episode(2, 1, "https://x.to/s02e01"), t.next(eps[1]))
        assertNull(t.next(eps.last()))
        assertEquals(listOf(12, 8, 10), Pattern.parseSeasons("12, 8 10"))
    }

    @Test fun continueAndNextUp() {
        val show = LibTitle("s", TitleKind.SERIES, "Show", null, template = "https://x.to/{s}/{e}", seasons = listOf(3))
        val movie = LibTitle("m", TitleKind.MOVIE, "Film", "https://x.to/film")
        val progress = mapOf(
            "e:s:1:1" to Progress(2_400_000, 2_400_000, finished = true, updatedAt = 10),
            "m:m" to Progress(600_000, 6_000_000, finished = false, updatedAt = 20),
        )
        val shelf = Shelves.continueWatching(listOf(show, movie), progress)
        assertEquals(2, shelf.size)
        assertEquals("m", shelf[0].title.id)                  // zuletzt bewegt zuerst
        assertTrue(shelf[1].isNextUp)
        assertEquals(2, shelf[1].episode!!.number)           // nach Folge 1 kommt Folge 2

        val (next, p) = Shelves.nextFor(show, progress)!!
        assertEquals(2, next.number)
        assertNull(p)
        assertEquals(1, Shelves.nextFor(show, emptyMap())!!.first.number)
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
        assertEquals("Kurz.", m.description)
        assertFalse(MetaFetcher.parse("<title>T</title>", "https://x.to").title.isNullOrEmpty())
    }
}
