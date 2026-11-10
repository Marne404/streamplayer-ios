package de.marne.streamplayer.importer

import kotlinx.coroutines.CancellationException
import java.net.URLDecoder

/** Ein Titel auf einer Übersichtsseite: Adresse und, falls lesbar, der Name aus dem Verweis. */
data class Found(val url: String, val name: String?)

/** Ergebnis einer Übersicht: Titel in Seitenreihenfolge, gelesene Seiten, und ob es weiterginge. */
data class Scan(val titles: List<Found>, val pages: Int, val more: Boolean, val usedBrowser: Boolean)

/**
 * „Ganze Übersicht“: eine Seite mit vielen Serien oder Filmen (Katalog, Genre, Liste) lesen und
 * die Titel darauf finden – ohne Regeln für einzelne Seiten:
 *
 * 1. schema.org `ItemList` (oder `TVSeries`/`Movie` mit `url`) in JSON-LD, wenn vorhanden.
 * 2. Sonst Familien von Verweisen: Adressen derselben Seite, die sich in genau einem Pfadsegment
 *    (oder Query-Wert) unterscheiden, etwa `/serie/foo`, `/serie/bar`, `/serie/baz`. Familien
 *    mit Navigationswörtern (`genre`, `page`, `login` …), Buchstaben-Leisten (A–Z) oder
 *    Folgen-Adressen (`staffel-2`, `episode-5`) fallen weg. Kacheln mit Bild zählen mehr.
 * 3. Folgeseiten der Übersicht: `rel="next"`, sonst eine Adresse mit Seitenzahl + 1
 *    (`?page=2`, `/seite/2/`), sonst ein Verweis „Weiter“/„Nächste“/„»“.
 */
object Overview {
    const val MIN_TITLES = 3

    fun titles(page: Page): List<Found> {
        val site = siteOf(page.url) ?: return emptyList()
        val own = key(page.url)
        val host = page.url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val siteName = page.meta["og:site_name"]
        val byKey = LinkedHashMap<String, Link>()
        page.links.forEach { l ->
            val k = key(l.url)
            if (siteOf(l.url) == site && k != own && !isAsset(l.url) && pathOf(l.url).trim('/').isNotEmpty()) byKey.putIfAbsent(k, l)
        }
        fun nameOf(text: String?): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty() || t.lowercase() in GENERIC_TEXT) return null
            return Info.cleanTitle(t, siteName, host).takeIf { it.isNotEmpty() && it.lowercase() !in GENERIC_TEXT }
        }

        // 1. JSON-LD: die Seite sagt selbst, was sie auflistet.
        val ld = ldItems(page).filter { (u, _) -> siteOf(u) == site && key(u) != own }.distinctBy { key(it.first) }
        if (ld.size >= MIN_TITLES) return ld.map { (u, n) -> Found(u, n?.let(::nameOf) ?: nameOf(byKey[key(u)]?.text)) }

        // 2. Familien: gleiche Adresse bis auf ein Segment.
        val families = LinkedHashMap<String, Family>()
        byKey.values.mapNotNull { Tokens.of(it) }.forEach { t ->
            t.tokens.indices.forEach { i ->
                val template = t.template(i)
                families.getOrPut(template.joinToString(SEP)) { Family(template) }.members += t to t.values[i]
            }
        }
        val scored = families.values.mapNotNull { score(it) }.sortedByDescending { it.points }
        val best = scored.firstOrNull() ?: return emptyList()
        val chosen = mutableSetOf<String>()
        // Mehrere Arten auf einer Übersicht (/serie/… und /film/…): weitere Familien, wenn fast so stark.
        // Übernommen werden nur die Titel einer Familie – „/katalog/login“ neben „/katalog/foo“ nicht.
        scored.filter { it.points >= best.points * 0.5 }.forEach { c ->
            val urls = c.titles.map { key(it.url) }
            if (urls.none { it in chosen }) chosen += urls
        }
        return byKey.filterKeys { it in chosen }.values.map { Found(it.url, nameOf(it.text)) }
    }

    private class Family(val template: List<String>) {
        val members = mutableListOf<Pair<Tokens, String>>()
    }

    private class Candidate(val titles: List<Link>, val points: Double)

    /** Titel einer Familie mit Punkten, oder null, wenn sie keine Titel-Liste ist. */
    private fun score(f: Family): Candidate? {
        val fixed = f.template.filter { it != WILD }.map { it.removeSuffix("=$WILD") }
        if (fixed.any { t -> t.split(WORD_SPLIT).any { it in NAV } }) return null
        val real = f.members.filter { (_, v) ->
            val base = v.substringBeforeLast('.').lowercase()
            v.isNotEmpty() && base !in NAV && base !in SECTIONS
        }
        val values = real.map { it.second.lowercase() }.toSet()
        if (values.size < MIN_TITLES) return null
        // A–Z-Leisten und Seitenzahlen: kurze Werte.
        if (values.count { it.substringBeforeLast('.').length >= 3 } < values.size * 0.7) return null
        // Folgen einer einzelnen Serie: „staffel-2“, „episode-5“, „s01e02“.
        if (values.count { EPISODE_MARK.containsMatchIn(it) } > values.size * 0.3) return null
        val images = real.count { it.first.link.image }
        val texts = real.mapNotNull { it.first.link.text.trim().lowercase().takeIf { t -> t.isNotEmpty() && t !in GENERIC_TEXT } }.toSet().size
        return Candidate(real.map { it.first.link }, values.size + images + texts * 0.5)
    }

    /** Adresse der nächsten Seite der Übersicht, sofern noch nicht gelesen. */
    fun next(page: Page, seen: Set<String>): String? {
        val site = siteOf(page.url)
        val own = key(page.url)
        fun ok(u: String) = siteOf(u) == site && key(u) != own && key(u) !in seen
        page.next?.takeIf(::ok)?.let { return it }
        val cur = pageNumber(page.url) ?: 1
        val base = withoutPage(page.url)
        page.links.firstOrNull { ok(it.url) && pageNumber(it.url) == cur + 1 && withoutPage(it.url) == base }?.let { return it.url }
        return page.links.firstOrNull { ok(it.url) && it.text.trim().lowercase().trimEnd('.', ' ') in NEXT_TEXT }?.url
    }

    /**
     * Übersicht lesen: erste Seite, dann Folgeseiten, bis [maxPages] Seiten oder [maxTitles]
     * Titel erreicht sind. Wie beim einzelnen Link erst schlicht, und bei Sperre, Vorschaltseite
     * oder zu wenig Titeln im Browser – dann bleibt es für die Folgeseiten beim Browser.
     */
    suspend fun scan(
        input: String,
        http: Fetcher,
        browser: Fetcher?,
        maxPages: Int,
        maxTitles: Int,
        progress: (page: Int, found: Int) -> Unit = { _, _ -> },
    ): Scan {
        val start = Importer.normalize(input) ?: throw FetchException("Das ist keine Adresse. Sie beginnt mit http:// oder https://.")
        val found = LinkedHashMap<String, Found>()
        val seen = mutableSetOf<String>()
        var target: String? = start
        var pages = 0
        var viaBrowser = false
        var lastError: String? = null

        suspend fun read(u: String, f: Fetcher): Page? {
            val r = try {
                f.fetch(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                return null
            }
            if (r.status >= 400) {
                lastError = when (r.status) {
                    404, 410 -> "Die Seite gibt es nicht (${r.status})."
                    401, 403, 429, 503 -> "Die Seite lässt den Creator nicht hinein (${r.status})."
                    else -> "Die Seite antwortet mit ${r.status}."
                }
                return null
            }
            return Page.parse(r.html, r.url)
        }

        while (target != null && pages < maxPages && found.size < maxTitles) {
            val u = target
            seen += key(u)
            progress(pages + 1, found.size)
            var page = if (viaBrowser) null else read(u, http)
            var t = page?.let { titles(it) }.orEmpty()
            if (browser != null && (page == null || page.challenge || t.size < MIN_TITLES)) {
                val b = read(u, browser)
                val bt = b?.let { titles(it) }.orEmpty()
                if (b != null && (page == null || bt.size > t.size)) {
                    page = b; t = bt; viaBrowser = true
                }
            }
            if (page == null) {
                if (pages == 0) throw FetchException(lastError ?: "Die Übersicht lässt sich nicht laden.")
                break   // Folgeseite nicht lesbar (oder Abrufgrenze): mit dem Gefundenen weiter.
            }
            pages++
            seen += key(page.url)
            if (pages == 1 && t.isEmpty()) {
                throw FetchException("Auf der Seite ist keine Liste von Serien oder Filmen zu erkennen. Für einen einzelnen Titel oben „Ein Titel“ wählen.")
            }
            // Seiten der Übersicht selbst („Zurück zur Liste“ auf Seite 2) sind keine Titel.
            t.forEach { if (found.size < maxTitles && key(it.url) !in seen) found.putIfAbsent(key(it.url), it) }
            target = next(page, seen)
        }
        return Scan(found.values.toList(), pages, more = target != null, usedBrowser = viaBrowser)
    }

    // --- Adressen ---

    /** Vergleichsschlüssel: ohne Schema, „www.“, #Anker und Schrägstrich am Ende. */
    fun key(u: String): String {
        val rest = u.substringAfter("://").substringBefore('#')
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        val tail = rest.substring(rest.substringBefore('/').substringBefore('?').length)
        val path = tail.substringBefore('?').trimEnd('/')
        val query = tail.substringAfter('?', "")
        return host + path + if (query.isNotEmpty()) "?$query" else ""
    }

    private fun siteOf(u: String): String? =
        u.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }

    private fun pathOf(u: String): String = u.substringAfter("://").let { r -> r.indexOf('/').let { if (it < 0) "" else r.substring(it) } }
        .substringBefore('#').substringBefore('?')

    private fun isAsset(u: String) = ASSET.containsMatchIn(pathOf(u))

    private val PAGE_IN_PATH = Regex("""(?i)/(page|seite|p|pg)[/\-_]?(\d+)(?=/|$|\.)""")
    private val PAGE_KEYS = setOf("page", "seite", "p", "pg", "paged")

    /** Seitenzahl einer Listen-Adresse: `/seite/3/`, `/page-3.html`, `?page=3`. */
    fun pageNumber(u: String): Int? {
        val query = u.substringBefore('#').substringAfter('?', "")
        query.split('&').forEach { kv ->
            if (kv.substringBefore('=').lowercase() in PAGE_KEYS) kv.substringAfter('=', "").toIntOrNull()?.let { return it }
        }
        return PAGE_IN_PATH.findAll(pathOf(u)).lastOrNull()?.groupValues?.get(2)?.toIntOrNull()
    }

    /** Adresse ohne Seitenzahl – gleiche Liste, andere Seite. */
    private fun withoutPage(u: String): String {
        val path = PAGE_IN_PATH.replace(pathOf(u), "")
        val query = u.substringBefore('#').substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in PAGE_KEYS }.sorted().joinToString("&")
        return key(u.substringBefore("://") + "://" + siteOf(u) + path + if (query.isNotEmpty()) "?$query" else "")
    }

    /** Adresse in Segmente zerlegt: Pfad (Zahlen-Segmente als „#“) und Query-Werte. */
    private class Tokens(val link: Link, val tokens: List<String>, val values: List<String>) {
        /** Form mit Platzhalter an Stelle [i]; bei Query-Werten bleibt der Name: „id=“ + Platzhalter. */
        fun template(i: Int): List<String> = tokens.mapIndexed { j, t ->
            if (j != i) t else if (t.contains('=')) t.substringBefore('=') + "=" + WILD else WILD
        }

        companion object {
            fun of(link: Link): Tokens? {
                val raw = link.url.substringAfter("://").substringBefore('#')
                val afterHost = raw.indexOf('/').let { if (it < 0) "" else raw.substring(it) }
                val segs = afterHost.substringBefore('?').split('/').filter { it.isNotEmpty() }.map(::decode)
                val query = afterHost.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .map { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=')) }.sortedBy { it.first }
                if (segs.isEmpty() && query.isEmpty()) return null
                val path = segs.map { s -> if (s.all { it.isDigit() }) "#" else s.lowercase() }
                val tokens = path + query.map { (k, v) -> "$k=" + if (v.all { it.isDigit() }) "#" else v.lowercase() }
                val values = segs + query.map { it.second }
                return Tokens(link, tokens, values)
            }

            private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        }
    }

    /** Titel, die die Seite selbst in JSON-LD auflistet: ItemList oder Serien/Filme mit url. */
    private fun ldItems(page: Page): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        fun abs(u: String) = runCatching { java.net.URI(page.url).resolve(u.trim()).toString() }.getOrNull()
        page.ld.forEach { o ->
            val types = Ld.types(o)
            if ("itemlist" in types) {
                val arr = o.optJSONArray("itemListElement") ?: return@forEach
                for (i in 0 until arr.length()) {
                    when (val e = arr.opt(i)) {
                        is String -> abs(e)?.let { out += it to null }
                        is org.json.JSONObject -> {
                            val item = Ld.obj(e, "item")
                            val u = Ld.str(e, "url") ?: Ld.str(item, "url") ?: (e.opt("item") as? String)
                            val n = Ld.str(item, "name") ?: Ld.str(e, "name")
                            u?.let(::abs)?.let { out += it to n }
                        }
                    }
                }
            } else if (types.any { it in LD_TITLE_TYPES }) {
                Ld.str(o, "url")?.let(::abs)?.let { out += it to Ld.str(o, "name") }
            }
        }
        return out
    }

    // Wie Words.marker, aber ohne „film“, „movie“, „ova“: „/film/der-film-2“ ist ein Titel, keine Folge.
    private val EPISODE_MARK = Regex("(?<![a-z])(" + (Words.season + Words.episode).sortedByDescending { it.length }.joinToString("|") + ")[-_ .]?\\d+")

    private const val SEP = "\u0001"
    private const val WILD = "\u0002"
    private val WORD_SPLIT = Regex("""[-_.=+ ]+""")
    private val ASSET = Regex("""(?i)\.(jpe?g|png|gif|webp|avif|svg|ico|css|js|json|xml|rss|atom|pdf|zip|mp4|m3u8|mpd|webm|mp3|txt)$""")
    private val LD_TITLE_TYPES = setOf("tvseries", "movie", "creativeworkseries")

    /** Wörter in festen Segmenten, die eine Familie zur Navigation machen. */
    private val NAV = setOf(
        "genre", "genres", "kategorie", "kategorien", "category", "categories", "cat", "tag", "tags", "jahr", "year", "years",
        "page", "seite", "seiten", "p", "pg", "paged", "sort", "order", "filter", "suche", "search", "s", "q",
        "login", "logout", "register", "registrieren", "anmelden", "signin", "signup", "user", "users", "profil", "profile", "account", "konto",
        "kontakt", "contact", "impressum", "imprint", "datenschutz", "privacy", "agb", "terms", "faq", "hilfe", "help", "dmca", "about",
        "news", "blog", "forum", "kommentare", "comments", "feed", "rss", "sitemap", "wp", "wp-content", "wp-json", "cdn-cgi", "static", "assets",
        "lang", "language", "sprache", "letter", "buchstabe", "alphabet", "az", "a-z", "char", "author", "autor", "schauspieler", "actor", "actors",
        "cast", "studio", "studios", "network", "netzwerk", "land", "country", "share", "teilen", "download", "downloads",
    )

    /** Werte, die Rubriken sind, keine Titel: „/serien“, „/neu“, „/beliebt“. */
    private val SECTIONS = setOf(
        "serie", "serien", "series", "show", "shows", "tv", "film", "filme", "films", "movie", "movies", "anime", "animes", "kino",
        "neu", "new", "neue", "neuste", "neueste", "latest", "beliebt", "popular", "top", "trending", "aktuell", "home", "start",
        "index", "alle", "all", "liste", "list", "katalog", "catalog", "random", "zufall", "kalender", "calendar", "watchlist",
    )

    private val GENERIC_TEXT = setOf(
        "mehr", "more", "ansehen", "anschauen", "details", "detail", "weiter", "watch", "watch now", "jetzt ansehen", "jetzt anschauen",
        "zur serie", "zum film", "stream", "play", "abspielen", "info", "infos", "»", "›", "…",
    )

    private val NEXT_TEXT = setOf("weiter", "nächste", "nächste seite", "next", "next page", "»", "›", ">", ">>", "vor")
}
