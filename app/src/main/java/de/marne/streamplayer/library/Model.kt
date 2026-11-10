package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
import org.json.JSONArray
import org.json.JSONObject

enum class TitleKind { MOVIE, SERIES }

/** Eine Folge: aus Muster und Staffeln erzeugt, ergänzt um TMDB-Angaben. */
data class Episode(
    val season: Int,
    val number: Int,
    val absolute: Int,
    val url: String,
    val title: String? = null,
    val still: String? = null,
    val overview: String? = null,
    val runtimeMin: Int? = null,
) {
    val label: String get() = if (season == 0) "Special $number" else "S$season · F$number"
}

/** Was TMDB über eine Folge weiß. */
data class EpInfo(val title: String?, val overview: String?, val still: String?, val runtimeMin: Int?)

/** Was TMDB über einen Titel weiß – getrennt gehalten, damit Katalog-Angaben immer gewinnen. */
data class TmdbInfo(
    val id: Int,
    val title: String?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val year: Int?,
    val runtimeMin: Int?,
    /** null: noch nie geholt (Stand vor 1.7.0) – der Enricher lädt dann einmal nach. */
    val genres: List<String>? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val tagline: String? = null,
    val cast: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
)

/**
 * Ein Titel der Mediathek – Film oder Serie.
 *
 * Gespeichert wird immer die *Seite*, nie der Stream: Stream-Adressen der Hoster leben
 * wenige Stunden. Aufgelöst wird erst beim Abspielen.
 *
 * Bildreihenfolge: eigenes Cover › Poster aus dem Katalog › TMDB-Poster › Hintergrund.
 */
data class LibTitle(
    val id: String,
    val kind: TitleKind,
    val title: String,
    val pageUrl: String?,
    val template: String? = null,
    val seasons: List<SeasonSpec> = emptyList(),
    val overrides: List<EpisodeOverride> = emptyList(),
    /** Poster (2:3) aus dem Katalog. */
    val poster: String? = null,
    /** Breites Bild (16:9): Katalog oder og:image der Seite. */
    val backdrop: String? = null,
    /** Vom Nutzer gewähltes Cover (Datei in filesDir/posters). */
    val customPoster: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val tmdbQuery: String? = null,
    val tmdb: TmdbInfo? = null,
    /** ID aus dem Katalog: ein erneuter Import aktualisiert statt zu verdoppeln. */
    val sourceId: String? = null,
    /** TMDB-Angaben je Folge, Schlüssel "s:e". */
    val episodeInfo: Map<String, EpInfo> = emptyMap(),
    /** Fertige Downloads: Fortschritts-Schlüssel ([Keys]) → content://-Adresse. */
    val offline: Map<String, String> = emptyMap(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    val host: String? get() = (pageUrl ?: template)?.let { runCatching { java.net.URI(it.replace("{", "").replace("}", "")).host?.removePrefix("www.") }.getOrNull() }

    /** Hochformat-Cover für Regale. */
    val cover: String? get() = customPoster ?: poster ?: tmdb?.poster ?: backdrop ?: tmdb?.backdrop

    /** Breitbild für Hero und Detailseite. */
    val wide: String? get() = backdrop ?: tmdb?.backdrop ?: poster ?: tmdb?.poster ?: customPoster

    /** Alte Stellen fragen nach einem Bild: das breite. */
    val image: String? get() = wide

    val text: String? get() = description ?: tmdb?.overview
    val genres: List<String> get() = tmdb?.genres.orEmpty()
    /** Nur mit genug Stimmen: eine 10,0 aus zwei Bewertungen ist keine Empfehlung. */
    val rating: Double? get() = tmdb?.rating?.takeIf { (tmdb.votes ?: 0) >= 20 }
    val shownYear: Int? get() = year ?: tmdb?.year
    val runtimeMs: Long? get() = durationMs ?: tmdb?.runtimeMin?.let { it * 60_000L }

    fun episodes(): List<Episode> = if (kind != TitleKind.SERIES) emptyList()
    else Episodes.generate(template, seasons, overrides).map { g ->
        val info = episodeInfo["${g.season}:${g.number}"]
        Episode(g.season, g.number, g.absolute, g.url, g.title ?: info?.title, info?.still, info?.overview, info?.runtimeMin)
    }

    fun episode(season: Int, number: Int): Episode? = episodes().firstOrNull { it.season == season && it.number == number }

    fun next(after: Episode): Episode? {
        val all = episodes()
        val i = all.indexOfFirst { it.season == after.season && it.number == after.number }
        return all.getOrNull(i + 1)
    }

    val episodeCount: Int get() = seasons.sumOf { it.episodes }

    fun seasonLabel(number: Int): String =
        seasons.firstOrNull { it.number == number }?.title ?: if (number == 0) "Specials" else "Staffel $number"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("title", title); put("pageUrl", pageUrl)
        put("template", template)
        put("seasons", JSONArray().apply {
            seasons.forEach { s ->
                put(JSONObject().put("number", s.number).put("episodes", s.episodes).put("start", s.start).put("template", s.template).put("title", s.title))
            }
        })
        put("overrides", JSONArray().apply {
            overrides.forEach { o -> put(JSONObject().put("season", o.season).put("episode", o.episode).put("url", o.url).put("title", o.title)) }
        })
        put("poster", poster); put("backdrop", backdrop); put("customPoster", customPoster)
        put("description", description); put("durationMs", durationMs); put("year", year)
        put("tmdbId", tmdbId); put("tmdbQuery", tmdbQuery); put("sourceId", sourceId)
        tmdb?.let { t ->
            put("tmdb", JSONObject().put("id", t.id).put("title", t.title).put("poster", t.poster).put("backdrop", t.backdrop)
                .put("overview", t.overview).put("year", t.year).put("runtimeMin", t.runtimeMin)
                .put("genres", t.genres?.let { JSONArray(it) }).put("rating", t.rating).put("votes", t.votes)
                .put("tagline", t.tagline).put("cast", JSONArray(t.cast)).put("creators", JSONArray(t.creators)))
        }
        put("episodeInfo", JSONObject().apply {
            episodeInfo.forEach { (k, e) ->
                put(k, JSONObject().put("title", e.title).put("overview", e.overview).put("still", e.still).put("runtimeMin", e.runtimeMin))
            }
        })
        put("offline", JSONObject(offline as Map<*, *>)); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): LibTitle {
            val s = o.optJSONArray("seasons") ?: JSONArray()
            val seasons = List(s.length()) { i ->
                when (val v = s.get(i)) {
                    is Number -> SeasonSpec(i + 1, v.toInt())                // bis 1.0: nur Folgenzahlen
                    is JSONObject -> SeasonSpec(v.optInt("number", i + 1), v.optInt("episodes"), v.optInt("start", 1), v.str("template"), v.str("title"))
                    else -> SeasonSpec(i + 1, 0)
                }
            }
            val ov = o.optJSONArray("overrides") ?: JSONArray()
            val overrides = List(ov.length()) { i ->
                val e = ov.getJSONObject(i)
                EpisodeOverride(e.getInt("season"), e.getInt("episode"), e.str("url"), e.str("title"))
            }
            val off = o.optJSONObject("offline") ?: JSONObject()
            val ei = o.optJSONObject("episodeInfo") ?: JSONObject()
            val tm = o.optJSONObject("tmdb")
            return LibTitle(
                id = o.getString("id"),
                kind = TitleKind.valueOf(o.getString("kind")),
                title = o.optString("title"),
                pageUrl = o.str("pageUrl"),
                template = o.str("template"),
                seasons = seasons,
                overrides = overrides,
                poster = o.str("poster"),
                backdrop = o.str("backdrop") ?: o.str("image"),          // bis 1.0 hieß es „image“
                customPoster = o.str("customPoster"),
                description = o.str("description"),
                durationMs = o.long("durationMs"),
                year = o.long("year")?.toInt(),
                tmdbId = o.long("tmdbId")?.toInt(),
                tmdbQuery = o.str("tmdbQuery"),
                tmdb = tm?.let {
                    TmdbInfo(
                        it.getInt("id"), it.str("title"), it.str("poster"), it.str("backdrop"), it.str("overview"),
                        it.long("year")?.toInt(), it.long("runtimeMin")?.toInt(),
                        genres = it.optJSONArray("genres")?.strings(),
                        rating = if (it.isNull("rating") || !it.has("rating")) null else it.optDouble("rating").takeIf { r -> !r.isNaN() },
                        votes = it.long("votes")?.toInt(),
                        tagline = it.str("tagline"),
                        cast = it.optJSONArray("cast")?.strings().orEmpty(),
                        creators = it.optJSONArray("creators")?.strings().orEmpty(),
                    )
                },
                sourceId = o.str("sourceId"),
                episodeInfo = ei.keys().asSequence().associateWith { k ->
                    val e = ei.getJSONObject(k)
                    EpInfo(e.str("title"), e.str("overview"), e.str("still"), e.long("runtimeMin")?.toInt())
                },
                offline = off.keys().asSequence().associateWith { off.getString(it) },
                addedAt = o.optLong("addedAt"),
            )
        }

        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
        private fun JSONObject.long(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
        private fun JSONArray.strings(): List<String> = List(length()) { optString(it) }.filter { it.isNotEmpty() }
    }
}

/** Schlüssel für Fortschritt und Offline-Dateien. */
object Keys {
    fun movie(t: LibTitle) = "m:${t.id}"
    fun episode(t: LibTitle, e: Episode) = "e:${t.id}:${e.season}:${e.number}"

    /** "e:<id>:<s>:<e>" → (id, s, e); "m:<id>" → (id, null, null) */
    fun parse(key: String): Triple<String, Int?, Int?>? {
        val p = key.split(':')
        return when {
            p.size == 2 && p[0] == "m" -> Triple(p[1], null, null)
            p.size == 4 && p[0] == "e" -> Triple(p[1], p[2].toIntOrNull(), p[3].toIntOrNull())
            else -> null
        }
    }
}
