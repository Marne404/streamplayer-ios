package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.ui.tr
import de.marne.streamplayer.ui.nowMillis

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
    val label: String get() = if (season == 0) "Special $number" else tr("S$season · F$number", "S$season · E$number")
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
    val addedAt: Long = nowMillis(),
    /** Bibliothek, in der der Titel steht ([Library]); null = die erste („Meine Bibliothek“). */
    val libraryId: String? = null,
    /**
     * Weitere Bibliotheken, in denen derselbe Titel auch steht – ein Titel, nicht mehrere Kopien:
     * Fortschritt, Downloads, Cover und TMDB-Angaben gelten überall. Anlass: Wer in eine neue
     * Bibliothek importierte, was schon in einer anderen stand, bekam es dort nicht zu sehen.
     */
    val alsoIn: List<String> = emptyList(),
) {
    /** Bibliothek, nie null. */
    val library: String get() = libraryId ?: Library.MAIN

    /** Alle Bibliotheken des Titels, die eigentliche zuerst. */
    val libraries: List<String> get() = if (alsoIn.isEmpty()) listOf(library) else (listOf(library) + alsoIn).distinct()

    fun isIn(libraryId: String): Boolean = library == libraryId || libraryId in alsoIn

    val host: String? get() = (pageUrl ?: template)?.let { hostOf(it.replace("{", "").replace("}", "")) }

    /**
     * Bild und Text stammen von der Webseite (og:image, Seitenbeschreibung) statt aus TMDB oder
     * vom Nutzer: ein Bild aus dem Netz, das nicht von TMDB ist. Dann gewinnt TMDB, sobald es einen
     * Treffer gibt. Anlass: Banner kam von TMDB, Cover und Beschreibung blieben die der Seite.
     * Eigene Bilder (Datei, data:) und TMDB-Bilder aus dem Katalog gewinnen weiter.
     */
    val siteInfo: Boolean get() = (poster ?: backdrop)?.let(::isSiteImage) == true

    /** Hochformat-Cover für Regale. */
    val cover: String? get() = customPoster ?: (if (siteInfo) tmdb?.poster ?: poster else poster ?: tmdb?.poster) ?: backdrop ?: tmdb?.backdrop

    /** Breitbild für Hero und Detailseite. */
    val wide: String? get() = (if (siteInfo) tmdb?.backdrop ?: backdrop else backdrop ?: tmdb?.backdrop) ?: poster ?: tmdb?.poster ?: customPoster

    /** Alte Stellen fragen nach einem Bild: das breite. */
    val image: String? get() = wide

    val text: String? get() = if (siteInfo) tmdb?.overview ?: description else description ?: tmdb?.overview
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
        seasons.firstOrNull { it.number == number }?.title ?: if (number == 0) "Specials" else tr("Staffel $number", "Season $number")

    companion object
}

/** Bild aus dem Netz, aber nicht von TMDB – also von der Webseite. */
internal fun isSiteImage(url: String) = url.startsWith("http") && "image.tmdb.org" !in url

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

/** Host einer Adresse ohne „www.“ – wie früher über java.net.URI, aber auch auf dem iPad. */
internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", missingDelimiterValue = "").ifEmpty { return null }
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.lowercase().removePrefix("www.").ifEmpty { null }
}
