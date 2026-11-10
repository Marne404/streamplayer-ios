package de.marne.streamplayer.importer

import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.ui.Lang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Nachgebaute Seitenstrukturen, wie sie bei Serienseiten üblich sind – keine echte Seite.
 * Anlass: „Aus Link übernehmen“ soll ohne Regeln für einzelne Seiten auskommen; diese Tests
 * halten fest, welche Formen das allgemeine Verfahren erkennen muss.
 */
class ImporterTest {

    // Die Meldungen werden hier auf Deutsch geprüft.
    @Before fun deutsch() { Lang.current = Lang.Code.DE }

    private class FakeWeb(private val pages: Map<String, String>) : Fetcher {
        val calls = mutableListOf<String>()
        override suspend fun fetch(url: String): Fetched {
            calls += url
            val html = pages[url] ?: return Fetched(url, 404, "")
            return Fetched(url, 200, html)
        }
    }

    private fun html(title: String, body: String, head: String = "") =
        "<!doctype html><html><head><title>$title</title>$head</head><body>$body</body></html>"

    private fun links(vararg hrefs: String) = hrefs.joinToString("") { "<a href=\"$it\">x</a>" }

    // Seitenleiste mit anderen Serien – auch einer mit fast gleichem Namen. Darf nie mitgezählt werden.
    private val nav = links(
        "/", "/genre/action", "/genre/drama", "/jahr/2019", "/serie/andere/staffel-1/episode-1", "/serie/andere/staffel-1/episode-2",
        "/serie/testserie-2", "/serie/testserie-2/staffel-1/episode-1", "/serie/testserie-2/staffel-1/episode-2",
        "/serie/testserie-2/staffel-1/episode-3", "/serie/testserie-2/staffel-1/episode-4", "/serie/testserie-2/staffel-1/episode-5",
        "/serie/testserie-2/staffel-1/episode-6", "/serie/testserie-2/staffel-1/episode-7",
    )

    private fun seasonTabs() = links("/serie/testserie/staffel-1", "/serie/testserie/staffel-2", "/serie/testserie/staffel-3", "/serie/testserie/filme")
    private fun eps(season: Int, vararg n: Int) = links(*n.map { "/serie/testserie/staffel-$season/episode-$it" }.toTypedArray())

    private val siteA = mapOf(
        "https://a.test/serie/testserie" to html(
            "Testserie - Stream | A-Test",
            nav + "<h1>Testserie</h1>" + seasonTabs() + eps(1, 1, 2, 3, 4, 5) + links("/serie/testserie/filme/film-1"),
            head = """<meta property="og:site_name" content="A-Test"><meta property="og:image" content="/img/testserie.jpg">
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"TVSeries","name":"Testserie","startDate":"2019-04-01","description":"Eine Serie."}</script>""",
        ),
        "https://a.test/serie/testserie/staffel-1" to html("Testserie Staffel 1 | A-Test", nav + seasonTabs() + eps(1, 1, 2, 3, 4, 5)),
        "https://a.test/serie/testserie/staffel-2" to html("Testserie Staffel 2 | A-Test", nav + seasonTabs() + eps(2, 1, 2, 3, 4)),
        "https://a.test/serie/testserie/staffel-3" to html("Testserie Staffel 3 | A-Test", nav + seasonTabs() + eps(3, 1, 2, 3, 5, 6)),
        "https://a.test/serie/testserie/staffel-2/episode-3" to html(
            "Testserie Staffel 2 Folge 3 | A-Test",
            nav + "<h1>Testserie – Staffel 2, Folge 3</h1>" + seasonTabs() + eps(2, 1, 2, 3, 4),
        ),
    )

    @Test fun seriesWithSeasonPages() = runBlocking {
        val web = FakeWeb(siteA)
        val d = Importer(web, pauseMs = 0).analyze("https://a.test/serie/testserie")
        val t = d.title
        assertEquals(CatalogType.SERIES, t.type)
        assertEquals("Testserie", t.title)
        assertEquals(2019, t.year)
        assertEquals("https://a.test/serie/testserie/staffel-{s}/episode-{e}", t.template)
        assertEquals(listOf(0 to 1, 1 to 5, 2 to 4, 3 to 6), t.seasons.map { it.number to it.episodes })
        assertEquals("https://a.test/serie/testserie/filme/film-{e}", t.seasons.first { it.number == 0 }.template)
        assertEquals("https://a.test/img/testserie.jpg", t.poster)
        // Staffel 1 steht schon auf der Übersicht: nur Staffel 2 und 3 werden nachgeladen.
        assertEquals(listOf("https://a.test/serie/testserie", "https://a.test/serie/testserie/staffel-2", "https://a.test/serie/testserie/staffel-3"), web.calls)
        assertTrue(d.notes.any { it.warning && it.text == "Staffel 3: Folge 4 fehlt auf der Seite." })
        val urls = Episodes.generate(t.template, t.seasons, t.episodes).map { it.url }
        assertTrue("https://a.test/serie/testserie/staffel-3/episode-6" in urls)
    }

    @Test fun startFromEpisodePage() = runBlocking {
        val web = FakeWeb(siteA)
        val d = Importer(web, pauseMs = 0).analyze("https://a.test/serie/testserie/staffel-2/episode-3")
        assertEquals("Testserie", d.title.title)       // aus der Übersicht, nicht „… Folge 3“
        assertEquals("https://a.test/serie/testserie/staffel-{s}/episode-{e}", d.title.template)
        assertEquals(listOf(0 to 1, 1 to 5, 2 to 4, 3 to 6), d.title.seasons.map { it.number to it.episodes })
        assertEquals(3, web.calls.size)
        assertEquals("https://a.test/serie/testserie", web.calls[1])
    }

    @Test fun singlePagePaddedNumbers() = runBlocking {
        val list = (1..10).map { "/show/foo/foo-s01e%02d.html".format(it) } + (1..8).map { "/show/foo/foo-s02e%02d.html".format(it) }
        val web = FakeWeb(mapOf("https://b.test/show/foo" to html("Foo (2015) - B-Test", links(*list.toTypedArray()) + links("/show/bar/bar-s01e01.html"))))
        val d = Importer(web, pauseMs = 0).analyze("https://b.test/show/foo")
        assertEquals("Foo", d.title.title)
        assertEquals(2015, d.title.year)
        assertEquals("https://b.test/show/foo/foo-s{ss}e{ee}.html", d.title.template)
        assertEquals(listOf(SeasonSpec(1, 10), SeasonSpec(2, 8)), d.title.seasons)
        assertEquals(1, web.calls.size)
    }

    @Test fun absoluteNumberingWithSeasonPages() = runBlocking {
        val tabs = links("/anime/bar?season=1", "/anime/bar?season=2")
        fun folgen(r: IntRange) = links(*r.map { "/anime/bar/folge-$it" }.toTypedArray())
        val web = FakeWeb(
            mapOf(
                "https://c.test/anime/bar" to html("Bar | C-Test", tabs + folgen(1..12)),
                "https://c.test/anime/bar?season=1" to html("Bar | C-Test", tabs + folgen(1..12)),
                "https://c.test/anime/bar?season=2" to html("Bar | C-Test", tabs + folgen(13..24)),
            ),
        )
        val d = Importer(web, pauseMs = 0).analyze("https://c.test/anime/bar")
        assertEquals("https://c.test/anime/bar/folge-{e}", d.title.template)
        assertEquals(listOf(SeasonSpec(1, 12, start = 1), SeasonSpec(2, 12, start = 13)), d.title.seasons)
        val s2 = Episodes.generate(d.title.template, d.title.seasons, emptyList()).filter { it.season == 2 }
        assertEquals("https://c.test/anime/bar/folge-13", s2.first().url)
    }

    @Test fun throughCountedSplitByReference() {
        val st = Structure.analyze("https://d.test/watch/baz", listOf(Loaded("https://d.test/watch/baz", (1..24).map { "https://d.test/watch/baz/episode-$it" })))
        assertTrue(st.absolute)
        val (tpl, seasons, notes) = Importer.reconcile(st, listOf(1 to 12, 2 to 12), "TMDB", "https://d.test/watch/baz")
        assertEquals("https://d.test/watch/baz/episode-{a}", tpl)
        assertEquals(listOf(SeasonSpec(1, 12), SeasonSpec(2, 12)), seasons)
        assertEquals("Die Seite zählt durch. Staffeln von TMDB übernommen.", notes.single().text)
        val urls = Episodes.generate(tpl, seasons, emptyList())
        assertEquals("https://d.test/watch/baz/episode-13", urls.first { it.season == 2 }.url)

        // Summe passt nicht: nichts raten, nur sagen.
        val (tpl2, seasons2, notes2) = Importer.reconcile(st, listOf(1 to 12, 2 to 13), "TMDB", "https://d.test/watch/baz")
        assertEquals("https://d.test/watch/baz/episode-{e}", tpl2)
        assertEquals(listOf(SeasonSpec(1, 24)), seasons2)
        assertTrue(notes2.single().text.startsWith("Die Seite zählt durch (24 Folgen)"))
    }

    @Test fun movieFromJsonLd() = runBlocking {
        val web = FakeWeb(
            mapOf(
                "https://m.test/film/qux" to html(
                    "Qux online anschauen | M-Test", nav,
                    head = """<script type="application/ld+json">[{"@type":"WebSite","name":"M-Test"},{"@type":"Movie","name":"Qux","datePublished":"2010-07-15","image":{"@type":"ImageObject","url":"https://m.test/qux.jpg"}}]</script>""",
                ),
            ),
        )
        val d = Importer(web, pauseMs = 0).analyze("Schau mal: https://m.test/film/qux")
        assertEquals(CatalogType.MOVIE, d.title.type)
        assertEquals("Qux", d.title.title)
        assertEquals(2010, d.title.year)
        assertEquals("https://m.test/film/qux", d.title.url)
        assertEquals("https://m.test/qux.jpg", d.title.poster)
        assertNull(d.title.template)
    }

    @Test fun idsAreNotEpisodes() {
        val st = Structure.analyze(
            "https://e.test/watch/qux",
            listOf(Loaded("https://e.test/watch/qux", listOf("https://e.test/watch/qux/123456", "https://e.test/watch/qux/123457", "https://e.test/watch/qux/123999"))),
        )
        assertFalse(st.found)
    }

    // Anlass: Ein einzelner „Erste Folge“-Verweis auf der Übersicht sah aus wie eine fertige Staffel mit einer Folge.
    @Test fun singleFirstEpisodeLinkLoadsThatEpisode() = runBlocking {
        val web = FakeWeb(
            mapOf(
                "https://f.test/serie/quux" to html("Quux", nav + links("/serie/quux/staffel-1/episode-1")),
                "https://f.test/serie/quux/staffel-1/episode-1" to html("Quux 1x1", nav + links(*(1..7).map { "/serie/quux/staffel-1/episode-$it" }.toTypedArray())),
            ),
        )
        val d = Importer(web, pauseMs = 0).analyze("https://f.test/serie/quux")
        assertEquals(listOf(SeasonSpec(1, 7)), d.title.seasons)
    }

    // Anlass: Mit TMDB-Treffer blieb das Poster leer („holt der Player selbst“) – im Creator gab es
    // nie ein Cover, im Player nur mit eigenem TMDB-Schlüssel.
    @Test fun linkedTmdbFillsImages() {
        val info = PageInfo("Foo", 2019, "SEO-Text der Seite", "https://x.test/seite.jpg", Kind.SERIES)
        val st = Structure.analyze("https://x.test/foo", listOf(Loaded("https://x.test/foo", (1..3).map { "https://x.test/foo/episode-$it" })))
        val draft = Draft(
            "https://x.test/foo", de.marne.streamplayer.catalog.CatalogTitle("", CatalogType.SERIES, "Foo"),
            emptyList(), null, emptyList(), 1, false, info, st,
        )
        val tmdb = de.marne.streamplayer.catalog.Tmdb.Result(
            42, de.marne.streamplayer.catalog.Tmdb.Kind.TV, "Foo", null, 2019,
            "https://image.tmdb.org/t/p/w780/p.jpg", "https://image.tmdb.org/t/p/w1280/b.jpg", "Worum es geht.",
        )
        val linked = Importer.build(draft, tmdb, null).title
        assertEquals(42, linked.tmdb?.id)
        assertEquals("https://image.tmdb.org/t/p/w780/p.jpg", linked.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", linked.backdrop)
        assertEquals("Worum es geht.", linked.description)

        // TMDB ohne Poster: Bild der Seite bleibt.
        assertEquals("https://x.test/seite.jpg", Importer.build(draft, tmdb.copy(poster = null), null).title.poster)

        // Ohne Treffer: alles von der Seite.
        val plain = Importer.build(draft, null, null).title
        assertEquals("https://x.test/seite.jpg", plain.poster)
        assertNull(plain.backdrop)
        assertEquals("SEO-Text der Seite", plain.description)
    }

    @Test fun linkLists() {
        // Text: beliebig gemischt, Doppelte (www., Schrägstrich) fallen weg.
        val text = "Serien:\nhttps://x.test/serie/a\n- https://www.x.test/serie/a/ (doppelt)\nhttps://x.test/serie/b, und https://y.test/film/c."
        assertEquals(listOf("https://x.test/serie/a", "https://x.test/serie/b", "https://y.test/film/c"), LinkList.parse(text).map { it.url })

        // JSON: Array, Objekt mit „links“, Einträge mit eigenem Namen.
        assertEquals(2, LinkList.parse("""["https://x.test/a", "https://x.test/b"]""").size)
        val json = LinkList.parse("""{"links":["https://x.test/a",{"url":"https://x.test/b","title":"Bee"}],"note":"siehe https://x.test/c"}""")
        // Reihenfolge der Schlüssel hält nur Androids org.json fest, die JVM-Fassung im Test nicht.
        assertEquals(listOf(LinkItem("https://x.test/a"), LinkItem("https://x.test/b", "Bee"), LinkItem("https://x.test/c")), json.sortedBy { it.url })

        // Ein Katalog ist keine Linkliste.
        assertTrue(LinkList.isCatalog("""{"format":"streamplayer-catalog","version":1,"titles":[]}"""))
        assertFalse(LinkList.isCatalog("""{"links":["https://x.test/a"]}"""))
        assertTrue(LinkList.parse("kein Link hier").isEmpty())
    }

    @Test fun nameFromAddress() {
        assertEquals("Foo Bar", Importer.nameFromUrl("https://x.test/serie/foo-bar/staffel-2/episode-3"))
        assertEquals("Foo", Importer.nameFromUrl("https://x.test/foo-s01e05.html"))
        assertNull(Importer.nameFromUrl("https://x.test/"))
    }

    @Test fun titles() {
        assertEquals("Foo", Info.cleanTitle("Foo – Staffel 2 Folge 5 online anschauen | MySite", "MySite", "mysite.to"))
        assertEquals("Re:Zero - Starting Life in Another World", Info.cleanTitle("Re:Zero - Starting Life in Another World | Site", "Site", "site.org"))
        assertEquals("Sword Art Online", Info.cleanTitle("Sword Art Online - Stream | xy.to", null, "xy.to"))
        assertEquals("Foo", Info.cleanTitle("Watch Foo Online Free", null, null))
        assertEquals("Foo", Info.cleanTitle("Foo (2019) S01E05 | anisite.to", null, "anisite.to"))
        assertEquals("", Info.cleanTitle("Staffel 2", null, null))
        assertEquals("German Angst", Info.cleanTitle("German Angst", null, null))
    }

    @Test fun inputNormalization() {
        assertEquals("https://x.test/a", Importer.normalize("  https://x.test/a "))
        assertEquals("https://x.test/a?b=1", Importer.normalize("Guck mal: https://x.test/a?b=1."))
        assertNull(Importer.normalize("kein link"))
    }

    @Test fun tmdbPick() {
        fun r(id: Int, title: String, year: Int?, original: String? = null) =
            de.marne.streamplayer.catalog.Tmdb.Result(id, de.marne.streamplayer.catalog.Tmdb.Kind.TV, title, original, year, null, null, null)
        val results = listOf(r(1, "Foo", 1998), r(2, "Foo", 2019), r(3, "Foo Bar", 2019))
        assertEquals(2, Importer.pick("Foo", 2019, results)?.id)
        assertEquals(1, Importer.pick("Foo", null, results)?.id)
        assertNull(Importer.pick("Foo", 2005, results))                  // gleicher Name, falsches Jahr: lieber fragen
        assertEquals(9, Importer.pick("Shingeki no Kyojin", 2013, listOf(r(9, "Attack on Titan", 2013, "Shingeki no Kyojin")))?.id)
        assertEquals(7, Importer.pick("Frieren", null, listOf(r(7, "Frieren – Nach dem Ende der Reise", 2023)))?.id)
    }
}
