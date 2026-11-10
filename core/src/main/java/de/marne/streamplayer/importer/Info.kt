package de.marne.streamplayer.importer

import org.json.JSONObject

enum class Kind { SERIES, MOVIE }

/** Was eine Seite über sich selbst sagt: Name, Jahr, Beschreibung, Bild, Art. */
data class PageInfo(
    val name: String?,
    val year: Int?,
    val description: String?,
    val image: String?,
    val kind: Kind?,
    /** Folgen je Staffel laut JSON-LD der Seite (containsSeason), sonst leer. */
    val ldSeasons: Map<Int, Int> = emptyMap(),
)

object Info {
    private val SERIES_TYPES = setOf("tvseries", "creativeworkseries", "podcastseries")
    private val PART_TYPES = setOf("tvseason", "creativeworkseason", "tvepisode", "episode")
    private val MOVIE_TYPES = setOf("movie")

    fun of(page: Page): PageInfo {
        val series = page.ld.firstOrNull { o -> Ld.types(o).any { it in SERIES_TYPES } }
            ?: page.ld.firstNotNullOfOrNull { o -> o.takeIf { Ld.types(it).any { t -> t in PART_TYPES } }?.let { Ld.obj(it, "partOfSeries") } }
        val movie = if (series == null) page.ld.firstOrNull { o -> Ld.types(o).any { it in MOVIE_TYPES } } else null
        val main: JSONObject? = series ?: movie

        val ogType = page.meta["og:type"].orEmpty().lowercase()
        val kind = when {
            series != null || page.ld.any { o -> Ld.types(o).any { it in PART_TYPES } } -> Kind.SERIES
            movie != null -> Kind.MOVIE
            ogType == "video.tv_show" || ogType == "video.episode" -> Kind.SERIES
            ogType == "video.movie" -> Kind.MOVIE
            else -> null
        }

        val site = page.meta["og:site_name"]
        val host = page.url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val rawTitles = listOfNotNull(page.h1, page.meta["og:title"], page.meta["twitter:title"], page.title)
        val name = Ld.str(main, "name")?.let { cleanTitle(it, site, host) }?.takeIf { it.isNotEmpty() }
            ?: rawTitles.firstNotNullOfOrNull { t -> cleanTitle(t, site, host).takeIf { it.isNotEmpty() } }
        val year = Ld.year(main)
            ?: rawTitles.firstNotNullOfOrNull { yearIn(it) }
            ?: listOf("video:release_date", "og:video:release_date", "release_date").firstNotNullOfOrNull { k ->
                page.meta[k]?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }
            }
        val description = Ld.str(main, "description") ?: page.meta["og:description"] ?: page.meta["description"] ?: page.meta["twitter:description"]
        val image = (Ld.image(main) ?: page.meta["og:image"] ?: page.meta["og:image:url"] ?: page.meta["twitter:image"])
            ?.let { resolve(page.url, it) }

        val ldSeasons = buildMap {
            val arr = series?.optJSONArray("containsSeason") ?: return@buildMap
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val n = s.optInt("seasonNumber", -1).takeIf { it >= 0 } ?: Ld.str(s, "seasonNumber")?.toIntOrNull() ?: continue
                val count = s.optInt("numberOfEpisodes", 0).takeIf { it > 0 } ?: s.optJSONArray("episode")?.length() ?: 0
                if (count > 0) put(n, count)
            }
        }
        return PageInfo(name, year, description?.trim(), image, kind, ldSeasons)
    }

    private fun resolve(base: String, url: String) = runCatching { java.net.URI(base).resolve(url.trim()).toString() }.getOrDefault(url)

    private val YEAR = Regex("""\((19\d{2}|20\d{2})\)""")
    fun yearIn(s: String): Int? = YEAR.find(s)?.groupValues?.get(1)?.toIntOrNull()

    private val SPLIT = Regex("""\s+[|–—·•»:]{1,2}\s+|\s+-\s+""")
    private val MARKERS = listOf(
        Regex("""(?i)\b(staffel|season|saison|stagione|temporada)\s*\d+"""),
        Regex("""(?i)\b(episode|episodio|folge|ep\.?)\s*\d+"""),
        Regex("""(?i)\bs\d{1,2}\s*e\d{1,4}\b"""),
        Regex("""(?i)\b\d{1,2}x\d{1,3}\b"""),
        Regex("""(?i)\b(alle\s+(folgen|staffeln|episoden)|all\s+episodes)\b"""),
    )
    private val BOILERPLATE = Regex(
        """(?i)\b(stream(s|en)?|online\s+(anschauen|schauen|sehen|gucken|streamen|ansehen)|anschauen|kostenlos|gratis|""" +
            """in\s+hd|full\s*hd|ger\s*(sub|dub)|eng\s*(sub|dub)|deutsch|german|english\s+sub(bed)?|online\s+free|watch\s+online)\b""",
    )
    // Als eigener Teil („Foo - Online | Seite“) kein Name. Mitten im Titel bleiben sie: „Sword Art Online“.
    private val GENERIC_WORDS = setOf("online", "hd", "anime", "serie", "series", "film", "movie", "home", "start", "startseite")
    private val LEADING = Regex("""(?i)^(watch|schaue?|stream)\s+""")
    private val TAG = Regex("""(?i)\s*\((serie|series|anime|tv|tv-serie|film|movie|19\d{2}|20\d{2})\)""")
    private val DOMAIN = Regex("""(?i)^[\w.-]+\.[a-z]{2,6}$""")
    private val TRAIL = Regex("""[\s\-–—:|,(\[]+$""")

    /**
     * Seitentitel → Name: „Foo – Staffel 2 Folge 5 online anschauen | Seite“ → „Foo“.
     * Erst in Teile an „ | “, „ - “ usw.; Teile, die nur Seitenname, Staffel/Folge oder Floskel
     * sind, fallen weg. Der Rest wird wieder zusammengesetzt, damit „Re:Zero - Starting Life …“
     * heil bleibt. Am Ende wird ab der ersten Staffel-/Folgenangabe abgeschnitten.
     */
    fun cleanTitle(raw: String, siteName: String?, host: String?): String {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val label = host?.substringBeforeLast('.')?.substringAfterLast('.')?.let(::norm)?.takeIf { it.length >= 4 }
        val fullHost = host?.let(::norm)
        fun isSite(s: String): Boolean {
            val n = norm(s)
            return n.isEmpty() || (siteName != null && n == norm(siteName)) || DOMAIN.matches(s.trim()) || n == fullHost ||
                (label != null && (n == label || n.startsWith(label) && n.length <= label.length + 4))
        }
        fun startsWithMarker(s: String) = MARKERS.any { m -> m.find(s)?.range?.first == 0 }
        fun onlyBoilerplate(s: String) = BOILERPLATE.replace(s, "").isBlank() || norm(s) in GENERIC_WORDS

        val segs = raw.replace(' ', ' ').split(SPLIT).map { it.trim() }
            .filter { it.isNotEmpty() && !isSite(it) && !startsWithMarker(it) && !onlyBoilerplate(it) }
        var t = segs.joinToString(" - ").ifEmpty { return "" }
        t = LEADING.replace(t, "")
        val cut = (MARKERS.mapNotNull { it.find(t)?.range?.first } + listOfNotNull(BOILERPLATE.find(t)?.range?.first))
            .filter { it > 0 }.minOrNull()
        if (cut != null) t = t.substring(0, cut)
        t = TAG.replace(t, "")
        return TRAIL.replace(t.trim(), "").trim()
    }
}
