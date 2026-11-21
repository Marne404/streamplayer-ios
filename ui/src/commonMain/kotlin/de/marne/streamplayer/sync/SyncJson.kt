package de.marne.streamplayer.sync

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.TitleCollection
import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.library.EpInfo
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.library.TmdbInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Liest die JSON-Texte, die die Android-App ins Konto schreibt (LibTitle.toJson & Co. mit
 * org.json) – ohne org.json, damit es auch auf dem iPad geht. Gleiche Regeln wie die
 * Android-Leser in ModelJson.kt/LibraryJson.kt/ProfileJson.kt/ProgressStore.kt: fehlende oder
 * leere Felder werden null, Zahlen dürfen auch als Text kommen. Ein Test auf Android
 * (SyncJsonParityTest) prüft, dass beide Seiten dasselbe ergeben.
 */
object SyncJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Platzhalter für ein eigenes Bild, das als eigenes Dokument (covers/{name}) mitreist. */
    const val COVER = "cover:"
    private val IMAGE_FIELDS = listOf("poster", "backdrop", "customPoster")

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    fun title(text: String): LibTitle = title(parse(text))

    fun title(o: JsonObject): LibTitle {
        val seasons = o.arr("seasons").mapIndexed { i, v ->
            when (v) {
                is JsonPrimitive -> SeasonSpec(i + 1, v.intOrNull ?: 0) // bis 1.0: nur Folgenzahlen
                is JsonObject -> SeasonSpec(v.int("number") ?: (i + 1), v.int("episodes") ?: 0, v.int("start") ?: 1, v.str("template"), v.str("title"))
                else -> SeasonSpec(i + 1, 0)
            }
        }
        val overrides = o.arr("overrides").filterIsInstance<JsonObject>().map { e ->
            EpisodeOverride(e.int("season") ?: 0, e.int("episode") ?: 0, e.str("url"), e.str("title"))
        }
        val tm = o["tmdb"] as? JsonObject
        val ei = o["episodeInfo"] as? JsonObject
        val off = o["offline"] as? JsonObject
        return LibTitle(
            id = o.str("id") ?: error("Titel ohne id"),
            kind = TitleKind.valueOf(o.str("kind") ?: error("Titel ohne kind")),
            title = o.str("title").orEmpty(),
            pageUrl = o.str("pageUrl"),
            template = o.str("template"),
            seasons = seasons,
            overrides = overrides,
            poster = o.str("poster"),
            backdrop = o.str("backdrop") ?: o.str("image"), // bis 1.0 hieß es „image“
            customPoster = o.str("customPoster"),
            description = o.str("description"),
            durationMs = o.long("durationMs"),
            year = o.long("year")?.toInt(),
            tmdbId = o.long("tmdbId")?.toInt(),
            tmdbQuery = o.str("tmdbQuery"),
            tmdb = tm?.let {
                TmdbInfo(
                    it.int("id") ?: 0, it.str("title"), it.str("poster"), it.str("backdrop"), it.str("overview"),
                    it.long("year")?.toInt(), it.long("runtimeMin")?.toInt(),
                    genres = (it["genres"] as? JsonArray)?.strings(),
                    rating = it.double("rating")?.takeIf { r -> !r.isNaN() },
                    votes = it.long("votes")?.toInt(),
                    tagline = it.str("tagline"),
                    cast = (it["cast"] as? JsonArray)?.strings().orEmpty(),
                    creators = (it["creators"] as? JsonArray)?.strings().orEmpty(),
                )
            },
            sourceId = o.str("sourceId"),
            episodeInfo = ei?.mapNotNull { (k, v) ->
                (v as? JsonObject)?.let { e -> k to EpInfo(e.str("title"), e.str("overview"), e.str("still"), e.long("runtimeMin")?.toInt()) }
            }?.toMap().orEmpty(),
            offline = off?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNullSafe()?.let { k to it } }?.toMap().orEmpty(),
            addedAt = o.long("addedAt") ?: 0L,
            libraryId = o.str("library"),
            alsoIn = o.arr("alsoIn").strings(),
        )
    }

    fun library(text: String): Library {
        val o = parse(text)
        return Library(
            id = o.str("id") ?: error("Bibliothek ohne id"),
            name = o.str("name").orEmpty(),
            color = o.int("color") ?: 0,
            createdAt = o.long("createdAt") ?: 0L,
            shared = o.bool("shared") ?: false,
            invite = o.str("invite")?.takeIf { it.isNotBlank() },
            joined = o.bool("joined") ?: false,
            owner = o.str("owner")?.takeIf { it.isNotBlank() },
        )
    }

    fun profile(text: String): Profile {
        val o = parse(text)
        return Profile(
            o.str("id") ?: error("Profil ohne id"),
            (o["name"] as? JsonPrimitive)?.contentOrNullSafe() ?: "Profil",
            o.int("color") ?: 0,
            o.long("createdAt") ?: 0L,
            o.arr("list").strings(),
            o.str("lock"),
            o.arr("collections").filterIsInstance<JsonObject>().map { c ->
                TitleCollection(c.str("id") ?: "", c.str("name").orEmpty(), c.arr("items").strings(), c.long("createdAt") ?: 0L)
            },
        )
    }

    /** Fortschritt eines Titels: Schlüssel (Keys) → Stand, wie ProgressMerge.toJson ihn schreibt. */
    fun progress(text: String): Map<String, Progress> = parse(text).mapNotNull { (k, v) ->
        (v as? JsonObject)?.let { p ->
            k to Progress(p.long("p") ?: 0, p.long("d") ?: 0, p.bool("f") ?: false, p.long("u") ?: 0, p.long("w") ?: -1)
        }
    }.toMap()

    /** Namen der eigenen Bilder (covers/{name}), die ein Titel braucht. */
    fun coverNames(o: JsonObject): List<String> =
        IMAGE_FIELDS.mapNotNull { f -> o.str(f)?.takeIf { it.startsWith(COVER) }?.removePrefix(COVER) }

    /**
     * Platzhalter „cover:name“ durch die Adresse des geladenen Bildes ersetzen. Fehlt das Bild,
     * fällt das Feld weg (auf dem iPad wird nichts zurückgeschrieben, ein leeres Feld schadet nicht).
     */
    fun resolveCovers(o: JsonObject, resolve: (String) -> String?): JsonObject = JsonObject(
        o.mapValues { (k, v) ->
            if (k !in IMAGE_FIELDS) return@mapValues v
            val s = (v as? JsonPrimitive)?.contentOrNullSafe() ?: return@mapValues v
            if (!s.startsWith(COVER)) v else resolve(s.removePrefix(COVER))?.let { JsonPrimitive(it) } ?: JsonNull
        },
    )

    // --- org.json-ähnliche Zugriffe ---

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.int(k: String): Int? = long(k)?.toInt()
    private fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.doubleOrNull }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.booleanOrNull }
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
    private fun List<JsonElement>.strings(): List<String> =
        mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }.filter { it.isNotEmpty() }
}
