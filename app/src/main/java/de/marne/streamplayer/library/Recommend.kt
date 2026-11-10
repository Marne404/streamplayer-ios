package de.marne.streamplayer.library

import de.marne.streamplayer.ui.tr
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Empfehlungen, Regale und Auswertungen – alles aus dem, was schon auf dem Gerät liegt: Genres und
 * Bewertung von TMDB, der Fortschritt des aktiven Profils und seine „Meine Liste“. Kein Server,
 * keine fremden Daten; reine Funktionen, damit sie sich ohne Gerät prüfen lassen.
 */
object Recommend {

    /** Gesehen, angefangen oder unberührt – je Titel, für das aktive Profil. */
    enum class Seen { NONE, STARTED, DONE }

    fun seen(t: LibTitle, progress: Map<String, Progress>): Seen {
        if (t.kind == TitleKind.MOVIE) {
            val p = progress[Keys.movie(t)] ?: return Seen.NONE
            return when {
                p.finished -> Seen.DONE
                p.positionMs > 0 -> Seen.STARTED
                else -> Seen.NONE
            }
        }
        val prefix = "e:${t.id}:"
        val mine = progress.filterKeys { it.startsWith(prefix) }.values
        if (mine.none { it.finished || it.positionMs > 0 }) return Seen.NONE
        val total = t.episodeCount
        return if (total > 0 && mine.count { it.finished } >= total) Seen.DONE else Seen.STARTED
    }

    /**
     * Wie sehr das Profil ein Genre mag. Gesehen zählt voll, angefangen halb, „Meine Liste“ etwas
     * weniger; eine Serie höchstens dreifach, sonst überstimmt eine lange Serie alles andere.
     * Wer viele Genres hat, verteilt sein Gewicht (÷ √n) – sonst wöge ein Titel mit fünf Genres
     * fünfmal so viel wie einer mit einem.
     */
    fun taste(titles: List<LibTitle>, progress: Map<String, Progress>, myList: List<String>): Map<String, Double> {
        val engagement = HashMap<String, Double>()
        for ((key, p) in progress) {
            val id = Keys.parse(key)?.first ?: continue
            val v = when {
                p.finished -> 1.0
                p.positionMs > ProgressStore.MIN_RESUME_MS -> 0.5
                else -> 0.0
            }
            if (v > 0) engagement[id] = ((engagement[id] ?: 0.0) + v).coerceAtMost(3.0)
        }
        myList.forEach { engagement[it] = (engagement[it] ?: 0.0) + 0.7 }
        val out = HashMap<String, Double>()
        for (t in titles) {
            val e = engagement[t.id] ?: continue
            val g = t.genres
            if (g.isEmpty()) continue
            val share = e / sqrt(g.size.toDouble())
            g.forEach { out[it] = (out[it] ?: 0.0) + share }
        }
        return out
    }

    /**
     * „Für dich“: noch nicht fertig Gesehenes, nach Geschmack geordnet. Ohne Verlauf entscheidet die
     * Bewertung, dann das Neueste – so ist das Regal auch am ersten Abend nicht leer.
     */
    fun forYou(
        titles: List<LibTitle>,
        progress: Map<String, Progress>,
        myList: List<String>,
        exclude: Set<String> = emptySet(),
    ): List<LibTitle> {
        val taste = taste(titles, progress, myList)
        val max = taste.values.maxOrNull() ?: 0.0
        return titles
            .filter { it.id !in exclude && seen(it, progress) != Seen.DONE }
            .map { t ->
                val g = t.genres
                val like = if (max > 0 && g.isNotEmpty()) g.sumOf { taste[it] ?: 0.0 } / (max * sqrt(g.size.toDouble())) else 0.0
                val quality = ((t.rating ?: 6.5) - 6.5) / 3.5            // −1 … +1
                val fresh = if (seen(t, progress) == Seen.NONE) 0.15 else 0.0
                t to (like * 2.0 + quality * 0.6 + fresh)
            }
            .sortedWith(compareByDescending<Pair<LibTitle, Double>> { it.second }.thenByDescending { it.first.addedAt })
            .map { it.first }
    }

    /** Der zuletzt gesehene Titel mit Genres und was ihm ähnelt – für „Weil du … geschaut hast“. */
    fun becauseYouWatched(
        titles: List<LibTitle>,
        progress: Map<String, Progress>,
        exclude: Set<String> = emptySet(),
    ): Pair<LibTitle, List<LibTitle>>? {
        val byId = titles.associateBy { it.id }
        val recent = progress.entries
            .filter { it.value.finished || it.value.positionMs > ProgressStore.MIN_RESUME_MS }
            .sortedByDescending { it.value.updatedAt }
            .mapNotNull { Keys.parse(it.key)?.first?.let(byId::get) }
            .distinctBy { it.id }
            .firstOrNull { it.genres.isNotEmpty() } ?: return null
        val list = similar(recent, titles).filter { it.id !in exclude && seen(it, progress) != Seen.DONE }
        return if (list.size >= 2) recent to list else null
    }

    /** Ähnliche Titel: geteilte Genres (Jaccard), gleiche Art etwas vorn, dann die Bewertung. */
    fun similar(t: LibTitle, titles: List<LibTitle>, limit: Int = 12): List<LibTitle> {
        val mine = t.genres.toSet()
        if (mine.isEmpty()) return emptyList()
        return titles.asSequence()
            .filter { it.id != t.id }
            .map { o ->
                val other = o.genres.toSet()
                val inter = (mine intersect other).size
                val jaccard = if (inter == 0) 0.0 else inter.toDouble() / (mine union other).size
                o to (jaccard + (if (o.kind == t.kind) 0.15 else 0.0) + ((o.rating ?: 6.5) - 6.5) / 20.0).takeIf { inter > 0 }
            }
            .mapNotNull { (o, s) -> s?.let { o to it } }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** Alle Genres mit Anzahl, die häufigsten vorn. */
    fun genres(titles: List<LibTitle>): List<Pair<String, Int>> =
        titles.flatMap { it.genres }.groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * Genre-Regale: was dem Profil gefällt zuerst, dann die großen Genres. Ein Genre mit nur einem
     * Titel ist kein Regal. Innen: Ungesehenes vor Gesehenem, dann Bewertung.
     */
    fun genreRows(
        titles: List<LibTitle>,
        progress: Map<String, Progress>,
        myList: List<String>,
        min: Int = 2,
        max: Int = 6,
    ): List<Pair<String, List<LibTitle>>> {
        val taste = taste(titles, progress, myList)
        return genres(titles)
            .filter { it.second >= min }
            .sortedWith(compareByDescending<Pair<String, Int>> { taste[it.first] ?: 0.0 }.thenByDescending { it.second })
            .take(max)
            .map { (g, _) -> g to inGenre(titles, g, progress) }
    }

    fun inGenre(titles: List<LibTitle>, genre: String, progress: Map<String, Progress>): List<LibTitle> =
        titles.filter { genre in it.genres }
            .sortedWith(compareBy<LibTitle> { seen(it, progress) == Seen.DONE }.thenByDescending { it.rating ?: 0.0 }.thenByDescending { it.addedAt })

    /** Die besten zehn nach TMDB – nur mit genug Stimmen ([LibTitle.rating]). */
    fun topRated(titles: List<LibTitle>, limit: Int = 10): List<LibTitle> =
        titles.filter { it.rating != null }.sortedByDescending { it.rating }.take(limit)

    /**
     * Der große Titel oben: aus den besten fünf „Für dich“ einer pro Tag – sonst wäre es immer
     * derselbe. Nur mit Bild; ohne Bild ist ein Hero eine leere Fläche.
     */
    fun hero(candidates: List<LibTitle>, day: Long): LibTitle? {
        val pool = candidates.filter { it.wide != null || it.cover != null }.take(5)
        return if (pool.isEmpty()) null else pool[(day % pool.size).toInt()]
    }

    // --- Suche ---

    fun search(titles: List<LibTitle>, query: String): List<LibTitle> {
        val q = norm(query).split(' ').filter { it.isNotBlank() }
        if (q.isEmpty()) return emptyList()
        return titles
            .map { t ->
                val title = norm(listOfNotNull(t.title, t.tmdb?.title).joinToString(" "))
                val rest = norm((t.genres + t.tmdb?.cast.orEmpty() + t.tmdb?.creators.orEmpty() + listOfNotNull(t.shownYear?.toString())).joinToString(" "))
                val all = "$title $rest"
                val hit = q.all { it in all }
                // Treffer im Titel vor Treffern in Besetzung oder Genre; Wortanfang vor Wortmitte.
                val score = q.sumOf { w -> when { title.startsWith(w) || " $w" in " $title" -> 3; w in title -> 2; else -> 1 } }
                t to (if (hit) score else 0)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<LibTitle, Int>> { it.second }.thenBy { it.first.title })
            .map { it.first }
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9ß ]+"), " ")

    // --- Sortieren für Übersichten ---

    enum class Sort {
        RECOMMENDED, NEW, AZ, RATING, YEAR;

        val label: String get() = when (this) {
            RECOMMENDED -> tr("Empfohlen", "Recommended")
            NEW -> tr("Neu", "Newest")
            AZ -> "A–Z"
            RATING -> tr("Bewertung", "Rating")
            YEAR -> tr("Jahr", "Year")
        }
    }

    fun sort(list: List<LibTitle>, sort: Sort, progress: Map<String, Progress>, myList: List<String>, all: List<LibTitle>): List<LibTitle> = when (sort) {
        Sort.RECOMMENDED -> {
            val order = forYou(all, progress, myList).withIndex().associate { it.value.id to it.index }
            list.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        }
        Sort.NEW -> list.sortedByDescending { it.addedAt }
        Sort.AZ -> list.sortedBy { norm(it.title) }
        Sort.RATING -> list.sortedByDescending { it.rating ?: -1.0 }
        Sort.YEAR -> list.sortedByDescending { it.shownYear ?: 0 }
    }

    // --- Auswertung ---

    data class Stats(
        val watchedMs: Long,
        val weekMs: Long,
        val episodes: Int,
        val movies: Int,
        /** Lieblingsgenres mit Anteil 0–1 am Geschmack, höchstens vier. */
        val topGenres: List<Pair<String, Float>>,
    )

    fun stats(titles: List<LibTitle>, progress: Map<String, Progress>, myList: List<String>, now: Long = System.currentTimeMillis()): Stats {
        val ids = titles.mapTo(HashSet()) { it.id }
        var total = 0L
        var week = 0L
        var episodes = 0
        var movies = 0
        for ((key, p) in progress) {
            val parsed = Keys.parse(key) ?: continue
            if (parsed.first !in ids) continue
            val ms = if (p.finished) p.durationMs else p.positionMs
            total += ms
            if (now - p.updatedAt < 7L * 24 * 3600 * 1000) week += ms
            if (p.finished) if (parsed.second != null) episodes++ else movies++
        }
        val taste = taste(titles, progress, myList)
        val sum = taste.values.sum()
        val top = if (sum <= 0) emptyList() else taste.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(4).map { it.key to (it.value / sum).toFloat() }
        return Stats(total, week, episodes, movies, top)
    }
}
