package de.marne.streamplayer.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * The Movie Database (TMDB): Titel erkennen, Poster, Hintergründe, Beschreibungen,
 * Staffeln mit Folgenzahl, Folgennamen und -bilder.
 *
 * Schlüssel: der API-Schlüssel (v3, 32 Zeichen) oder das „API Read Access Token“ (v4, beginnt
 * mit „eyJ“). Beides gibt es kostenlos unter themoviedb.org → Einstellungen → API.
 */
class Tmdb(
    private val http: OkHttpClient,
    private val key: () -> String?,
    private val language: () -> String = { "de-DE" },
) {
    enum class Kind(val path: String) { MOVIE("movie"), TV("tv") }

    data class Result(
        val id: Int,
        val kind: Kind,
        val title: String,
        val originalTitle: String?,
        val year: Int?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
    )

    data class SeasonInfo(val number: Int, val episodeCount: Int, val name: String?, val poster: String?)

    data class Details(val result: Result, val seasons: List<SeasonInfo>, val runtimeMin: Int?)

    data class EpisodeInfo(val season: Int, val episode: Int, val name: String?, val overview: String?, val still: String?, val runtimeMin: Int?)

    class TmdbException(message: String) : IOException(message)

    val configured: Boolean get() = !key().isNullOrBlank()

    /** Prüft den Schlüssel. */
    suspend fun check(): Boolean = runCatching { get("configuration"); true }.getOrDefault(false)

    suspend fun search(query: String, kind: Kind, year: Int? = null): List<Result> {
        val params = buildMap {
            put("query", query)
            put("include_adult", "false")
            if (year != null) put(if (kind == Kind.TV) "first_air_date_year" else "year", year.toString())
        }
        return parseSearch(get("search/${kind.path}", params), kind)
    }

    suspend fun details(kind: Kind, id: Int): Details = parseDetails(get("${kind.path}/$id"), kind)

    suspend fun season(tvId: Int, season: Int): List<EpisodeInfo> = parseSeason(get("tv/$tvId/season/$season"))

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val k = key()?.trim().takeIf { !it.isNullOrEmpty() } ?: throw TmdbException("Kein TMDB-Schlüssel eingetragen.")
        val bearer = k.startsWith("eyJ")
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (n, v) -> addQueryParameter(n, v) }
            addQueryParameter("language", language())
            if (!bearer) addQueryParameter("api_key", k)
        }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $k") }
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 401 -> throw TmdbException("TMDB lehnt den Schlüssel ab.")
                r.code == 404 -> throw TmdbException("Bei TMDB nicht gefunden.")
                !r.isSuccessful -> throw TmdbException("TMDB antwortet mit ${r.code}.")
            }
            JSONObject(body)
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        fun poster(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w500$it" }
        fun backdrop(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w1280$it" }
        fun still(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w300$it" }

        private fun JSONObject.s(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

        private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

        fun parseResult(o: JSONObject, kind: Kind) = Result(
            id = o.getInt("id"),
            kind = kind,
            title = (if (kind == Kind.TV) o.s("name") else o.s("title")) ?: o.s("original_name") ?: o.s("original_title") ?: "",
            originalTitle = if (kind == Kind.TV) o.s("original_name") else o.s("original_title"),
            year = yearOf(if (kind == Kind.TV) o.s("first_air_date") else o.s("release_date")),
            poster = poster(o.s("poster_path")),
            backdrop = backdrop(o.s("backdrop_path")),
            overview = o.s("overview"),
        )

        fun parseSearch(o: JSONObject, kind: Kind): List<Result> {
            val arr = o.optJSONArray("results") ?: return emptyList()
            return List(arr.length()) { parseResult(arr.getJSONObject(it), kind) }
        }

        fun parseDetails(o: JSONObject, kind: Kind): Details {
            val seasons = o.optJSONArray("seasons")?.let { arr ->
                List(arr.length()) { i ->
                    val s = arr.getJSONObject(i)
                    SeasonInfo(s.getInt("season_number"), s.optInt("episode_count"), s.s("name"), poster(s.s("poster_path")))
                }
            }.orEmpty()
            val runtime = if (kind == Kind.MOVIE) o.optInt("runtime").takeIf { it > 0 }
            else o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else null }?.takeIf { it > 0 }
            return Details(parseResult(o, kind), seasons, runtime)
        }

        fun parseSeason(o: JSONObject): List<EpisodeInfo> {
            val arr = o.optJSONArray("episodes") ?: return emptyList()
            return List(arr.length()) { i ->
                val e = arr.getJSONObject(i)
                EpisodeInfo(
                    season = e.optInt("season_number"),
                    episode = e.optInt("episode_number"),
                    name = e.s("name"),
                    overview = e.s("overview"),
                    still = still(e.s("still_path")),
                    runtimeMin = e.optInt("runtime").takeIf { it > 0 },
                )
            }
        }
    }
}
