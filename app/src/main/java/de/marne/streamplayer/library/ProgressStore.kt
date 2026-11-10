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

/**
 * Wiedergabefortschritt je Film/Folge ([Keys]) und je Profil. [all] ist immer der des aktiven
 * Profils – Fortsetzen, „Weiterschauen“, „Nächste Folge“ sehen nur das.
 */
@OptIn(FlowPreview::class)
class ProgressStore(context: Context, private val activeId: StateFlow<String>) {
    private val file = File(context.filesDir, "progress.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _byProfile = MutableStateFlow(load())
    /** Alle Profile – für den Abgleich mit dem Konto. */
    val byProfile: StateFlow<Map<String, Map<String, Progress>>> = _byProfile.asStateFlow()

    private val _all = MutableStateFlow(_byProfile.value[activeId.value].orEmpty())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()

    init {
        scope.launch { _byProfile.drop(1).debounce(500).collect { save(it) } }
        // Synchron nachziehen statt über stateIn: MainActivity liest all.value direkt nach einem Wechsel.
        scope.launch(Dispatchers.Main.immediate) { activeId.collect { publish() } }
    }

    private fun publish() { _all.value = _byProfile.value[activeId.value].orEmpty() }

    private fun change(pid: String = activeId.value, f: (Map<String, Progress>) -> Map<String, Progress>) {
        _byProfile.update { m -> m + (pid to f(m[pid].orEmpty())) }
        publish()
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
        change { it + (key to Progress(positionMs, durationMs, finished, System.currentTimeMillis())) }
    }

    /**
     * „Nicht gesehen“ setzt auf Anfang statt den Eintrag zu löschen: Ein gelöschter Eintrag käme
     * beim Abgleich vom anderen Gerät zurück, ein neuerer Eintrag „auf Anfang“ gewinnt dagegen.
     */
    fun setWatched(key: String, watched: Boolean) = change { map ->
        val d = map[key]?.durationMs ?: 0
        map + (key to if (watched) Progress(d, d, true, System.currentTimeMillis()) else Progress(0, d, false, System.currentTimeMillis()))
    }

    /** Titel aus der Bibliothek entfernt: sein Fortschritt in allen Profilen mit. */
    fun removeTitle(id: String) {
        _byProfile.update { m -> m.mapValues { (_, map) -> map.filterKeys { Keys.parse(it)?.first != id } } }
        publish()
    }

    /** Profil gelöscht. */
    fun removeProfile(pid: String) {
        _byProfile.update { it - pid }
        publish()
    }

    // --- Sync ---

    /** Einträge vom Server einmischen: je Folge gewinnt der neuere Stand. */
    fun mergeRemote(pid: String, remote: Map<String, Progress>) = change(pid) { local -> ProgressMerge.merge(local, remote) }

    /** Zwei Doppel desselben Titels wurden zusammengeführt: Fortschritt zieht zur neuen ID um. */
    fun rekey(from: String, to: String) {
        _byProfile.update { m ->
            m.mapValues { (_, map) ->
                val moved = map.mapNotNull { (k, p) -> de.marne.streamplayer.account.SyncCodec.rekey(k, from, to)?.let { it to p } }.toMap()
                ProgressMerge.merge(map.filterKeys { Keys.parse(it)?.first != from }, moved)
            }
        }
        publish()
    }

    /** Vom Server: Fortschritt eines Titels gelöscht. */
    fun removeRemote(pid: String, titleId: String) = change(pid) { map -> map.filterKeys { Keys.parse(it)?.first != titleId } }

    private fun load(): Map<String, Map<String, Progress>> = runCatching {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        // Bis 1.2: ein flaches Objekt ohne Profile – gehört dem ersten Profil.
        if (!o.has("profiles")) return mapOf(de.marne.streamplayer.account.Profile.DEFAULT_ID to ProgressMerge.fromJson(o))
        val ps = o.getJSONObject("profiles")
        ps.keys().asSequence().associateWith { ProgressMerge.fromJson(ps.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    private fun save(all: Map<String, Map<String, Progress>>) {
        val ps = JSONObject()
        all.forEach { (pid, map) -> ps.put(pid, ProgressMerge.toJson(map)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("v", 2).put("profiles", ps).toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MIN_RESUME_MS = 30_000L
    }
}

/** Reine Funktionen rund um Fortschritt – testbar ohne Android. */
object ProgressMerge {
    /** Je Schlüssel gewinnt der neuere Stand; Einträge, die nur eine Seite kennt, bleiben. */
    fun merge(local: Map<String, Progress>, remote: Map<String, Progress>): Map<String, Progress> {
        val out = local.toMutableMap()
        remote.forEach { (k, r) -> val l = out[k]; if (l == null || r.updatedAt > l.updatedAt) out[k] = r }
        return out
    }

    /** Nach Titel gruppiert: ein Dokument je Titel und Profil statt eines je Folge. */
    fun byTitle(map: Map<String, Progress>): Map<String, Map<String, Progress>> =
        map.entries.groupBy({ Keys.parse(it.key)?.first ?: "_" }, { it.key to it.value }).mapValues { it.value.toMap() }

    fun toJson(map: Map<String, Progress>): JSONObject = JSONObject().apply {
        // Sortiert, damit derselbe Stand immer denselben Text ergibt (Änderungserkennung über Hash).
        map.toSortedMap().forEach { (k, p) -> put(k, JSONObject().put("p", p.positionMs).put("d", p.durationMs).put("f", p.finished).put("u", p.updatedAt)) }
    }

    fun fromJson(o: JSONObject): Map<String, Progress> = o.keys().asSequence().associateWith { k ->
        val p = o.getJSONObject(k)
        Progress(p.optLong("p"), p.optLong("d"), p.optBoolean("f"), p.optLong("u"))
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
