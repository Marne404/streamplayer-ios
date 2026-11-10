package de.marne.streamplayer.importer

import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Pattern
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.catalog.TmdbRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.abs

/** Antwort einer Seite: Adresse nach Umleitungen, HTTP-Status, HTML. */
data class Fetched(val url: String, val status: Int, val html: String)

class FetchException(message: String) : IOException(message)

/** Lädt eine Seite. Im Creator: OkHttp, und bei Bedarf eine WebView. Im Test: eine Tabelle. */
fun interface Fetcher {
    suspend fun fetch(url: String): Fetched
}

data class Note(val text: String, val warning: Boolean = false)

/**
 * Ergebnis der Analyse: ein fertiger Katalog-Titel zum Übernehmen, dazu Hinweise, wo die Seite
 * und TMDB sich nicht einig sind, und – wenn TMDB nicht eindeutig war – Kandidaten zur Wahl.
 */
data class Draft(
    val source: String,
    val title: CatalogTitle,
    val notes: List<Note>,
    val linked: Tmdb.Result?,
    val candidates: List<Tmdb.Result>,
    val pages: Int,
    val usedBrowser: Boolean,
    internal val info: PageInfo,
    internal val structure: Structure?,
    internal val details: Tmdb.Details? = null,
) {
    val episodeCount: Int get() = title.seasons.sumOf { it.episodes }
}

/**
 * „Aus Link übernehmen“: eine Seite laden, ihre Struktur erkennen, fehlende Staffeln nachladen,
 * mit TMDB abgleichen. Höchstens [maxPages] Abrufe mit [pauseMs] Abstand – eine Seite plus eine
 * pro Staffel, nie die ganze Seite abgrasen.
 */
class Importer(
    private val http: Fetcher,
    private val browser: Fetcher? = null,
    private val tmdb: Tmdb? = null,
    private val pauseMs: Long = 400,
    private val maxPages: Int = 40,
) {
    suspend fun analyze(input: String, progress: (String) -> Unit = {}): Draft {
        val url = normalize(input) ?: throw FetchException("Das ist keine Adresse. Sie beginnt mit http:// oder https://.")
        val loaded = mutableListOf<Loaded>()
        val tried = mutableSetOf<String>()
        var count = 0
        var viaBrowser = false

        suspend fun get(u: String, label: String, f: Fetcher): Fetched {
            if (count > 0) delay(pauseMs)
            progress(label)
            tried += u
            count++
            return f.fetch(u)
        }

        // 1. Startseite – erst schlicht, bei Bedarf im Browser.
        var res = runCatching { get(url, "Lade die Seite …", http) }.getOrElse { e ->
            if (e is CancellationException) throw e
            if (browser == null) throw FetchException(e.message ?: "Die Seite lässt sich nicht laden.")
            null
        }
        var page = res?.takeIf { it.status < 400 }?.let { Page.parse(it.html, it.url) }
        var st = page?.let { Structure.analyze(it.url, listOf(Loaded(it.url, it.links.map { l -> l.url }))) }
        // Ein Film mit JSON-LD braucht keinen Browser, auch wenn die Seite kaum Verweise hat.
        val movie = page != null && Info.of(page).kind == Kind.MOVIE
        val needsBrowser = res == null || res.status in BLOCKED || page == null || page.challenge ||
            (!movie && (page.links.size < 8 || st?.found != true))
        val br = browser
        if (br != null && needsBrowser) {
            val b = runCatching { get(url, "Lade die Seite im Browser …", br) }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
            val bPage = b?.takeIf { it.status < 400 }?.let { Page.parse(it.html, it.url) }
            if (bPage != null) {
                val bSt = Structure.analyze(bPage.url, listOf(Loaded(bPage.url, bPage.links.map { it.url })))
                // Den Browser nur behalten, wenn er mehr sieht – er ist langsamer.
                if (page == null || bSt.found || bPage.links.size > page.links.size) {
                    res = b; page = bPage; st = bSt; viaBrowser = true
                }
            }
        }
        if (res == null || page == null || st == null) {
            val status = res?.status
            throw FetchException(
                when (status) {
                    null -> "Die Seite lässt sich nicht laden."
                    404, 410 -> "Die Seite gibt es nicht ($status)."
                    in BLOCKED -> "Die Seite lässt den Creator nicht hinein ($status)."
                    else -> "Die Seite antwortet mit $status."
                },
            )
        }
        val fetcher = if (viaBrowser) browser!! else http
        // Ab hier zählt die Adresse, auf der die Seite gelandet ist (Umleitung, Bestätigungsseite).
        val landed = page.url.takeIf { it.startsWith("http") } ?: url
        loaded += Loaded(landed, page.links.map { it.url } + url)
        var info = Info.of(page)

        // 2. Übersicht der Serie, wenn die Startseite eine Folge oder Staffel war.
        val root = st.root
        if (root != null && !same(root, url) && !same(root, page.url) && count < maxPages) {
            val r = runCatching { get(root, "Lade die Übersicht der Serie …", fetcher) }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
            if (r != null && r.status < 400) {
                val p = Page.parse(r.html, r.url)
                loaded += Loaded(r.url, p.links.map { it.url })
                info = merge(Info.of(p), info)
                st = Structure.analyze(landed, loaded)
            }
        }

        // 3. Staffeln, deren Folgen noch keine geladene Seite zeigt.
        while (count < maxPages) {
            val next = st!!.toLoad(loaded, tried)
            val (season, target) = next.firstOrNull() ?: break
            val total = (st!!.seasonPages.keys + st!!.regularSeasons.map { it.number }).count { it > 0 }
            val r = runCatching { get(target, "Lade Staffel $season von $total …", fetcher) }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            } ?: continue
            if (r.status >= 400) continue
            val p = Page.parse(r.html, r.url)
            loaded += Loaded(target, p.links.map { it.url } + r.url)
            st = Structure.analyze(landed, loaded)
        }

        // Verrät die Seite keinen Namen, dann wenigstens der aus der Adresse – auch für die TMDB-Suche.
        if (info.name.isNullOrBlank()) info = info.copy(name = nameFromUrl(landed))

        val structure = st!!
        val kind = when {
            structure.found -> Kind.SERIES
            else -> info.kind ?: Kind.MOVIE
        }
        val base = Draft(
            source = landed,
            title = CatalogTitle(id = "", type = if (kind == Kind.SERIES) CatalogType.SERIES else CatalogType.MOVIE, title = info.name.orEmpty()),
            notes = emptyList(), linked = null, candidates = emptyList(),
            // Verschiedene Seiten, nicht Abrufe: schlicht und dann im Browser ist dieselbe Seite.
            pages = tried.size, usedBrowser = viaBrowser, info = info, structure = structure,
        )

        // 4. TMDB: eindeutiger Treffer wird verknüpft, sonst Kandidaten zur Wahl.
        val t = tmdb
        if (t == null || !t.configured || info.name.isNullOrBlank()) return build(base, null, null)
        progress("Suche bei TMDB …")
        return try {
            val (best, candidates) = match(t, info.name, info.year, kind)
            val details = best?.let { b ->
                if (kind == Kind.SERIES) runCatching { t.details(Tmdb.Kind.TV, b.id) }.getOrNull() else null
            }
            build(base.copy(candidates = if (best == null) candidates else emptyList()), best, details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            build(base, null, null, extra = Note("TMDB nicht erreichbar: ${e.message}"))
        }
    }

    /**
     * Art umstellen (Serie ↔ Film), wenn die Erkennung danebenlag. TMDB-IDs von Filmen und Serien
     * sind verschiedene Räume – also neu suchen statt die alte Verknüpfung zu behalten.
     */
    suspend fun retype(draft: Draft, type: CatalogType, name: String = draft.title.title): Draft {
        val base = draft.copy(title = draft.title.copy(type = type), linked = null, candidates = emptyList(), details = null)
        val t = tmdb
        if (t == null || !t.configured || name.isBlank()) return build(base, null, null)
        return try {
            val kind = if (type == CatalogType.SERIES) Kind.SERIES else Kind.MOVIE
            val (best, candidates) = match(t, name, draft.title.year ?: draft.info.year, kind)
            val details = best?.takeIf { type == CatalogType.SERIES }?.let { b -> runCatching { t.details(Tmdb.Kind.TV, b.id) }.getOrNull() }
            build(base.copy(candidates = if (best == null) candidates else emptyList()), best, details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            build(base, null, null, extra = Note("TMDB nicht erreichbar: ${e.message}"))
        }
    }

    /** Anderen TMDB-Treffer wählen (oder keinen) – Staffeln und Hinweise werden neu abgeglichen. */
    suspend fun link(draft: Draft, result: Tmdb.Result?): Draft {
        if (result == null) return build(draft.copy(candidates = emptyList()), null, null)
        val details = if (draft.title.type == CatalogType.SERIES) runCatching { tmdb?.details(Tmdb.Kind.TV, result.id) }.getOrNull() else null
        return build(draft.copy(candidates = emptyList()), result, details)
    }

    private suspend fun match(t: Tmdb, name: String, year: Int?, kind: Kind): Pair<Tmdb.Result?, List<Tmdb.Result>> {
        suspend fun search(k: Tmdb.Kind): List<Tmdb.Result> {
            val withYear = if (year != null) t.search(name, k, year) else emptyList()
            return withYear.ifEmpty { t.search(name, k) }
        }
        val primary = if (kind == Kind.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
        val results = search(primary)
        return pick(name, year, results) to results.take(4)
    }

    companion object {
        private val BLOCKED = setOf(401, 403, 429, 503)

        fun normalize(input: String): String? {
            val t = input.trim().substringBefore(' ').trim()
            // Geteilte Texte enthalten oft mehr als die Adresse: „Schau mal: https://…“
            val found = Regex("""https?://\S+""").find(input)?.value?.trimEnd('.', ',', ')', '"', '\'')
            val u = found ?: t
            return u.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && it.length > 10 && '.' in it }
        }

        private fun same(a: String, b: String): Boolean {
            fun n(u: String) = u.substringAfter("://").removePrefix("www.").trimEnd('/').substringBefore('#')
            return n(a) == n(b)
        }

        internal fun merge(primary: PageInfo, fallback: PageInfo) = PageInfo(
            name = primary.name ?: fallback.name,
            year = primary.year ?: fallback.year,
            description = primary.description ?: fallback.description,
            image = primary.image ?: fallback.image,
            kind = primary.kind ?: fallback.kind,
            ldSeasons = primary.ldSeasons.ifEmpty { fallback.ldSeasons },
        )

        /** Name aus der Adresse: „/serie/foo-bar/staffel-1“ → „Foo Bar“. */
        fun nameFromUrl(url: String): String? = SeriesKey.of(url).slug
            ?.split('-')?.filter { it.isNotEmpty() }
            ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            ?.takeIf { it.isNotBlank() }

        internal fun norm(s: String?) = s.orEmpty().lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

        /**
         * Eindeutig ist ein Treffer mit gleichem Namen (oder Originalnamen) und passendem Jahr (±1),
         * oder der einzige Treffer überhaupt, wenn sich die Namen enthalten.
         */
        internal fun pick(name: String, year: Int?, results: List<Tmdb.Result>): Tmdb.Result? {
            val n = norm(name)
            if (n.isEmpty()) return null
            val exact = results.filter { norm(it.title) == n || norm(it.originalTitle) == n }
            exact.firstOrNull { year == null || it.year == null || abs(it.year - year) <= 1 }?.let { return it }
            val only = results.singleOrNull() ?: return null
            val o = norm(only.title)
            return only.takeIf { n.length >= 4 && o.length >= 4 && (o.contains(n) || n.contains(o)) && exact.isEmpty() }
        }

        /** Entwurf aus Seite, Struktur und (optional) TMDB zusammensetzen. */
        internal fun build(draft: Draft, linked: Tmdb.Result?, details: Tmdb.Details?, extra: Note? = null): Draft {
            val info = draft.info
            val type = draft.title.type
            val notes = mutableListOf<Note>()
            var template: String? = null
            var seasons = emptyList<SeasonSpec>()
            if (type == CatalogType.SERIES) {
                val ref = details?.seasons?.filter { it.number > 0 && it.episodeCount > 0 }?.map { it.number to it.episodeCount }
                    ?.takeIf { it.isNotEmpty() }
                val (tpl, sea, n) = reconcile(
                    draft.structure, ref ?: info.ldSeasons.filter { it.key > 0 }.toList().takeIf { it.isNotEmpty() },
                    if (ref != null) "TMDB" else "die Seite", draft.source,
                )
                template = tpl; seasons = sea; notes += n
            }
            extra?.let { notes += it }
            val name = linked?.title ?: info.name.orEmpty()
            val year = linked?.year ?: info.year
            val title = draft.title.copy(
                id = CatalogJson.slug(name.ifBlank { "titel" }, year),
                title = name,
                year = year,
                tmdb = linked?.let { TmdbRef(it.id) },
                // Mit TMDB kommen Bilder und Text von dort (besser und in der eigenen Sprache) – und zwar
                // direkt in den Katalog. Anlass: Leer gelassen, damit der Player sie selbst holt, fehlte
                // das Hochformat-Cover im Creator immer und im Player, solange dort kein TMDB-Schlüssel
                // eingetragen ist. Hat TMDB kein Poster, bleibt das Bild der Seite.
                poster = linked?.poster ?: info.image,
                backdrop = linked?.backdrop,
                description = (if (linked != null) linked.overview else info.description)?.take(1200),
                url = if (type == CatalogType.MOVIE) draft.source else null,
                template = template,
                seasons = seasons,
            )
            return draft.copy(title = title, linked = linked, details = details, notes = notes.sortedByDescending { it.warning })
        }

        /**
         * Seite gegen Referenz (TMDB oder JSON-LD der Seite): Lücken, abweichende Folgenzahlen, und
         * Seiten, die über alle Staffeln durchzählen – dann werden die Staffeln der Referenz mit
         * absoluter Folge ({a}) übernommen, sofern die Summe genau passt.
         */
        internal fun reconcile(st: Structure?, ref: List<Pair<Int, Int>>?, refName: String, source: String): Triple<String?, List<SeasonSpec>, List<Note>> {
            val notes = mutableListOf<Note>()
            if (st == null || !st.found) {
                val guess = Pattern.derive(source)?.takeIf { looksLikeEpisode(source) }
                val seasons = ref?.map { (n, c) -> SeasonSpec(n, c) }.orEmpty()
                notes += if (guess != null) Note("Kein Muster auf der Seite, nur aus der Adresse geraten. Vorschau im Editor prüfen.", warning = true)
                else Note("Kein Folgen-Muster gefunden. Im Editor den Link einer Folge eintragen.", warning = true)
                return Triple(guess, seasons, notes)
            }
            var template = st.template
            var seasons = st.seasons
            st.gaps.forEach { (s, missing) ->
                val where = if (s == 0) "Specials" else "Staffel $s"
                notes += Note(
                    if (missing.size == 1) "$where: Folge ${missing[0]} fehlt auf der Seite."
                    else "$where: ${missing.size} Folgen fehlen auf der Seite (${missing.take(5).joinToString(", ")}${if (missing.size > 5) " …" else ""}).",
                    warning = true,
                )
            }
            val regular = st.regularSeasons
            if (ref != null) {
                val refMap = ref.toMap()
                val sum = ref.sumOf { it.second }
                if (st.absolute && regular.size == 1 && ref.size >= 2) {
                    val only = regular.single()
                    if (only.start == 1 && only.episodes == sum && st.specials == null && template != null) {
                        template = template.replace("{eee}", "{aaa}").replace("{ee}", "{aa}").replace("{e}", "{a}")
                        seasons = ref.sortedBy { it.first }.map { (n, c) -> SeasonSpec(n, c) }
                        notes += Note("Die Seite zählt durch. Staffeln von $refName übernommen.")
                    } else {
                        notes += Note("Die Seite zählt durch (${only.episodes} Folgen), $refName kennt ${ref.size} Staffeln mit $sum.")
                    }
                } else {
                    regular.forEach { s ->
                        val c = refMap[s.number] ?: return@forEach
                        if (c != s.episodes) notes += Note("Staffel ${s.number}: ${s.episodes} Folgen auf der Seite, $c bei $refName.")
                    }
                    val missing = ref.map { it.first }.filter { n -> regular.none { it.number == n } }
                    if (missing.isNotEmpty() && !st.absolute) {
                        notes += Note(
                            if (missing.size == 1) "Staffel ${missing[0]} gibt es bei $refName, auf der Seite nicht."
                            else "Staffeln ${missing.joinToString(", ")} gibt es bei $refName, auf der Seite nicht.",
                        )
                    }
                }
            }
            return Triple(template, seasons, notes)
        }

        private fun looksLikeEpisode(url: String): Boolean =
            Regex("""(?i)(staffel|season|episode|folge|[/\-_]s\d{1,2}e\d|[/\-_]ep?[-_]?\d)""").containsMatchIn(url)
    }
}
