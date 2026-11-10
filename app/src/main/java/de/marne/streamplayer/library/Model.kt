package de.marne.streamplayer.library

import org.json.JSONArray
import org.json.JSONObject

enum class TitleKind { MOVIE, SERIES }

/** Eine Folge, erzeugt aus dem Muster der Serie. */
data class Episode(val season: Int, val number: Int, val url: String) {
    val label: String get() = "S$season · F$number"
}

/**
 * Ein Titel der Mediathek – Film oder Serie.
 *
 * Gespeichert wird immer die *Seite*, nie der Stream: Stream-Adressen der Hoster leben
 * wenige Stunden. Aufgelöst wird erst beim Abspielen.
 *
 * Serie: [template] ist eine Adresse mit `{s}` und `{e}` (zweistellig: `{ss}`, `{ee}`),
 * [seasons] die Folgenzahl je Staffel. Daraus entstehen alle Folgen.
 */
data class LibTitle(
    val id: String,
    val kind: TitleKind,
    val title: String,
    val pageUrl: String?,
    val template: String? = null,
    val seasons: List<Int> = emptyList(),
    val image: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    /** Fertige Downloads: Fortschritts-Schlüssel ([Keys]) → content://-Adresse. */
    val offline: Map<String, String> = emptyMap(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    val host: String? get() = (pageUrl ?: template)?.let { runCatching { java.net.URI(it.replace("{", "").replace("}", "")).host?.removePrefix("www.") }.getOrNull() }

    fun episodes(): List<Episode> = if (kind != TitleKind.SERIES || template == null) emptyList()
    else seasons.flatMapIndexed { i, count -> (1..count).map { e -> Episode(i + 1, e, Pattern.fill(template, i + 1, e)) } }

    fun episode(season: Int, number: Int): Episode? = episodes().firstOrNull { it.season == season && it.number == number }

    fun next(after: Episode): Episode? {
        val all = episodes()
        val i = all.indexOfFirst { it.season == after.season && it.number == after.number }
        return all.getOrNull(i + 1)
    }

    val episodeCount: Int get() = seasons.sum()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("title", title); put("pageUrl", pageUrl)
        put("template", template); put("seasons", JSONArray(seasons)); put("image", image)
        put("description", description); put("durationMs", durationMs)
        put("offline", JSONObject(offline as Map<*, *>)); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): LibTitle {
            val s = o.optJSONArray("seasons") ?: JSONArray()
            val off = o.optJSONObject("offline") ?: JSONObject()
            return LibTitle(
                id = o.getString("id"),
                kind = TitleKind.valueOf(o.getString("kind")),
                title = o.optString("title"),
                pageUrl = o.str("pageUrl"),
                template = o.str("template"),
                seasons = List(s.length()) { s.getInt(it) },
                image = o.str("image"),
                description = o.str("description"),
                durationMs = if (o.isNull("durationMs") || !o.has("durationMs")) null else o.optLong("durationMs"),
                offline = off.keys().asSequence().associateWith { off.getString(it) },
                addedAt = o.optLong("addedAt"),
            )
        }

        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
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

/** Muster für Serien: aus einer Folgen-Adresse wird eine Vorlage, aus der Vorlage jede Folge. */
object Pattern {

    fun fill(template: String, season: Int, episode: Int): String = template
        .replace("{ss}", season.toString().padStart(2, '0'))
        .replace("{ee}", episode.toString().padStart(2, '0'))
        .replace("{s}", season.toString())
        .replace("{e}", episode.toString())

    private val seasonWord = Regex("""(staffel|season|saison|stagione|temporada|s)([-_/ ]?)(\d{1,3})(?=\D|$)""", RegexOption.IGNORE_CASE)
    private val episodeWord = Regex("""(episode|episodio|folge|ep|e)([-_/ ]?)(\d{1,4})(?=\D|$)""", RegexOption.IGNORE_CASE)

    /**
     * Schlägt aus der Adresse einer Folge eine Vorlage vor. Erst benannte Teile
     * (staffel-2, episode-5, s02e05), sonst die letzten beiden Zahlen im Pfad.
     * Nur der Pfad wird angefasst, nie der Host.
     */
    fun derive(url: String): String? {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
        val pathStart = url.indexOf('/', schemeEnd).takeIf { it >= 0 } ?: return null
        val head = url.substring(0, pathStart)
        var path = url.substring(pathStart)

        val s = seasonWord.findAll(path).lastOrNull()
        val e = episodeWord.findAll(path).lastOrNull { it.range.first > (s?.range?.last ?: -1) || s == null }
        if (e != null) {
            path = replaceNumber(path, e)
            if (s != null && s.range.last < e.range.first) path = replaceNumber(path, s, season = true)
            return head + path
        }
        // Ohne Namen: die letzten zwei Zahlen sind Staffel und Folge, eine einzelne ist die Folge.
        val numbers = Regex("""\d+""").findAll(path).toList()
        if (numbers.isEmpty()) return null
        val sb = StringBuilder(path)
        val last = numbers.last()
        sb.replace(last.range.first, last.range.last + 1, placeholder(last.value, "e"))
        if (numbers.size >= 2) {
            val prev = numbers[numbers.size - 2]
            sb.replace(prev.range.first, prev.range.last + 1, placeholder(prev.value, "s"))
        }
        return head + sb
    }

    private fun replaceNumber(path: String, m: MatchResult, season: Boolean = false): String {
        val digits = m.groups[3]!!
        val ph = placeholder(digits.value, if (season) "s" else "e")
        return path.replaceRange(digits.range, ph)
    }

    private fun placeholder(digits: String, letter: String) =
        if (digits.length >= 2 && digits.startsWith('0')) "{$letter$letter}" else "{$letter}"

    /** "12, 8 10" → [12, 8, 10]; Unsinn → leer */
    fun parseSeasons(text: String): List<Int> =
        text.split(',', ';', ' ', '/').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..999 }
}
