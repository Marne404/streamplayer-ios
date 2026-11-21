package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.SeasonSpec
import org.json.JSONArray
import org.json.JSONObject

// LibTitle liegt im gemeinsamen Modul :ui; Speichern und Lesen (org.json) bleibt auf Android.

fun LibTitle.toJson(): JSONObject = JSONObject().apply {
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
    if (libraryId != null) put("library", libraryId)
    if (alsoIn.isNotEmpty()) put("alsoIn", JSONArray(alsoIn))
}

fun LibTitle.Companion.fromJson(o: JSONObject): LibTitle {
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
        libraryId = o.str("library"),
        alsoIn = o.optJSONArray("alsoIn")?.strings().orEmpty(),
    )
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

private fun JSONObject.long(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)

private fun JSONArray.strings(): List<String> = List(length()) { optString(it) }.filter { it.isNotEmpty() }
