package de.marne.streamplayer.library

/**
 * [watchedMs]: wirklich abgespielte Zeit (Uhr läuft nur, solange das Bild läuft – Vorspulen zählt
 * nicht). -1 = Eintrag von vor 1.10, als nur die Position gespeichert wurde; dann gilt die alte
 * Schätzung [countedMs].
 */
data class Progress(val positionMs: Long, val durationMs: Long, val finished: Boolean, val updatedAt: Long, val watchedMs: Long = -1) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    /** Für die Statistik: echte Sehzeit, bei alten Einträgen die frühere Schätzung. */
    val countedMs: Long get() = if (watchedMs >= 0) watchedMs else if (finished) durationMs else positionMs

    companion object {
        /** Ab hier gilt ein Film/eine Folge als angefangen (Weiterschauen, Fortsetzen). */
        const val MIN_RESUME_MS = 30_000L
    }
}

/** Ein Eintrag in „Weiterschauen“: angefangene Filme/Folgen und die nächste Folge einer Serie. */
data class ContinueItem(val title: LibTitle, val episode: Episode?, val progress: Progress?, val isNextUp: Boolean) {
    val key: String get() = if (episode != null) Keys.episode(title, episode) else Keys.movie(title)
}

object Shelves {
    fun continueWatching(titles: List<LibTitle>, progress: Map<String, Progress>): List<ContinueItem> {
        val byId = titles.associateBy { it.id }
        val out = mutableListOf<Pair<Long, ContinueItem>>()
        // Je Titel nur der zuletzt bewegte Eintrag.
        val latest = progress.entries
            .mapNotNull { (k, p) -> Keys.parse(k)?.let { Triple(it, k, p) } }
            .groupBy { it.first.first }
            .mapValues { (_, list) -> list.maxBy { it.third.updatedAt } }
        for ((id, entry) in latest) {
            val t = byId[id] ?: continue
            val (parsed, _, p) = entry
            val ep = if (parsed.second != null && parsed.third != null) t.episode(parsed.second!!, parsed.third!!) else null
            if (!p.finished && p.positionMs > Progress.MIN_RESUME_MS) {
                out += p.updatedAt to ContinueItem(t, ep, p, isNextUp = false)
            } else if (p.finished && ep != null) {
                val next = t.next(ep) ?: continue
                val np = progress[Keys.episode(t, next)]
                if (np?.finished == true) continue
                out += p.updatedAt to ContinueItem(t, next, np, isNextUp = true)
            }
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** Nächste Folge zum Abspielen auf der Detailseite: angefangene, sonst die nach der letzten gesehenen, sonst Folge 1. */
    fun nextFor(t: LibTitle, progress: Map<String, Progress>): Pair<Episode, Progress?>? {
        val eps = t.episodes()
        if (eps.isEmpty()) return null
        val withP = eps.mapNotNull { e -> progress[Keys.episode(t, e)]?.let { e to it } }
        val last = withP.maxByOrNull { it.second.updatedAt } ?: return eps.first() to null
        if (!last.second.finished) return last
        val next = t.next(last.first) ?: return null
        return next to progress[Keys.episode(t, next)]
    }
}
