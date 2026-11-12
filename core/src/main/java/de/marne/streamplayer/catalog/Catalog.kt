package de.marne.streamplayer.catalog

import de.marne.streamplayer.ui.tr
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

/**
 * Das Austauschformat zwischen Creator und Player: eine JSON-Datei mit Filmen und Serien.
 *
 * {
 *   "format": "streamplayer-catalog", "version": 1,
 *   "titles": [
 *     { "type": "series", "id": "breaking-bad", "title": "Breaking Bad", "year": 2008,
 *       "tmdb": { "id": 1396 },
 *       "template": "https://example.to/serie/breaking-bad/staffel-{s}/episode-{e}",
 *       "seasons": [ { "number": 1, "episodes": 7 }, { "number": 2, "episodes": 13, "start": 8 } ],
 *       "episodes": [ { "season": 1, "episode": 3, "url": "https://…" } ],
 *       "poster": "https://… oder data:image/jpeg;base64,…" },
 *     { "type": "movie", "id": "inception", "title": "Inception", "year": 2010,
 *       "tmdb": { "query": "Inception" }, "url": "https://example.to/film/inception" }
 *   ]
 * }
 */
data class Catalog(val titles: List<CatalogTitle>, val name: String? = null)

enum class CatalogType(val json: String) { MOVIE("movie"), SERIES("series") }

/** Wie der Titel bei TMDB zu finden ist: feste ID oder Suchbegriff (+ Jahr). */
data class TmdbRef(val id: Int? = null, val query: String? = null)

/**
 * Eine Staffel. [start]: Nummer der ersten Folge (manche Seiten zählen weiter statt neu).
 * [template]: eigenes Muster nur für diese Staffel (z. B. andere Adresse für Specials).
 */
data class SeasonSpec(
    val number: Int,
    val episodes: Int,
    val start: Int = 1,
    val template: String? = null,
    val title: String? = null,
)

/** Einzelne Folge mit eigener Adresse oder eigenem Titel, wo das Muster nicht passt. */
data class EpisodeOverride(val season: Int, val episode: Int, val url: String? = null, val title: String? = null)

data class CatalogTitle(
    val id: String,
    val type: CatalogType,
    val title: String,
    val year: Int? = null,
    val tmdb: TmdbRef? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    /** Film: Seite des Films. */
    val url: String? = null,
    /** Serie: Muster für alle Staffeln ohne eigenes. */
    val template: String? = null,
    val seasons: List<SeasonSpec> = emptyList(),
    val episodes: List<EpisodeOverride> = emptyList(),
)

/**
 * „Serie“ mit genau einer Folge ist ein Film, den die Seitenstruktur wie eine Serie aussehen ließ
 * (Filmseite mit einem Link „Episode 1“ zum Player). Serien mit nur einer Folge gibt es nicht –
 * als Serie fände TMDB ihn auch nie. Die eine Folge wird zur Filmseite.
 */
fun CatalogTitle.singleEpisodeAsMovie(): CatalogTitle {
    if (type != CatalogType.SERIES) return this
    // Erst zählen, dann erzeugen: läuft beim Übernehmen für jede Serie im Katalog (bei 11 000 Serien
    // sonst Hunderttausende Folgen-Adressen je übernommenem Film).
    if (seasons.sumOf { it.episodes.coerceAtLeast(0) } != 1) return this
    val eps = Episodes.generate(template, seasons, episodes)
    if (eps.size != 1) return this
    return copy(type = CatalogType.MOVIE, url = eps.single().url, template = null, seasons = emptyList(), episodes = emptyList())
}

class CatalogException(message: String) : Exception(message)

/** Eine erzeugte Folge. */
data class GeneratedEpisode(val season: Int, val number: Int, val absolute: Int, val url: String, val title: String?)

object Episodes {
    /** Alle Folgen in Staffel-Reihenfolge, absolute Zählung über alles. Overrides gewinnen. */
    fun generate(template: String?, seasons: List<SeasonSpec>, overrides: List<EpisodeOverride>): List<GeneratedEpisode> {
        var absolute = 0
        val byKey = overrides.associateBy { it.season to it.episode }
        return seasons.sortedBy { it.number }.flatMap { s ->
            (0 until s.episodes).map { i ->
                val n = s.start + i
                absolute++
                val ov = byKey[s.number to n]
                val tpl = s.template ?: template
                GeneratedEpisode(
                    season = s.number,
                    number = n,
                    absolute = absolute,
                    url = ov?.url ?: tpl?.let { Pattern.fill(it, s.number, n, absolute) }.orEmpty(),
                    title = ov?.title,
                )
            }
        }
    }
}

object CatalogJson {
    const val FORMAT = "streamplayer-catalog"
    const val VERSION = 1

    /**
     * Liest einen Katalog. Fehler sagen, was nicht stimmt – mit Position, wo es geht.
     * [strict] = false für Arbeitsstände (Creator): Halbfertiges wie ein Titel ohne Namen ist
     * dort normal und darf nie dazu führen, dass die ganze Datei nicht mehr lesbar ist.
     */
    fun parse(text: String, strict: Boolean = true): Catalog = parseReport(text, strict, skipBroken = false).first

    /**
     * Wie [parse], aber mit [skipBroken] werden kaputte Einträge übersprungen statt die ganze Datei
     * abzulehnen. Rückgabe: Katalog und je übersprungenem Eintrag eine Meldung.
     */
    fun parseReport(text: String, strict: Boolean = true, skipBroken: Boolean = true): Pair<Catalog, List<String>> =
        read(StringReader(text), strict, skipBroken)

    /**
     * Wie [parseReport], aber aus einem Strom und Titel für Titel: Es liegt nie die ganze Datei als
     * Text oder als JSON-Baum im Speicher, nur der eine Eintrag, der gerade gelesen wird.
     *
     * Anlass: Ein Creator-Katalog mit 11 000 Serien wurde als ein Text plus JSON-Baum gelesen und
     * geschrieben – das sprengt den Speicher der App. Liest jedes Layout (eingerückt wie früher,
     * ein Titel je Zeile wie [write] heute, oder eine bloße Liste).
     *
     * [partial]: Bricht die Datei mittendrin ab (Speichern unterbrochen), gilt, was bis dahin
     * vollständig war – mit einer Meldung statt eines Fehlers für alles.
     */
    fun read(input: Reader, strict: Boolean = true, skipBroken: Boolean = true, partial: Boolean = false): Pair<Catalog, List<String>> {
        val s = Scan(input)
        val titles = ArrayList<CatalogTitle>()
        val problems = mutableListOf<String>()
        var name: String? = null
        var format: String? = null
        var version: Int? = null
        var sawTitles = false

        fun checkHeader() {
            val f = format ?: FORMAT
            if (f != FORMAT) throw CatalogException(tr("Unbekanntes Format „$f“.", "Unknown format “$f”."))
            val v = version ?: VERSION
            if (v > VERSION) throw CatalogException(tr("Katalog-Version $v ist neuer als diese App. Bitte aktualisieren.", "Catalog version $v is newer than this app. Please update."))
        }

        fun entry(i: Int, raw: String) {
            try {
                if (!raw.startsWith("{")) throw CatalogException(tr("kein Objekt.", "not an object."))
                titles += parseTitle(JSONObject(raw), strict)
            } catch (e: Exception) {
                val reason = (e as? CatalogException)?.message ?: e.message
                val msg = tr("Eintrag ${i + 1}: $reason", "Entry ${i + 1}: $reason")
                if (!skipBroken) throw CatalogException(msg)
                problems += msg
            }
        }

        fun titleArray() {
            s.expect('[')
            if (s.peekNonWs() == ']'.code) { s.next(); return }
            var i = 0
            val sb = StringBuilder()
            while (true) {
                sb.setLength(0)
                s.value(sb)
                entry(i++, sb.toString())
                when (s.peekNonWs()) {
                    ','.code -> s.next()
                    ']'.code -> { s.next(); return }
                    else -> s.fail("„,“ oder „]“", "“,” or “]”")
                }
            }
        }

        try {
            when (s.peekNonWs()) {
                '['.code -> { titleArray(); sawTitles = true }
                '{'.code -> {
                    s.next()
                    if (s.peekNonWs() == '}'.code) s.next()
                    else while (true) {
                        val key = s.key()
                        s.expect(':')
                        if (key == "titles" && s.peekNonWs() == '['.code) {
                            checkHeader()
                            titleArray()
                            sawTitles = true
                        } else {
                            val raw = StringBuilder().also { s.value(it) }.toString()
                            val v = JSONTokener(raw).nextValue()
                            when (key) {
                                "format" -> format = v.toString()
                                "version" -> version = (v as? Number)?.toInt() ?: v.toString().toIntOrNull()
                                "name" -> name = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            }
                        }
                        when (s.peekNonWs()) {
                            ','.code -> s.next()
                            '}'.code -> { s.next(); break }
                            else -> s.fail("„,“ oder „}“", "“,” or “}”")
                        }
                    }
                }
                else -> s.fail("„{“ oder „[“", "“{” or “[”")
            }
        } catch (e: Exception) {
            val err = e as? CatalogException ?: CatalogException(tr("Keine gültige JSON-Datei: ${e.message}", "Not a valid JSON file: ${e.message}"))
            if (!partial || titles.isEmpty()) throw err
            problems += err.message.orEmpty()
            return Catalog(titles, name) to problems
        }
        checkHeader()
        if (!sawTitles) throw CatalogException(tr("Kein Feld „titles“ gefunden.", "No “titles” field found."))
        return Catalog(titles, name) to problems
    }

    /**
     * Zerlegt JSON nur so weit, wie [read] es braucht: Schlüssel der obersten Ebene und je ein ganzer
     * Wert als Text. Was darin steht, liest danach org.json – für einen Eintrag ist das klein.
     */
    private class Scan(input: Reader) {
        private val r = input as? BufferedReader ?: BufferedReader(input, 64 * 1024)
        private var peeked = NONE
        private var pos = 0L

        fun peek(): Int {
            if (peeked == NONE) peeked = r.read()
            return peeked
        }

        fun next(): Int {
            val c = peek()
            peeked = NONE
            if (c >= 0) pos++
            return c
        }

        fun peekNonWs(): Int {
            while (true) {
                val c = peek()
                if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) next() else return c
            }
        }

        fun expect(ch: Char) {
            if (peekNonWs() != ch.code) fail("„$ch“", "“$ch”")
            next()
        }

        fun key(): String {
            if (peekNonWs() != '"'.code) fail("ein Schlüssel in Anführungszeichen", "a quoted key")
            val sb = StringBuilder()
            value(sb)
            return JSONTokener(sb.toString()).nextValue() as String
        }

        /** Einen vollständigen Wert (Objekt, Liste, Zeichenkette, Zahl …) als Text anhängen. */
        fun value(into: StringBuilder) {
            when (peekNonWs()) {
                -1 -> fail("ein Wert", "a value")
                '{'.code, '['.code -> {
                    var depth = 0
                    do {
                        val c = next()
                        if (c < 0) fail("„}“ oder „]“", "“}” or “]”")
                        into.append(c.toChar())
                        when (c) {
                            '"'.code -> stringRest(into)
                            '{'.code, '['.code -> depth++
                            '}'.code, ']'.code -> depth--
                        }
                    } while (depth > 0)
                }
                '"'.code -> { into.append(next().toChar()); stringRest(into) }
                else -> while (true) {
                    val c = peek()
                    if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
                    into.append(next().toChar())
                }
            }
        }

        /** Rest einer Zeichenkette nach dem öffnenden Anführungszeichen, samt dem schließenden. */
        private fun stringRest(into: StringBuilder) {
            while (true) {
                val c = next()
                if (c < 0) fail("„\"“", "“\"”")
                into.append(c.toChar())
                if (c == '\\'.code) {
                    val e = next()
                    if (e < 0) fail("„\"“", "“\"”")
                    into.append(e.toChar())
                } else if (c == '"'.code) return
            }
        }

        fun fail(de: String, en: String): Nothing {
            val at = pos + 1
            throw CatalogException(tr("Keine gültige JSON-Datei: $de erwartet bei Zeichen $at.", "Not a valid JSON file: expected $en at character $at."))
        }

        private companion object { const val NONE = -2 }
    }

    private fun parseTitle(o: JSONObject, strict: Boolean): CatalogTitle {
        val type = when (o.optString("type").lowercase()) {
            "movie", "film" -> CatalogType.MOVIE
            "series", "serie", "tv", "show" -> CatalogType.SERIES
            "" -> if (o.has("template") || o.has("seasons")) CatalogType.SERIES else CatalogType.MOVIE
            else -> throw CatalogException(tr("Unbekannter Typ „${o.optString("type")}“ (movie oder series).", "Unknown type “${o.optString("type")}” (movie or series)."))
        }
        val title = o.str("title") ?: o.optJSONObject("tmdb")?.str("query") ?: if (strict) throw CatalogException(tr("„title“ fehlt.", "“title” is missing.")) else ""
        val tmdb = when (val t = o.opt("tmdb")) {
            is JSONObject -> TmdbRef(t.optInt("id").takeIf { it > 0 }, t.str("query"))
            is Number -> TmdbRef(t.toInt())
            is String -> t.toIntOrNull()?.let { TmdbRef(it) } ?: TmdbRef(query = t)
            else -> null
        }
        val seasons = o.optJSONArray("seasons")?.let { arr ->
            List(arr.length()) { i ->
                when (val s = arr.get(i)) {
                    is Number -> SeasonSpec(i + 1, s.toInt())          // Kurzform: [12, 8, 10]
                    is JSONObject -> SeasonSpec(
                        number = s.optInt("number", i + 1),
                        episodes = s.optInt("episodes", -1).takeIf { it >= 0 } ?: if (strict) throw CatalogException(tr("Staffel ${i + 1}: „episodes“ fehlt.", "Season ${i + 1}: “episodes” is missing.")) else 0,
                        start = s.optInt("start", 1),
                        template = s.str("template"),
                        title = s.str("title"),
                    )
                    else -> throw CatalogException(tr("Staffel ${i + 1} ist weder Zahl noch Objekt.", "Season ${i + 1} is neither a number nor an object."))
                }
            }
        }.orEmpty()
        val episodes = o.optJSONArray("episodes")?.let { arr ->
            List(arr.length()) { i ->
                val e = arr.getJSONObject(i)
                EpisodeOverride(e.getInt("season"), e.getInt("episode"), e.str("url"), e.str("title"))
            }
        }.orEmpty()
        val template = o.str("template")
        val url = o.str("url")
        if (strict && type == CatalogType.SERIES && template != null && !Pattern.hasEpisode(template) && seasons.any { it.template == null }) {
            throw CatalogException(tr("Muster ohne Folgennummer ({e} oder {a}).", "Pattern without an episode number ({e} or {a})."))
        }
        return CatalogTitle(
            id = o.str("id") ?: slug(title, o.optInt("year")),
            type = type,
            title = title,
            year = o.optInt("year").takeIf { it in 1870..2200 },
            tmdb = tmdb,
            poster = o.str("poster"),
            backdrop = o.str("backdrop"),
            description = o.str("description"),
            url = url,
            template = template,
            seasons = seasons,
            episodes = episodes,
        )
    }

    fun write(c: Catalog): String = StringWriter().also { write(c, it) }.toString()

    /**
     * Schreibt Titel für Titel in [out], ein Titel je Zeile – nie die ganze Datei als ein Text.
     * Gültiges JSON wie zuvor (früher eingerückt; das kostete bei großen Katalogen ein Drittel
     * der Größe nur für Leerzeichen).
     */
    fun write(c: Catalog, out: Writer) {
        out.write("{\"format\":\"$FORMAT\",\"version\":$VERSION")
        c.name?.let { out.write(",\"name\":"); out.write(JSONObject.quote(it)) }
        out.write(",\"titles\":[")
        c.titles.forEachIndexed { i, t ->
            out.write(if (i == 0) "\n" else ",\n")
            out.write(titleJson(t).toString())
        }
        out.write("\n]}\n")
    }

    private fun titleJson(t: CatalogTitle): JSONObject =
            JSONObject().apply {
                put("type", t.type.json)
                put("id", t.id)
                put("title", t.title)
                t.year?.let { put("year", it) }
                t.tmdb?.let { r -> put("tmdb", JSONObject().apply { r.id?.let { put("id", it) }; r.query?.let { put("query", it) } }) }
                t.url?.let { put("url", it) }
                t.template?.let { put("template", it) }
                if (t.seasons.isNotEmpty()) put("seasons", JSONArray().apply {
                    t.seasons.forEach { s ->
                        put(JSONObject().apply {
                            put("number", s.number); put("episodes", s.episodes)
                            if (s.start != 1) put("start", s.start)
                            s.template?.let { put("template", it) }
                            s.title?.let { put("title", it) }
                        })
                    }
                })
                if (t.episodes.isNotEmpty()) put("episodes", JSONArray().apply {
                    t.episodes.forEach { e ->
                        put(JSONObject().apply {
                            put("season", e.season); put("episode", e.episode)
                            e.url?.let { put("url", it) }; e.title?.let { put("title", it) }
                        })
                    }
                })
                t.poster?.let { put("poster", it) }
                t.backdrop?.let { put("backdrop", it) }
                t.description?.let { put("description", it) }
            }

    fun slug(title: String, year: Int? = null): String {
        val base = title.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "titel" }
        return if (year != null && year > 0) "$base-$year" else base
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
}
