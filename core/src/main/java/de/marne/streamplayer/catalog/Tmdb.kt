package de.marne.streamplayer.catalog

import de.marne.streamplayer.ui.tr
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

    data class Details(
        val result: Result,
        val seasons: List<SeasonInfo>,
        val runtimeMin: Int?,
        /** Genres in der eingestellten Sprache („Krimi“, „Drama“). */
        val genres: List<String> = emptyList(),
        /** TMDB-Bewertung 0–10 und wie viele sie abgegeben haben – wenige Stimmen sagen wenig. */
        val rating: Double? = null,
        val votes: Int? = null,
        val tagline: String? = null,
        /** Die ersten Namen der Besetzung. */
        val cast: List<String> = emptyList(),
        /** Film: Regie; Serie: wer sie erfunden hat. */
        val creators: List<String> = emptyList(),
    )

    data class EpisodeInfo(val season: Int, val episode: Int, val name: String?, val overview: String?, val still: String?, val runtimeMin: Int?)

    /** [code]: HTTP-Status (0: kein Schlüssel). Netzfehler sind gewöhnliche IOExceptions. */
    class TmdbException(message: String, val code: Int = 0) : IOException(message)

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

    /**
     * Mit Besetzung und Bildern in derselben Anfrage (append_to_response) – eine Abfrage pro Titel.
     * Bilder in der eingestellten Sprache, auf Englisch und ohne Sprache („null“): fehlt das Poster
     * in der Wunschsprache, nimmt [parseDetails] eines davon, statt ganz ohne Cover dazustehen.
     */
    suspend fun details(kind: Kind, id: Int): Details {
        val lang = language().substringBefore('-').lowercase().ifBlank { "en" }
        val params = mapOf(
            "append_to_response" to "credits,images",
            "include_image_language" to listOf(lang, "en", "null").distinct().joinToString(","),
        )
        return parseDetails(get("${kind.path}/$id", params), kind, lang)
    }

    suspend fun season(tvId: Int, season: Int): List<EpisodeInfo> = parseSeason(get("tv/$tvId/season/$season"))

    /**
     * Alle Poster eines Titels zur Auswahl (Cover ändern): Wunschsprache zuerst, dann Englisch,
     * dann ohne Schrift, dann der Rest – je nach Bewertung. Rückgabe: Bildpfade (für [poster]).
     */
    suspend fun posters(kind: Kind, id: Int): List<String> {
        val lang = language().substringBefore('-').lowercase().ifBlank { "en" }
        val o = get("${kind.path}/$id/images", mapOf("include_image_language" to listOf(lang, "en", "null").distinct().joinToString(",")))
        return parsePosters(o, lang)
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val k = key()?.trim().takeIf { !it.isNullOrEmpty() } ?: throw TmdbException(tr("Kein TMDB-Schlüssel eingetragen.", "No TMDB key entered."))
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
                r.code == 401 -> throw TmdbException(tr("TMDB lehnt den Schlüssel ab.", "TMDB rejected the key."), 401)
                r.code == 404 -> throw TmdbException(tr("Bei TMDB nicht gefunden.", "Not found on TMDB."), 404)
                !r.isSuccessful -> throw TmdbException(tr("TMDB antwortet mit ${r.code}.", "TMDB responded with ${r.code}."), r.code)
            }
            JSONObject(body)
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"

        // Bildadressen liegen im gemeinsamen Modul :ui (TmdbImages), damit auch das iPad sie hat.
        fun poster(path: String?) = TmdbImages.poster(path)
        fun backdrop(path: String?) = TmdbImages.backdrop(path)
        fun still(path: String?) = TmdbImages.still(path)
        fun large(url: String?): String? = TmdbImages.large(url)
        fun largeModel(model: Any?): Any? = TmdbImages.largeModel(model)

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

        /**
         * Bestes Bild aus `images.posters`/`images.backdrops`: Wunschsprache, dann Englisch, dann
         * ohne Sprache (meist textfreie Motive), dann irgendeins – innerhalb davon nach Bewertung.
         */
        fun pickImage(o: JSONObject, list: String, lang: String): String? {
            val arr = o.optJSONObject("images")?.optJSONArray(list) ?: return null
            val all = List(arr.length()) { arr.getJSONObject(it) }.filter { it.s("file_path") != null }
            if (all.isEmpty()) return null
            fun rank(i: JSONObject): Int = when (i.s("iso_639_1")) {
                lang -> 0
                "en" -> 1
                null -> 2
                else -> 3
            }
            return all.sortedWith(compareBy<JSONObject> { rank(it) }.thenByDescending { it.optDouble("vote_average", 0.0) }).first().s("file_path")
        }

        fun parsePosters(o: JSONObject, lang: String): List<String> {
            val arr = o.optJSONArray("posters") ?: return emptyList()
            val all = List(arr.length()) { arr.getJSONObject(it) }.filter { it.s("file_path") != null }
            fun rank(i: JSONObject): Int = when (i.s("iso_639_1")) { lang -> 0; "en" -> 1; null -> 2; else -> 3 }
            return all.sortedWith(compareBy<JSONObject> { rank(it) }.thenByDescending { it.optDouble("vote_average", 0.0) })
                .mapNotNull { it.s("file_path") }.distinct().take(60)
        }

        /** Kleines Poster für Auswahl-Raster. */
        fun posterSmall(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w342$it" }

        fun parseDetails(o: JSONObject, kind: Kind, lang: String = "de"): Details {
            val seasons = o.optJSONArray("seasons")?.let { arr ->
                List(arr.length()) { i ->
                    val s = arr.getJSONObject(i)
                    SeasonInfo(s.getInt("season_number"), s.optInt("episode_count"), s.s("name"), poster(s.s("poster_path")))
                }
            }.orEmpty()
            val runtime = if (kind == Kind.MOVIE) o.optInt("runtime").takeIf { it > 0 }
            else o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else null }?.takeIf { it > 0 }
            val genres = o.optJSONArray("genres")?.let { arr -> List(arr.length()) { arr.getJSONObject(it).s("name") }.filterNotNull() }.orEmpty()
            val credits = o.optJSONObject("credits")
            val cast = credits?.optJSONArray("cast")?.let { arr ->
                List(arr.length()) { arr.getJSONObject(it) }.sortedBy { it.optInt("order", 999) }.mapNotNull { it.s("name") }.distinct().take(8)
            }.orEmpty()
            val creators = if (kind == Kind.TV) {
                o.optJSONArray("created_by")?.let { arr -> List(arr.length()) { arr.getJSONObject(it).s("name") }.filterNotNull() }.orEmpty()
            } else {
                credits?.optJSONArray("crew")?.let { arr ->
                    List(arr.length()) { arr.getJSONObject(it) }.filter { it.s("job") == "Director" }.mapNotNull { it.s("name") }.distinct()
                }.orEmpty()
            }
            // Ohne Poster in der Wunschsprache: aus den mitgelieferten Bildern einen Ersatz wählen.
            val base = parseResult(o, kind)
            val result = base.copy(
                poster = base.poster ?: poster(pickImage(o, "posters", lang)),
                backdrop = base.backdrop ?: backdrop(pickImage(o, "backdrops", lang)),
            )
            return Details(
                result, seasons, runtime,
                genres = genres,
                rating = o.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
                votes = o.optInt("vote_count").takeIf { it > 0 },
                tagline = o.s("tagline"),
                cast = cast,
                creators = creators.take(3),
            )
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
