package de.marne.streamplayer.importer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * „Ganze Übersicht“: nachgebaute Katalogseiten, wie sie üblich sind – keine echte Seite.
 * Festgehalten wird, welche Verweise als Titel zählen und welche Navigation sind.
 */
class OverviewTest {

    private class FakeWeb(private val pages: Map<String, String>, private val status: Map<String, Int> = emptyMap()) : Fetcher {
        val calls = mutableListOf<String>()
        override suspend fun fetch(url: String): Fetched {
            calls += url
            status[url]?.let { return Fetched(url, it, "") }
            val html = pages[url] ?: return Fetched(url, 404, "")
            return Fetched(url, 200, html)
        }
    }

    private fun html(title: String, body: String, head: String = "") =
        "<!doctype html><html><head><title>$title</title>$head</head><body>$body</body></html>"

    private fun a(href: String, text: String = "x") = "<a href=\"$href\">$text</a>"
    private fun card(href: String, name: String) = "<div class=\"card\"><a href=\"$href\"><img src=\"/img/$name.jpg\" alt=\"$name\"></a><a href=\"$href\">$name</a></div>"

    // Kopf, Seitenleiste und Fuß, wie sie Katalogseiten haben: Rubriken, Genres, A–Z, Konto, Rechtliches.
    private val chrome = listOf(
        a("/", "Start"), a("/serien", "Serien"), a("/filme", "Filme"), a("/neu", "Neu"), a("/beliebt", "Beliebt"),
        a("/genre/action", "Action"), a("/genre/drama", "Drama"), a("/genre/comedy", "Komödie"), a("/genre/krimi", "Krimi"),
        a("/serien/a", "A"), a("/serien/b", "B"), a("/serien/c", "C"), a("/serien/d", "D"),
        a("/login", "Anmelden"), a("/register", "Registrieren"), a("/impressum", "Impressum"), a("/datenschutz", "Datenschutz"),
        a("/kontakt", "Kontakt"), a("/img/logo.png", "Logo"),
        // „Neue Folgen“: Folgen verschiedener Serien – keine Titel.
        a("/serie/alpha/staffel-2/episode-5", "Alpha 2x5"), a("/serie/beta/staffel-1/episode-9", "Beta 1x9"),
    ).joinToString("")

    private val names = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta")

    @Test fun cardsAmongNavigation() {
        val body = chrome + names.joinToString("") { card("/serie/${it.lowercase()}", it) }
        val page = Page.parse(html("Alle Serien | Seite", body), "https://k.test/serien")
        val found = Overview.titles(page)
        assertEquals(names.map { "https://k.test/serie/${it.lowercase()}" }, found.map { it.url })
        assertEquals(names, found.map { it.name })
    }

    // Anlass (Testseite im Emulator): Konto und Rechtliches lagen in derselben Familie wie die
    // Titel (/katalog/login/, /katalog/impressum/) und landeten mit in der Liste.
    @Test fun navigationInTitleFamily() {
        val nav = a("/katalog/login/", "Anmelden") + a("/katalog/impressum/", "Impressum") + a("/katalog/neu/", "Neu")
        val body = nav + listOf("nordlicht", "hafenwache", "sternwarte").joinToString("") { card("/katalog/$it/", it) }
        val found = Overview.titles(Page.parse(html("Alle", body), "https://k.test/katalog/alle/"))
        assertEquals(listOf("nordlicht", "hafenwache", "sternwarte").map { "https://k.test/katalog/$it/" }, found.map { it.url })
    }

    @Test fun mixedSeriesAndMovies() {
        val series = (1..6).joinToString("") { card("/serie/show-$it", "Show $it") }
        val movies = (1..5).joinToString("") { card("/film/movie-$it.html", "Movie $it") }
        val page = Page.parse(html("Neu", chrome + series + movies), "https://k.test/neu")
        val found = Overview.titles(page).map { it.url }
        assertEquals(11, found.size)
        assertTrue("https://k.test/film/movie-3.html" in found)
        assertTrue("https://k.test/serie/show-6" in found)
    }

    @Test fun episodeListIsNoCatalog() {
        // Eine Serienseite: Folgen einer Serie sind keine Titel.
        val body = chrome + (1..12).joinToString("") { a("/serie/alpha/staffel-1/episode-$it", "Folge $it") }
        assertTrue(Overview.titles(Page.parse(html("Alpha", body), "https://k.test/serie/alpha")).isEmpty())
    }

    @Test fun numericIdsInPath() {
        val body = chrome + (1..5).joinToString("") { card("/title/${1000 + it}/name-$it", "Name $it") }
        val found = Overview.titles(Page.parse(html("Liste", body), "https://k.test/liste"))
        assertEquals(5, found.size)
        assertEquals("https://k.test/title/1001/name-1", found.first().url)
    }

    @Test fun jsonLdItemList() {
        val ld = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"ItemList","itemListElement":[
            {"@type":"ListItem","position":1,"url":"https://k.test/x/eins"},
            {"@type":"ListItem","position":2,"item":{"@type":"TVSeries","url":"/x/zwei","name":"Zwei"}},
            {"@type":"ListItem","position":3,"url":"https://k.test/x/drei"},
            {"@type":"ListItem","position":4,"url":"https://other.test/x/vier"}]}</script>"""
        val body = chrome + a("/x/eins", "Eins") + a("/x/drei", "Drei")
        val found = Overview.titles(Page.parse(html("Liste", body, ld), "https://k.test/liste"))
        assertEquals(listOf("https://k.test/x/eins", "https://k.test/x/zwei", "https://k.test/x/drei"), found.map { it.url })
        assertEquals(listOf("Eins", "Zwei", "Drei"), found.map { it.name })
    }

    @Test fun nextPage() {
        fun next(url: String, body: String, head: String = "") = Overview.next(Page.parse(html("L", body, head), url), emptySet())
        // rel="next"
        assertEquals("https://k.test/serien?page=2", next("https://k.test/serien", "", "<link rel=\"next\" href=\"/serien?page=2\">"))
        // Seitenzahl + 1, nicht irgendeine Seite und nicht die Seite einer anderen Liste
        val pager = a("/genre/action/seite/2/", "2") + a("/serien/seite/3/", "3") + a("/serien/seite/2/", "2")
        assertEquals("https://k.test/serien/seite/2/", next("https://k.test/serien/", pager))
        assertEquals("https://k.test/serien/seite/3/", next("https://k.test/serien/seite/2/", pager))
        assertEquals("https://k.test/serien?sort=neu&page=3", next("https://k.test/serien?page=2&sort=neu", a("/serien?sort=neu&page=3", "3")))
        // Nur ein „Weiter“
        assertEquals("https://k.test/liste/xyz", next("https://k.test/liste", a("/liste/xyz", "Weiter")))
        assertNull(next("https://k.test/serien/", a("/serien/seite/5/", "5")))
    }

    @Test fun scanFollowsPagesAndStops() = runBlocking {
        fun listing(n: Int, from: Int, nextHref: String?) =
            html("Serien – Seite $n", chrome + (from until from + 4).joinToString("") { card("/serie/show-$it", "Show $it") } + (nextHref?.let { a(it, "Weiter") } ?: ""))
        val web = FakeWeb(
            mapOf(
                "https://k.test/serien" to listing(1, 1, "/serien?page=2"),
                "https://k.test/serien?page=2" to listing(2, 5, "/serien?page=3"),
                "https://k.test/serien?page=3" to listing(3, 9, null),
            ),
        )
        val all = Overview.scan("https://k.test/serien", web, null, maxPages = 10, maxTitles = 100)
        assertEquals(12, all.titles.size)
        assertEquals(3, all.pages)
        assertFalse(all.more)

        val two = Overview.scan("https://k.test/serien", FakeWeb(mapOf("https://k.test/serien" to listing(1, 1, "/serien?page=2"), "https://k.test/serien?page=2" to listing(2, 5, "/serien?page=3"))), null, maxPages = 2, maxTitles = 100)
        assertEquals(8, two.titles.size)
        assertTrue(two.more)

        val few = Overview.scan("https://k.test/serien", web, null, maxPages = 10, maxTitles = 6)
        assertEquals(6, few.titles.size)
    }

    // Anlass: Seite 2 verlinkt zurück auf die Übersicht – in derselben Familie wie die Titel.
    @Test fun listingPagesAreNoTitles() = runBlocking {
        val p1 = html("Alles", (1..4).joinToString("") { card("/liste/titel-$it", "Titel $it") } + a("/liste/alles?page=2", "2"))
        val p2 = html("Alles 2", (5..8).joinToString("") { card("/liste/titel-$it", "Titel $it") } + card("/liste/alles", "Alle Titel"))
        val scan = Overview.scan("https://k.test/liste/alles", FakeWeb(mapOf("https://k.test/liste/alles" to p1, "https://k.test/liste/alles?page=2" to p2)), null, 5, 100)
        assertEquals((1..8).map { "https://k.test/liste/titel-$it" }, scan.titles.map { it.url })
    }

    @Test fun scanWithoutListFails() = runBlocking {
        val web = FakeWeb(mapOf("https://k.test/serie/alpha" to html("Alpha", chrome)))
        try {
            Overview.scan("https://k.test/serie/alpha", web, null, maxPages = 5, maxTitles = 50)
            fail("Keine Liste – sollte scheitern")
        } catch (e: FetchException) {
            assertTrue(e.message!!.startsWith("Auf der Seite ist keine Liste"))
        }
    }

    @Test fun robots() {
        val r = Robots.parse(
            """
            # Kommentar
            User-agent: Googlebot
            Disallow: /

            User-agent: *
            User-agent: andere
            Disallow: /suche
            Disallow: /serie/*/download$
            Disallow: /privat/
            Allow: /privat/offen/
            Crawl-delay: 2.5
            """.trimIndent(),
        )
        assertTrue(r.allowed("https://k.test/serie/alpha"))
        assertFalse(r.allowed("https://k.test/suche?q=x"))
        assertFalse(r.allowed("https://k.test/serie/alpha/download"))
        assertTrue(r.allowed("https://k.test/serie/alpha/download/info"))
        assertFalse(r.allowed("https://k.test/privat/x"))
        assertTrue(r.allowed("https://k.test/privat/offen/x"))
        assertEquals(2500L, r.crawlDelayMs)

        val own = Robots.parse("User-agent: *\nDisallow:\n\nUser-agent: Streamplayer\nDisallow: /")
        assertFalse(own.allowed("https://k.test/x"))
        assertTrue(Robots.parse("<html>Nicht gefunden</html>").allowed("https://k.test/x"))
    }

    @Test fun throttleBudgetRobotsAndSlowDown() = runBlocking {
        var clock = 0L
        val sleeps = mutableListOf<Long>()
        val web = FakeWeb(
            mapOf(
                "https://k.test/robots.txt" to "User-agent: *\nDisallow: /privat/\nCrawl-delay: 1",
                "https://k.test/a" to "a", "https://k.test/b" to "b", "https://k.test/c" to "c",
            ),
            status = mapOf("https://k.test/voll" to 429),
        )
        val t = Throttle(web, budget = 4, pauseMs = 300, backoffMs = 5_000, now = { clock }, sleep = { sleeps += it; clock += it })
        val f = t.wrap(web)
        assertEquals("a", f.fetch("https://k.test/a").html)
        // robots.txt zuerst, Crawl-delay hebt die Pause von 0,3 auf 1 s.
        assertEquals(listOf("https://k.test/robots.txt", "https://k.test/a"), web.calls)
        assertEquals(1000L, t.pauseMs)
        assertEquals(2, t.used)
        try {
            f.fetch("https://k.test/privat/x"); fail("robots.txt verbietet das")
        } catch (e: FetchException) {
            assertEquals(Throttle.DISALLOWED, e.message)
        }
        assertEquals(2, t.used)   // Verbotenes kostet nichts
        f.fetch("https://k.test/b")
        f.fetch("https://k.test/c")
        assertEquals(0, t.remaining)
        try {
            f.fetch("https://k.test/a"); fail("Budget ist aufgebraucht")
        } catch (e: FetchException) {
            assertEquals(Throttle.Stop.BUDGET, t.stop)
        }
        assertTrue(sleeps.all { it <= 1000 })

        // Nächster Durchgang; die Seite bremst zweimal hintereinander: Pause verdoppelt, dann Schluss.
        t.refill(10)
        sleeps.clear()
        assertEquals(429, f.fetch("https://k.test/voll").status)
        assertEquals(Throttle.Stop.SLOW_DOWN, t.stop)
        assertEquals(2000L, t.pauseMs)
        assertTrue(5_000L in sleeps)
        try {
            f.fetch("https://k.test/a"); fail("Nach 429 ist Schluss")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test fun importerReportsUnreadSeasons() = runBlocking {
        fun eps(s: Int) = (1..3).joinToString("") { a("/serie/foo/staffel-$s/episode-$it") }
        val tabs = (1..4).joinToString("") { a("/serie/foo/staffel-$it") }
        val site = (1..4).associate { "https://k.test/serie/foo/staffel-$it" to html("Foo", tabs + eps(it)) } + ("https://k.test/serie/foo" to html("Foo", tabs + eps(1)))
        // Übersicht + Staffel 2, dann ist bei 2 Abrufen Schluss.
        val d = Importer(FakeWeb(site), pauseMs = 0, maxPages = 2).analyze("https://k.test/serie/foo")
        assertEquals(listOf(3, 4), d.unread)
        assertTrue(d.notes.any { it.warning && it.text == "Abrufgrenze erreicht: Staffeln 3, 4 nicht gelesen." })
        assertTrue(Importer(FakeWeb(site), pauseMs = 0).analyze("https://k.test/serie/foo").unread.isEmpty())
    }
}
