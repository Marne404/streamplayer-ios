package de.marne.streamplayer.library

import java.text.Normalizer

/**
 * Vorbereitete Suche über eine Titelliste. Früher wurde bei jedem Tastendruck jeder Titel neu
 * normalisiert (Regex über Beschreibung, Besetzung …) und für jede Serie die ganze Folgenliste
 * erzeugt – bei ein paar tausend Titeln hing dabei die Tastatur. Jetzt geschieht das einmal je
 * Liste, im Hintergrund; die Suche selbst vergleicht nur noch fertige Zeichenketten.
 *
 * Ändert sich die Liste (Anreicherung, Abgleich), werden nur geänderte Titel neu aufbereitet:
 * Unveränderte Titel sind dasselbe Objekt wie vorher ([LibraryStore] ersetzt nur, was sich ändert).
 */
class SearchIndex private constructor(
    /** Die Liste, aus der der Index gebaut ist – zum Wiedererkennen (gleiches Objekt). */
    val source: List<LibTitle>,
    private val entries: List<Entry>,
    private val people: List<Person>,
) {
    private class Entry(
        val t: LibTitle,
        val title: String,
        /** Mit Leerzeichen davor: „ wort“ darin heißt „Wortanfang“. */
        val titleSp: String,
        val rest: String,
        val all: String,
        /** (Name, „ normalisierter Name“) – Besetzung und Macher, ohne Doppelte. */
        val people: List<Pair<String, String>>,
        val episodes: List<Ep>,
    )

    private class Person(val name: String, val normSp: String, val titles: List<LibTitle>)

    private class Ep(val e: Episode, val title: String, val withShow: String)

    fun search(query: String): List<LibTitle> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<LibTitle, Int>>()
        for (x in entries) {
            if (!q.all { it in x.all }) continue
            // Titel vor Besetzung/Genre vor Beschreibung; Wortanfang vor Wortmitte.
            val score = q.sumOf { w -> when { " $w" in x.titleSp -> 6; w in x.title -> 4; w in x.rest -> 2; else -> 1 } }
            hits += x.t to score
        }
        return hits.sortedWith(compareByDescending<Pair<LibTitle, Int>> { it.second }.thenBy { it.first.title }).map { it.first }
    }

    /**
     * Personen erst ab drei Buchstaben – sonst passt „an“ auf die halbe Besetzung. Folgen über ihren
     * Titel (der Serienname darf mitgesucht werden: „lost pilot“).
     */
    fun searchAll(query: String): Recommend.Found {
        val q = words(query)
        if (q.isEmpty()) return Recommend.Found.EMPTY
        val ppl = if (q.sumOf { it.length } < 3) emptyList() else people
            .filter { p -> q.all { w -> " $w" in p.normSp } }
            .sortedWith(compareByDescending<Person> { it.titles.size }.thenBy { it.name })
            .take(8)
            .map { it.name to it.titles }
        val eps = ArrayList<Pair<LibTitle, Episode>>()
        outer@ for (x in entries) for (ep in x.episodes) {
            if (q.all { it in ep.withShow } && q.any { it in ep.title }) {
                eps += x.t to ep.e
                if (eps.size >= 30) break@outer
            }
        }
        return Recommend.Found(search(query), ppl, eps)
    }

    companion object {
        /** Zuletzt gebauter Index: Wer „Entdecken“ wieder öffnet, wartet nicht noch einmal. */
        @Volatile private var last: SearchIndex? = null

        /** Fertiger Index für genau diese Liste, falls es ihn schon gibt. */
        fun cached(titles: List<LibTitle>): SearchIndex? = last?.takeIf { it.source === titles }

        /**
         * Index für [titles]; nutzt, was der letzte Index schon aufbereitet hat. [check] wird
         * zwischendurch gerufen – zum Abbrechen, wenn die Liste sich schon wieder geändert hat.
         */
        fun of(titles: List<LibTitle>, check: () -> Unit = {}): SearchIndex {
            cached(titles)?.let { return it }
            val before = last?.entries?.associateBy { it.t.id }.orEmpty()
            val entries = ArrayList<Entry>(titles.size)
            titles.forEachIndexed { i, t ->
                if (i % 128 == 0) check()
                entries += before[t.id]?.takeIf { it.t === t } ?: entry(t)
            }
            // Personen über alle Titel sammeln; Reihenfolge der Titel bleibt die der Liste.
            val byName = LinkedHashMap<String, Pair<String, ArrayList<LibTitle>>>()
            for (x in entries) for ((name, normSp) in x.people) {
                byName.getOrPut(name) { normSp to ArrayList() }.second += x.t
            }
            val people = byName.map { (name, v) -> Person(name, v.first, v.second.distinct()) }
            return SearchIndex(titles, entries, people).also { last = it }
        }

        private fun entry(t: LibTitle): Entry {
            val title = fold(listOfNotNull(t.title, t.tmdb?.title).joinToString(" "))
            val rest = fold((t.genres + t.tmdb?.cast.orEmpty() + t.tmdb?.creators.orEmpty() + listOfNotNull(t.shownYear?.toString())).joinToString(" "))
            val text = fold(listOfNotNull(t.description, t.tmdb?.overview, t.tmdb?.tagline).joinToString(" "))
            val people = (t.tmdb?.cast.orEmpty() + t.tmdb?.creators.orEmpty()).distinct().map { it to " " + fold(it) }
            // Nur Folgen mit eigenem Namen sind auffindbar – die übrigen gar nicht erst merken.
            val episodes = if (t.kind != TitleKind.SERIES) emptyList() else {
                val show = fold(t.title)
                t.episodes().mapNotNull { e -> e.title?.let { fold(it) }?.takeIf { it.isNotBlank() }?.let { Ep(e, it, "$show $it") } }
            }
            return Entry(t, title, " $title", rest, "$title $rest $text", people, episodes)
        }

        fun words(query: String) = fold(query).split(' ').filter { it.isNotBlank() }

        /**
         * Für den Vergleich: klein, ohne Akzente („Amélie“ → „amelie“), alles außer Buchstaben und
         * Ziffern wird ein Leerzeichen. Ohne Regex und – bei reinem ASCII – ohne Normalizer: das
         * läuft für jeden Titel, jede Beschreibung und jeden Namen.
         */
        fun fold(s: String): String {
            val src = if (s.any { it.code >= 128 }) Normalizer.normalize(s, Normalizer.Form.NFD) else s
            val out = StringBuilder(src.length)
            var space = false
            for (ch in src) {
                val c = ch.lowercaseChar()
                when {
                    c in 'a'..'z' || c in '0'..'9' -> { out.append(c); space = false }
                    c.code < 128 -> if (!space) { out.append(' '); space = true }
                    isMark(c) -> {}
                    // Andere Schriften (Kyrillisch, Japanisch …) bleiben suchbar.
                    Character.isLetterOrDigit(c) -> { out.append(c); space = false }
                    else -> if (!space) { out.append(' '); space = true }
                }
            }
            return out.toString()
        }

        private fun isMark(c: Char) = when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }
}
