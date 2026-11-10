package de.marne.streamplayer.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogTest {

    private val sample = """
        {
          "format": "streamplayer-catalog", "version": 1,
          "titles": [
            { "type": "series", "id": "bb", "title": "Breaking Bad", "year": 2008, "tmdb": { "id": 1396 },
              "template": "https://x.to/bb/staffel-{s}/episode-{e}",
              "seasons": [ { "number": 1, "episodes": 2 }, { "number": 2, "episodes": 3, "start": 3 },
                           { "number": 0, "episodes": 1, "template": "https://x.to/bb/special-{e}", "title": "Specials" } ],
              "episodes": [ { "season": 2, "episode": 4, "url": "https://x.to/bb/sonder" } ] },
            { "type": "movie", "title": "Inception", "year": 2010, "tmdb": "Inception", "url": "https://x.to/film/inception" },
            { "title": "Kurzform", "template": "https://x.to/k/{a}", "seasons": [3, 2] }
          ]
        }
    """.trimIndent()

    @Test fun parseAndGenerate() {
        val c = CatalogJson.parse(sample)
        assertEquals(3, c.titles.size)
        val bb = c.titles[0]
        assertEquals(CatalogType.SERIES, bb.type)
        assertEquals(1396, bb.tmdb?.id)
        val eps = Episodes.generate(bb.template, bb.seasons, bb.episodes)
        assertEquals(6, eps.size)
        // Specials (Staffel 0) zuerst, mit eigenem Muster
        assertEquals("https://x.to/bb/special-1", eps[0].url)
        assertEquals("https://x.to/bb/staffel-1/episode-1", eps[1].url)
        // Staffel 2 zählt ab 3 weiter, Folge 4 hat eine eigene Adresse
        assertEquals(listOf(3, 4, 5), eps.filter { it.season == 2 }.map { it.number })
        assertEquals("https://x.to/bb/sonder", eps.first { it.season == 2 && it.number == 4 }.url)

        val movie = c.titles[1]
        assertEquals(CatalogType.MOVIE, movie.type)
        assertEquals("Inception", movie.tmdb?.query)
        assertEquals("inception-2010", movie.id)

        val k = c.titles[2]
        assertEquals(CatalogType.SERIES, k.type)        // ohne „type“, aber mit Muster
        val kEps = Episodes.generate(k.template, k.seasons, k.episodes)
        assertEquals("https://x.to/k/4", kEps[3].url)   // absolute Zählung über Staffeln
        assertEquals(2, kEps[3].season)
    }

    @Test fun roundTrip() {
        val c = CatalogJson.parse(sample)
        val again = CatalogJson.parse(CatalogJson.write(c))
        assertEquals(c, again)
    }

    @Test fun helpfulErrors() {
        fun err(json: String): String = try { CatalogJson.parse(json); fail("kein Fehler"); "" } catch (e: CatalogException) { e.message!! }
        assertTrue(err("{kaputt").startsWith("Keine gültige JSON-Datei"))
        assertTrue(err("""{"titles":[{"type":"buch","title":"x"}]}""").contains("Eintrag 1: Unbekannter Typ"))
        assertTrue(err("""{"titles":[{"type":"series","title":"x","template":"https://x.to/a","seasons":[2]}]}""").contains("ohne Folgennummer"))
        assertTrue(err("""{"version":9,"titles":[]}""").contains("neuer als diese App"))
        // Ein reines Array ist auch erlaubt
        assertEquals(1, CatalogJson.parse("""[{"type":"movie","title":"A","url":"https://x.to/a"}]""").titles.size)
    }

    @Test fun lenientForWorkInProgress() {
        val wip = Catalog(listOf(CatalogTitle("neue-serie", CatalogType.SERIES, "", template = "https://x.to/a")))
        val text = CatalogJson.write(wip)
        assertEquals("", CatalogJson.parse(text, strict = false).titles.single().title)
        assertTrue(try { CatalogJson.parse(text); false } catch (e: CatalogException) { true })
    }

    @Test fun skipBrokenEntries() {
        val (c, problems) = CatalogJson.parseReport("""{"titles":[{"type":"movie","title":"A","url":"https://x.to/a"},{"type":"series"}]}""")
        assertEquals(1, c.titles.size)
        assertEquals(listOf("Eintrag 2: „title“ fehlt."), problems)
    }

    @Test fun patterns() {
        assertEquals("https://x.to/serie/abc/staffel-{s}/episode-{e}", Pattern.derive("https://x.to/serie/abc/staffel-1/episode-1"))
        assertEquals("https://x.to/show-s{ss}e{ee}.html", Pattern.derive("https://x.to/show-s01e05.html"))
        assertEquals("https://x.to/anime/naruto/{s}/{e}", Pattern.derive("https://x.to/anime/naruto/1/3"))
        assertNull(Pattern.derive("https://x.to/watch/movie"))
        assertEquals("https://x.to/s02e007-a012", Pattern.fill("https://x.to/s{ss}e{eee}-a{aaa}", 2, 7, 12))
        assertTrue(Pattern.hasEpisode("x/{aa}"))
    }

    @Test fun tmdbParsing() {
        val search = JSONObject("""{"results":[{"id":1396,"name":"Breaking Bad","original_name":"Breaking Bad","first_air_date":"2008-01-20",
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg","overview":"Ein Lehrer …"}]}""")
        val r = Tmdb.parseSearch(search, Tmdb.Kind.TV).single()
        assertEquals(2008, r.year)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", r.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", r.backdrop)

        val details = Tmdb.parseDetails(JSONObject("""{"id":1396,"name":"Breaking Bad","episode_run_time":[47],
            "seasons":[{"season_number":0,"episode_count":9,"name":"Specials"},{"season_number":1,"episode_count":7,"name":"Staffel 1"}]}"""), Tmdb.Kind.TV)
        assertEquals(47, details.runtimeMin)
        assertEquals(listOf(0 to 9, 1 to 7), details.seasons.map { it.number to it.episodeCount })

        val season = Tmdb.parseSeason(JSONObject("""{"episodes":[{"season_number":1,"episode_number":1,"name":"Pilot","still_path":"/s.jpg","runtime":58}]}"""))
        assertEquals("Pilot", season.single().name)
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", season.single().still)

        val movie = Tmdb.parseDetails(JSONObject("""{"id":27205,"title":"Inception","release_date":"2010-07-15","runtime":148,"poster_path":null}"""), Tmdb.Kind.MOVIE)
        assertEquals(148, movie.runtimeMin)
        assertNull(movie.result.poster)
    }
}
