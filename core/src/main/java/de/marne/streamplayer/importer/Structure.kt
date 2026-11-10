package de.marne.streamplayer.importer

import de.marne.streamplayer.catalog.SeasonSpec
import java.net.URLDecoder

/** Eine geladene Seite: ihre Adresse (nach Umleitungen) und alle Verweise darauf. */
data class Loaded(val url: String, val links: List<String>)

/**
 * Adresse, zerlegt in Text und Zahlen: „/serie/x/staffel-2/episode-5“ → Teile
 * ["/serie/x/staffel-", "/episode-", ""] und Zahlen ["2", "5"]. Gleiche Teile = gleiche Form.
 * Nur Pfad und Query zählen. Der Host geht ohne „www.“ und ohne Schema in den Schlüssel ein,
 * weil Seiten beides gemischt verlinken.
 */
class Shaped private constructor(
    val url: String,
    val head: String,
    private val site: String,
    val parts: List<String>,
    val numbers: List<String>,
) {
    val key: String get() = site + "\u0000" + parts.joinToString("\u0001")
    val path: String get() = url.substring(head.length)
    fun value(i: Int): Int = numbers[i].trimStart('0').ifEmpty { "0" }.let { if (it.length > 9) Int.MAX_VALUE else it.toInt() }
    fun sameSite(other: Shaped) = site == other.site

    /** Pfadsegmente und Query-Werte, dekodiert – dort sucht [SeriesKey] den Namen der Serie. */
    fun segments(): List<String> {
        val p = path.substringBefore('#')
        val pathPart = p.substringBefore('?')
        val query = p.substringAfter('?', "")
        val segs = pathPart.split('/').filter { it.isNotEmpty() }
        val q = query.split('&').mapNotNull { it.substringAfter('=', "").takeIf { v -> v.isNotEmpty() } }
        return (segs + q).map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    companion object {
        fun of(url: String): Shaped? {
            val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
            val hostStart = schemeEnd + 3
            val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
            val head = url.substring(0, pathStart)
            val host = url.substring(hostStart, pathStart).lowercase().removePrefix("www.")
            if (host.isEmpty()) return null
            val tail = url.substring(pathStart).substringBefore('#')
            val parts = mutableListOf<String>()
            val numbers = mutableListOf<String>()
            var last = 0
            DIGITS.findAll(tail).forEach { m ->
                parts += tail.substring(last, m.range.first)
                numbers += m.value
                last = m.range.last + 1
            }
            parts += tail.substring(last)
            return Shaped(url.substringBefore('#'), head, host, parts, numbers)
        }

        private val DIGITS = Regex("""\d+""")
    }
}

internal enum class Role { SEASON, EPISODE, SPECIAL, PAGE }

internal object Words {
    val season = setOf("staffel", "staffeln", "season", "seasons", "saison", "stagione", "temporada", "s", "st")
    val episode = setOf("episode", "episodes", "episodio", "episodios", "episodi", "folge", "folgen", "ep", "eps", "epi", "e", "x")
    val special = setOf("special", "specials", "ova", "ovas", "oad", "film", "filme", "films", "movie", "movies", "extra", "extras")
    // Zahlen hinter diesen Wörtern sind Seitenzahlen, Jahre oder Sortierungen – nie Folgen.
    val page = setOf("page", "seite", "p", "pg", "offset", "start", "limit", "sort", "order", "year", "jahr", "genre", "id", "v", "t", "ref", "utm")

    // Was beim Vergleich von Pfadsegmenten nicht zum Namen gehört: „staffel-2“, „folge-5“, „s01e05“.
    private val all = (season + episode + special).sortedByDescending { it.length }.joinToString("|")
    val marker = Regex("""(?<![a-z])($all)[-_ .]?\d+""")

    fun role(hint: String?): Role? = when (hint) {
        null -> null
        in season -> Role.SEASON
        in episode -> Role.EPISODE
        in special -> Role.SPECIAL
        in page -> Role.PAGE
        else -> null
    }

    /** Das Wort direkt vor einer Zahl: „/serie/x/staffel-“ → „staffel“. */
    fun hintOf(part: String): String? = HINT.find(part)?.groupValues?.get(1)?.lowercase()
    private val HINT = Regex("""([A-Za-z]+)[^A-Za-z0-9]{0,2}$""")
}

/**
 * Woran man erkennt, dass ein Verweis zur selben Serie gehört: Ein Pfadsegment heißt nach dem
 * Entfernen von „staffel-2“, „folge-5“ und Endungen genauso wie die Serie in der Startadresse.
 * „foo“ passt so auf „/foo/staffel-1“ und „/foo-s01e02.html“, aber nicht auf „/foo-bar/…“ –
 * Seitenleisten mit ähnlich benannten Serien fallen damit heraus.
 * Ohne erkennbaren Namen gilt der Pfad der Startseite als Präfix.
 */
internal class SeriesKey private constructor(val slug: String?, private val prefix: String?) {
    fun related(s: Shaped): Boolean = when {
        slug != null -> s.segments().any { clean(it) == slug }
        prefix != null -> s.path.startsWith(prefix)
        else -> false
    }

    companion object {
        fun of(pageUrl: String): SeriesKey {
            val s = Shaped.of(pageUrl) ?: return SeriesKey(null, null)
            val slug = s.segments().reversed().firstNotNullOfOrNull { seg ->
                clean(seg).takeIf { c -> c !in GENERIC && c.any { it.isLetter() } && c.length >= 2 }
            }
            val path = s.path.substringBefore('?').trimEnd('/')
            return SeriesKey(slug, path.takeIf { it.length > 1 })
        }

        fun clean(segment: String): String = segment.lowercase()
            .replace(EXT, "")
            .replace(Words.marker, "")
            .replace(SEP, "-")
            .trim('-')

        private val EXT = Regex("""\.(html?|php\d?|aspx?|jsp|cfm)$""")
        private val SEP = Regex("""[-_ .+]+""")
        private val GENERIC = setOf(
            "", "stream", "streams", "watch", "anime", "animes", "serie", "serien", "series", "show", "shows", "tv",
            "film", "filme", "movie", "movies", "video", "videos", "online", "de", "en", "ger", "eng", "sub", "dub",
            "info", "detail", "details", "staffel", "staffeln", "season", "seasons", "folge", "folgen", "episode",
            "episoden", "episodes", "index", "home", "page", "play", "player", "ansehen", "anschauen", "katalog",
        )
    }
}

/** Gruppe von Verweisen gleicher Form. Zahlen, die sich unterscheiden, sind die Variablen. */
internal class Family(val members: List<Shaped>) {
    val sample = members.first()
    val size get() = members.size
    private val n = sample.numbers.size
    val variable: List<Int> = (0 until n).filter { i -> members.map { it.value(i) }.toSet().size > 1 }

    fun role(i: Int): Role? = Words.role(Words.hintOf(sample.parts[i]))
    fun values(i: Int): List<Int> = members.map { it.value(i) }
    fun hintedPositions(role: Role) = (0 until n).filter { role(it) == role }

    /** Breite mit führenden Nullen: 2 bei „05“, 3 bei „007“, sonst 1. */
    fun width(i: Int): Int {
        val padded = members.map { it.numbers[i] }.filter { it.length > 1 && it.startsWith('0') }
        return if (padded.isEmpty()) 1 else padded.maxOf { it.length }
    }

    fun template(placeholders: Map<Int, String>): String = buildString {
        append(sample.head)
        for (i in 0 until n) {
            append(sample.parts[i])
            append(placeholders[i] ?: sample.numbers[i])
        }
        append(sample.parts.last())
    }

    fun matches(s: Shaped) = s.key == sample.key
}

/** Welche Zahl in einer Familie was bedeutet. */
internal data class Roles(val season: Int?, val episode: Int, val special: Boolean = false)

/**
 * Die Struktur einer Serie, wie sie die Verweise der geladenen Seiten zeigen: Muster, Staffeln
 * mit Folgenzahl und erster Folge, Lücken und die Seiten der einzelnen Staffeln.
 */
class Structure internal constructor(
    private val episodes: Family?,
    private val roles: Roles?,
    private val seasonFamily: Family?,
    private val seasonPos: Int?,
    /** Muster der regulären Folgen. */
    val template: String?,
    /** Staffeln wie im Katalog; Specials als Staffel 0 mit eigenem Muster. */
    val seasons: List<SeasonSpec>,
    /** Je Staffel die Folgen, die zwischen erster und letzter auf keiner Seite vorkamen. */
    val gaps: Map<Int, List<Int>>,
    /** Seiten einzelner Staffeln, sofern verlinkt. */
    val seasonPages: Map<Int, String>,
    /** Übersichtsseite der Serie, abgeleitet aus dem Muster. */
    val root: String?,
) {
    val found: Boolean get() = template != null && seasons.any { it.number > 0 }
    val regularSeasons: List<SeasonSpec> get() = seasons.filter { it.number > 0 }
    val specials: SeasonSpec? get() = seasons.firstOrNull { it.number == 0 && it.template != null }
    /** Zählt die Seite über alle Staffeln durch, ohne Staffel in der Adresse? */
    val absolute: Boolean get() = roles != null && roles.season == null

    /** Staffel einer Seite: Staffelseite oder Folge einer Staffel. */
    fun seasonOf(url: String): Int? {
        val s = Shaped.of(url) ?: return null
        if (seasonFamily != null && seasonPos != null && seasonFamily.matches(s)) return s.value(seasonPos)
        val r = roles ?: return null
        if (episodes != null && r.season != null && episodes.matches(s)) return s.value(r.season)
        return null
    }

    /** (Staffel, Folge) eines Verweises – nur, wenn die Staffel in der Adresse steht. */
    fun episodeOf(url: String): Pair<Int, Int>? {
        val s = Shaped.of(url) ?: return null
        val r = roles ?: return null
        if (episodes == null || r.season == null || !episodes.matches(s)) return null
        return s.value(r.season) to s.value(r.episode)
    }

    private fun isEpisode(url: String): Boolean {
        val s = Shaped.of(url) ?: return false
        return episodes?.matches(s) == true
    }

    fun firstEpisode(season: Int): String? {
        val r = roles ?: return null
        val list = episodes?.members ?: return null
        return if (r.season == null) null
        else list.filter { it.value(r.season) == season }.minByOrNull { it.value(r.episode) }?.url
    }

    /**
     * Welche Staffeln noch eine Seite brauchen, damit alle Folgen bekannt sind. Eine Staffel gilt
     * als vollständig, wenn eine geladene Seite zu ihr gehört, oder wenn eine Übersicht (ohne
     * eigene Staffel) ihre Folgen lückenlos zeigt. Für die anderen: Staffelseite, sonst die erste
     * bekannte Folge – Folgenseiten listen ihre Geschwister meistens mit.
     */
    fun toLoad(loaded: List<Loaded>, tried: Set<String>): List<Pair<Int, String>> {
        val listed = mutableSetOf<Int>()
        for (p in loaded) {
            val own = seasonOf(p.url)
            if (own != null) { listed += own; continue }
            val counts = p.links.mapNotNull { episodeOf(it) }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            val shown = counts.maxByOrNull { it.value.size }?.key
            // Ein einzelner Verweis („Erste Folge ansehen“) zeigt keine Staffel – erst ab zwei Folgen.
            counts.forEach { (s, eps) ->
                if (eps.size >= 2 && (s == shown || (eps.size >= 3 && eps.max() - eps.min() + 1 == eps.size))) listed += s
            }
        }
        val known = (seasonPages.keys + regularSeasons.map { it.number }).filter { it > 0 }.toSortedSet()
        return known.filter { it !in listed }.mapNotNull { s ->
            (seasonPages[s] ?: firstEpisode(s))?.takeIf { it !in tried }?.let { s to it }
        }
    }

    companion object {
        /** Höher liegende Zahlen sind IDs, keine Folgen. */
        private const val MAX_EPISODE = 5000
        private const val MAX_SEASON = 300

        fun analyze(pageUrl: String, pages: List<Loaded>): Structure {
            val start = Shaped.of(pageUrl) ?: return empty()
            val key = SeriesKey.of(pageUrl)
            val pool = LinkedHashMap<String, Shaped>()
            (pages.map { it.url } + pages.flatMap { it.links } + pageUrl).forEach { u ->
                if (u in pool) return@forEach
                val s = Shaped.of(u) ?: return@forEach
                if (s.sameSite(start) && key.related(s)) pool[u] = s
            }
            val families = pool.values.groupBy { it.key }.values.map { Family(it) }

            // Staffelseiten: genau eine Zahl, die sich ändert, mit Staffel-Wort davor.
            val seasonCands = families.mapNotNull { f -> seasonPosition(f)?.let { f to it } }
                .filter { (f, pos) -> f.values(pos).all { it in 0..MAX_SEASON } }
            val (seasonFamily, seasonPos) = seasonCands.maxByOrNull { it.first.size } ?: (null to null)

            // Folgen: die beste Familie mit Folgen-Rolle.
            val episodeCands = families.mapNotNull { f -> rolesOf(f)?.takeIf { !it.special && plausible(f, it) }?.let { f to it } }
            val (episodes, roles) = episodeCands.maxByOrNull { (f, r) -> score(f, r) } ?: (null to null)

            val specialCands = families.mapNotNull { f -> rolesOf(f)?.takeIf { it.special && plausible(f, it) }?.let { f to it } }
            val (specialFam, specialRoles) = specialCands.maxByOrNull { it.first.size } ?: (null to null)

            val seasonPages = buildMap {
                if (seasonFamily != null && seasonPos != null) seasonFamily.members.forEach { m -> putIfAbsent(m.value(seasonPos), m.url) }
            }

            val bySeason = sortedMapOf<Int, MutableSet<Int>>()
            var template: String? = null
            if (episodes != null && roles != null) {
                val ph = mutableMapOf(roles.episode to episodePlaceholder(episodes.width(roles.episode)))
                if (roles.season != null) {
                    ph[roles.season] = if (episodes.width(roles.season) >= 2) "{ss}" else "{s}"
                    episodes.members.forEach { m -> bySeason.getOrPut(m.value(roles.season)) { sortedSetOf() } += m.value(roles.episode) }
                } else {
                    assignBySeasonPage(episodes, roles.episode, pages, seasonFamily, seasonPos, bySeason)
                }
                template = episodes.template(ph)
            }

            val gaps = mutableMapOf<Int, List<Int>>()
            val seasons = bySeason.map { (s, eps) ->
                val first = eps.first()
                val last = eps.last()
                val missing = (first..last).filter { it !in eps }
                if (missing.isNotEmpty()) gaps[s] = missing
                SeasonSpec(number = s, episodes = last - first + 1, start = first)
            }.toMutableList()

            // Specials mit eigenem Muster – nur, wenn die Folgen-Familie nicht schon eine Staffel 0 hat.
            if (specialFam != null && specialRoles != null && seasons.none { it.number == 0 }) {
                val vals = specialFam.values(specialRoles.episode).toSortedSet()
                val tpl = specialFam.template(mapOf(specialRoles.episode to episodePlaceholder(specialFam.width(specialRoles.episode))))
                seasons += SeasonSpec(0, vals.last() - vals.first() + 1, start = vals.first(), template = tpl, title = "Specials")
            }

            val root = (template ?: seasonFamily?.let { f -> seasonPos?.let { f.template(mapOf(it to "{s}")) } })
                ?.let { rootOf(it) }
                ?.let { r -> pool.keys.firstOrNull { it.trimEnd('/') == r } ?: r }
            return Structure(episodes, roles, seasonFamily, seasonPos, template, seasons.sortedBy { it.number }, gaps, seasonPages, root)
        }

        private fun empty() = Structure(null, null, null, null, null, emptyList(), emptyMap(), emptyMap(), null)

        private fun episodePlaceholder(width: Int) = when {
            width >= 3 -> "{eee}"
            width == 2 -> "{ee}"
            else -> "{e}"
        }

        private fun seasonPosition(f: Family): Int? {
            val pos = when (f.variable.size) {
                1 -> f.variable[0].takeIf { f.role(it) == Role.SEASON }
                // Nur ein Verweis (etwa „Staffel 2“ auf der Seite von Staffel 1): die Zahl mit Staffel-Wort.
                0 -> f.hintedPositions(Role.SEASON).lastOrNull()
                else -> null
            } ?: return null
            // Steht dahinter noch eine Folge oder ein Special, ist es eine Folgenseite, keine Staffelseite.
            val after = (pos + 1 until f.sample.numbers.size).map { f.role(it) }
            return pos.takeIf { Role.EPISODE !in after && Role.SPECIAL !in after }
        }

        internal fun rolesOf(f: Family): Roles? {
            val vars = f.variable
            return when (vars.size) {
                0 -> {
                    // Einzelner Verweis: zählt nur mit eindeutigem Folgen- oder Special-Wort.
                    val ep = f.hintedPositions(Role.EPISODE).lastOrNull()
                    val sp = f.hintedPositions(Role.SPECIAL).lastOrNull()
                    when {
                        ep != null -> Roles(f.hintedPositions(Role.SEASON).lastOrNull { it < ep }, ep)
                        sp != null -> Roles(null, sp, special = true)
                        else -> null
                    }
                }
                1 -> {
                    val i = vars[0]
                    when (f.role(i)) {
                        Role.PAGE, Role.SEASON -> null
                        Role.SPECIAL -> Roles(null, i, special = true)
                        else -> Roles(f.hintedPositions(Role.SEASON).lastOrNull { it < i }, i)
                    }
                }
                2 -> {
                    val (a, b) = vars
                    val ra = f.role(a)
                    val rb = f.role(b)
                    if (ra == Role.PAGE || rb == Role.PAGE || ra == Role.SPECIAL || rb == Role.SPECIAL) return null
                    when {
                        ra == Role.SEASON -> Roles(a, b)
                        rb == Role.SEASON -> Roles(b, a)
                        rb == Role.EPISODE -> Roles(a, b)
                        ra == Role.EPISODE -> Roles(b, a)
                        else -> Roles(a, b)   // ohne Wörter: erst Staffel, dann Folge (1x05, /3/12)
                    }
                }
                else -> null
            }
        }

        private fun plausible(f: Family, r: Roles): Boolean {
            val eps = f.values(r.episode)
            if (eps.any { it > MAX_EPISODE }) return false
            // Lauter Jahreszahlen ohne Folgen-Wort: Archiv- oder Filterlinks.
            if (f.role(r.episode) != Role.EPISODE && eps.all { it in 1900..2100 }) return false
            if (r.season != null && f.values(r.season).any { it > MAX_SEASON }) return false
            return true
        }

        private fun score(f: Family, r: Roles): Int {
            var s = f.size
            if (f.role(r.episode) == Role.EPISODE) s += 10 + f.size
            if (r.season != null) s += 5
            return s
        }

        /**
         * Folgen ohne Staffel in der Adresse (Seite zählt durch): Staffel nach der Staffelseite,
         * auf der die Folge steht. Was nur auf Übersichten vorkommt, ist Staffel 1 – sofern es
         * für Staffel 1 keine eigene Seite gibt; sonst sind es Doppelte.
         */
        private fun assignBySeasonPage(
            episodes: Family, epPos: Int, pages: List<Loaded>, seasonFamily: Family?, seasonPos: Int?,
            out: MutableMap<Int, MutableSet<Int>>,
        ) {
            val pageSeason = pages.associate { p ->
                p.url to Shaped.of(p.url)?.takeIf { seasonFamily?.matches(it) == true && seasonPos != null }?.value(seasonPos!!)
            }
            val claimed = mutableMapOf<Int, Int>()   // Folge → Staffel
            pages.filter { pageSeason[it.url] != null }.sortedBy { pageSeason[it.url] }.forEach { p ->
                val s = pageSeason[p.url]!!
                p.links.forEach { u ->
                    val m = Shaped.of(u)?.takeIf { episodes.matches(it) } ?: return@forEach
                    claimed.putIfAbsent(m.value(epPos), s)
                }
            }
            val seasonOnePage = pageSeason.values.any { it == 1 }
            episodes.members.forEach { m ->
                val e = m.value(epPos)
                val s = claimed[e] ?: if (!seasonOnePage) 1 else return@forEach
                out.getOrPut(s) { sortedSetOf() } += e
            }
        }

        /** „https://x/serie/foo/staffel-{s}/episode-{e}“ → „https://x/serie/foo“; null, wenn nur der Host bliebe. */
        internal fun rootOf(template: String): String? {
            val cut = template.indexOf('{').takeIf { it > 0 } ?: return null
            val pre = template.substring(0, cut).substringBefore('?')
            val slash = pre.lastIndexOf('/')
            val hostEnd = template.indexOf('/', template.indexOf("://") + 3)
            if (hostEnd < 0 || slash <= hostEnd) return null
            return pre.substring(0, slash)
        }
    }
}
