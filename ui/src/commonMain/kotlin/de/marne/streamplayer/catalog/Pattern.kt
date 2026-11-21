package de.marne.streamplayer.catalog

/**
 * Link-Muster für Serien. Platzhalter:
 *  {s} {ss}          Staffel (ss = zweistellig)
 *  {e} {ee} {eee}    Folge innerhalb der Staffel
 *  {a} {aa} {aaa}    absolute Folge über alle Staffeln (Anime-Seiten zählen oft so)
 */
object Pattern {

    val PLACEHOLDERS = listOf("{s}", "{ss}", "{e}", "{ee}", "{eee}", "{a}", "{aa}", "{aaa}")

    fun fill(template: String, season: Int, episode: Int, absolute: Int = episode): String = template
        .replace("{ss}", pad(season, 2))
        .replace("{eee}", pad(episode, 3))
        .replace("{ee}", pad(episode, 2))
        .replace("{aaa}", pad(absolute, 3))
        .replace("{aa}", pad(absolute, 2))
        .replace("{s}", season.toString())
        .replace("{e}", episode.toString())
        .replace("{a}", absolute.toString())

    /** Enthält das Muster eine Folgennummer? Ohne sie wären alle Folgen dieselbe Adresse. */
    fun hasEpisode(template: String): Boolean =
        listOf("{e}", "{ee}", "{eee}", "{a}", "{aa}", "{aaa}").any { template.contains(it) }

    private fun pad(n: Int, width: Int) = n.toString().padStart(width, '0')

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
        val e = episodeWord.findAll(path).lastOrNull { s == null || it.range.first > s.range.last }
        if (e != null) {
            path = replaceNumber(path, e, "e")
            if (s != null && s.range.last < e.range.first) path = replaceNumber(path, s, "s")
            return head + path
        }
        val numbers = Regex("""\d+""").findAll(path).toList()
        if (numbers.isEmpty()) return null
        val sb = StringBuilder(path)
        val last = numbers.last()
        sb.setRange(last.range.first, last.range.last + 1, placeholder(last.value, "e"))
        if (numbers.size >= 2) {
            val prev = numbers[numbers.size - 2]
            sb.setRange(prev.range.first, prev.range.last + 1, placeholder(prev.value, "s"))
        }
        return head + sb
    }

    private fun replaceNumber(path: String, m: MatchResult, letter: String): String {
        val digits = m.groups[3]!!
        return path.replaceRange(digits.range, placeholder(digits.value, letter))
    }

    private fun placeholder(digits: String, letter: String) = when {
        digits.length >= 3 && digits.startsWith('0') && letter == "e" -> "{eee}"
        digits.length >= 2 && digits.startsWith('0') -> "{$letter$letter}"
        else -> "{$letter}"
    }

    /** "12, 8 10" → [12, 8, 10]; Unsinn → leer */
    fun parseSeasons(text: String): List<Int> =
        text.split(',', ';', ' ', '/').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..999 }
}
