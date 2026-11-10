package de.marne.streamplayer.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class Progress(val positionMs: Long, val durationMs: Long, val finished: Boolean, val updatedAt: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/** Wiedergabefortschritt je Film/Folge ([Keys]). Fortsetzen, „Weiterschauen“, „Nächste Folge“. */
@OptIn(FlowPreview::class)
class ProgressStore(context: Context) {
    private val file = File(context.filesDir, "progress.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()

    init {
        scope.launch { _all.drop(1).debounce(500).collect { save(it) } }
    }

    operator fun get(key: String): Progress? = _all.value[key]

    /** Ab hier weiterspielen: nur bei echtem Fortschritt und nicht am Ende. */
    fun resumePosition(key: String): Long {
        val p = _all.value[key] ?: return 0
        return if (!p.finished && p.positionMs > MIN_RESUME_MS) p.positionMs else 0
    }

    fun report(key: String, positionMs: Long, durationMs: Long, ended: Boolean = false) {
        if (durationMs <= 0 && !ended) return
        // Abspann zählt als gesehen: weniger als 2 Minuten oder 5 % übrig.
        val finished = ended || (durationMs > 0 && durationMs - positionMs < maxOf(120_000L, durationMs / 20))
        _all.update { it + (key to Progress(positionMs, durationMs, finished, System.currentTimeMillis())) }
    }

    fun setWatched(key: String, watched: Boolean) = _all.update { map ->
        if (watched) {
            val d = map[key]?.durationMs ?: 0
            map + (key to Progress(d, d, true, System.currentTimeMillis()))
        } else map - key
    }

    fun removeTitle(id: String) = _all.update { map -> map.filterKeys { Keys.parse(it)?.first != id } }

    private fun load(): Map<String, Progress> = runCatching {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val p = o.getJSONObject(k)
            Progress(p.optLong("p"), p.optLong("d"), p.optBoolean("f"), p.optLong("u"))
        }
    }.getOrDefault(emptyMap())

    private fun save(map: Map<String, Progress>) {
        val o = JSONObject()
        map.forEach { (k, p) -> o.put(k, JSONObject().put("p", p.positionMs).put("d", p.durationMs).put("f", p.finished).put("u", p.updatedAt)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    companion object {
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
            if (!p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS) {
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
