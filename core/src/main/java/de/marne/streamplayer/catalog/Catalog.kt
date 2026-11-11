package de.marne.streamplayer.catalog

import de.marne.streamplayer.ui.tr
import org.json.JSONArray
import org.json.JSONObject

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
    fun parseReport(text: String, strict: Boolean = true, skipBroken: Boolean = true): Pair<Catalog, List<String>> {
        val root = try {
            val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
            if (trimmed.startsWith("[")) JSONObject().put("titles", JSONArray(trimmed)) else JSONObject(trimmed)
        } catch (e: Exception) {
            throw CatalogException(tr("Keine gültige JSON-Datei: ${e.message}", "Not a valid JSON file: ${e.message}"))
        }
        val format = root.optString("format", FORMAT)
        if (format != FORMAT) throw CatalogException(tr("Unbekanntes Format „$format“.", "Unknown format “$format”."))
        val version = root.optInt("version", VERSION)
        if (version > VERSION) throw CatalogException(tr("Katalog-Version $version ist neuer als diese App. Bitte aktualisieren.", "Catalog version $version is newer than this app. Please update."))
        val arr = root.optJSONArray("titles") ?: throw CatalogException(tr("Kein Feld „titles“ gefunden.", "No “titles” field found."))
        val problems = mutableListOf<String>()
        val titles = (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.optJSONObject(i) ?: throw CatalogException(tr("kein Objekt.", "not an object."))
                parseTitle(o, strict)
            } catch (e: Exception) {
                val reason = (e as? CatalogException)?.message ?: e.message
                val msg = tr("Eintrag ${i + 1}: $reason", "Entry ${i + 1}: $reason")
                if (!skipBroken) throw CatalogException(msg)
                problems += msg
                null
            }
        }
        return Catalog(titles, root.str("name")) to problems
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

    fun write(c: Catalog): String {
        val arr = JSONArray()
        c.titles.forEach { t ->
            arr.put(JSONObject().apply {
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
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            c.name?.let { put("name", it) }
            put("titles", arr)
        }.toString(2)
    }

    fun slug(title: String, year: Int? = null): String {
        val base = title.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "titel" }
        return if (year != null && year > 0) "$base-$year" else base
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
}
