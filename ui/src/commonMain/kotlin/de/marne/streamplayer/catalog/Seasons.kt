package de.marne.streamplayer.catalog

// Staffeln und erzeugte Folgen – reine Daten, gemeinsam für Android und iPad (aus Catalog.kt).

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
