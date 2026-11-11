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

    /**
     * Sehzeit je Tag (Epochentag → ms) und Profil – für „diese Woche“. Ein Zähler je Folge reicht
     * dafür nicht: Er wüsste nicht, wann die Zeit angefallen ist. Nur auf dem Gerät, 60 Tage.
     */
    private val _daysByProfile = MutableStateFlow(loadDays())
    val daysByProfile: StateFlow<Map<String, Map<Long, Long>>> = _daysByProfile.asStateFlow()
    private val _days = MutableStateFlow(_daysByProfile.value[activeId.value].orEmpty())
    val days: StateFlow<Map<Long, Long>> = _days.asStateFlow()

    init {
        scope.launch { _byProfile.drop(1).debounce(500).collect { save(it) } }
        scope.launch { _daysByProfile.drop(1).debounce(500).collect { save(_byProfile.value) } }
        // Synchron nachziehen statt über stateIn: MainActivity liest all.value direkt nach einem Wechsel.
        scope.launch(Dispatchers.Main.immediate) { activeId.collect { publish() } }
    }

    private fun publish() {
        _all.value = _byProfile.value[activeId.value].orEmpty()
        _days.value = _daysByProfile.value[activeId.value].orEmpty()
    }

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

    /** [playedMs]: seit der letzten Meldung wirklich abgespielte Zeit (siehe [Progress.watchedMs]). */
    fun report(key: String, positionMs: Long, durationMs: Long, ended: Boolean = false, playedMs: Long = 0) {
        if (durationMs <= 0 && !ended) return
        // Abspann zählt als gesehen: weniger als 2 Minuten oder 5 % übrig.
        val finished = ended || (durationMs > 0 && durationMs - positionMs < maxOf(120_000L, durationMs / 20))
        val played = playedMs.coerceAtLeast(0)
        change {
            val before = it[key]?.countedMs ?: 0
            it + (key to Progress(positionMs, durationMs, finished, System.currentTimeMillis(), before + played))
        }
        if (played > 0) {
            val pid = activeId.value
            val today = java.time.LocalDate.now().toEpochDay()
            _daysByProfile.update { m ->
                val days = m[pid].orEmpty()
                m + (pid to (days + (today to (days[today] ?: 0) + played)).filterKeys { it > today - 60 })
            }
            publish()
        }
    }

    /**
     * „Nicht gesehen“ setzt auf Anfang statt den Eintrag zu löschen: Ein gelöschter Eintrag käme
     * beim Abgleich vom anderen Gerät zurück, ein neuerer Eintrag „auf Anfang“ gewinnt dagegen.
     */
    fun setWatched(key: String, watched: Boolean) = change { map ->
        val d = map[key]?.durationMs ?: 0
        // Markieren ist kein Schauen: die gezählte Sehzeit bleibt, wie sie war.
        val w = map[key]?.countedMs ?: 0
        map + (key to if (watched) Progress(d, d, true, System.currentTimeMillis(), w) else Progress(0, d, false, System.currentTimeMillis(), w))
    }

    /** Titel aus der Bibliothek entfernt: sein Fortschritt in allen Profilen mit. */
    fun removeTitle(id: String) = removeTitles(setOf(id))

    /** Mehrere auf einmal (Bibliothek geleert): ein Durchgang statt einer je Titel. */
    fun removeTitles(ids: Set<String>) {
        if (ids.isEmpty()) return
        _byProfile.update { m -> m.mapValues { (_, map) -> map.filterKeys { Keys.parse(it)?.first !in ids } } }
        publish()
    }

    /** Profil gelöscht. */
    fun removeProfile(pid: String) {
        _byProfile.update { it - pid }
        _daysByProfile.update { it - pid }
        publish()
    }

    /**
     * Sicherung einspielen, Profile und Titel schon auf die hiesigen IDs umgeschrieben. Ersetzen:
     * der Verlauf ist danach der der Datei. Zusammenführen: je Folge der neuere Stand, Sehzeit je Tag
     * die größere.
     */
    fun importBackup(progress: Map<String, Map<String, Progress>>, days: Map<String, Map<Long, Long>>, replace: Boolean) {
        _byProfile.update { m -> if (replace) progress else m + progress.mapValues { (pid, p) -> ProgressMerge.merge(m[pid].orEmpty(), p) } }
        _daysByProfile.update { m ->
            if (replace) days
            else m + days.mapValues { (pid, d) -> (m[pid].orEmpty().keys + d.keys).associateWith { maxOf(m[pid]?.get(it) ?: 0, d[it] ?: 0) } }
        }
        publish()
    }

    // --- Sync ---

    /** Einträge vom Server einmischen: je Folge gewinnt der neuere Stand. */
    fun mergeRemote(pid: String, remote: Map<String, Progress>) = change(pid) { local -> ProgressMerge.merge(local, remote) }

    /** Zwei Doppel desselben Titels wurden zusammengeführt: Fortschritt zieht zur neuen ID um. */
    /** Folge eines Titels wird zum Film (Ein-Folgen-„Serie“): ihr Fortschritt zieht auf den Film-Schlüssel um. */
    fun episodeToMovie(id: String) {
        _byProfile.update { m ->
            m.mapValues { (_, map) ->
                val ep = map.filterKeys { k -> Keys.parse(k)?.let { it.first == id && it.second != null } == true }
                if (ep.isEmpty()) map
                else (map - ep.keys) + ("m:$id" to ep.values.maxBy { it.updatedAt })
            }
        }
        publish()
    }

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

    private fun loadDays(): Map<String, Map<Long, Long>> = runCatching {
        if (!file.exists()) return emptyMap()
        val w = JSONObject(file.readText()).optJSONObject("watch") ?: return emptyMap()
        w.keys().asSequence().associateWith { pid -> ProgressMerge.daysFromJson(w.getJSONObject(pid)) }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save(all: Map<String, Map<String, Progress>>) {
        val ps = JSONObject()
        all.forEach { (pid, map) -> ps.put(pid, ProgressMerge.toJson(map)) }
        val w = JSONObject()
        _daysByProfile.value.forEach { (pid, days) -> w.put(pid, ProgressMerge.daysToJson(days)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("v", 2).put("profiles", ps).put("watch", w).toString())
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
        remote.forEach { (k, r) ->
            val l = out[k]
            // Die Position kommt vom neueren Stand, die Sehzeit vom größeren: Sie wächst nur, und
            // ein älteres Gerät mit mehr gezählter Zeit hat sie wirklich gesehen.
            out[k] = when {
                l == null -> r
                r.updatedAt > l.updatedAt -> r.copy(watchedMs = maxOf(r.watchedMs, l.watchedMs))
                else -> l.copy(watchedMs = maxOf(r.watchedMs, l.watchedMs))
            }
        }
        return out
    }

    fun daysToJson(days: Map<Long, Long>): JSONObject = JSONObject().apply { days.toSortedMap().forEach { (d, ms) -> put(d.toString(), ms) } }

    fun daysFromJson(o: JSONObject): Map<Long, Long> =
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.optLong(k) } }.toMap()

    /** Nach Titel gruppiert: ein Dokument je Titel und Profil statt eines je Folge. */
    fun byTitle(map: Map<String, Progress>): Map<String, Map<String, Progress>> =
        map.entries.groupBy({ Keys.parse(it.key)?.first ?: "_" }, { it.key to it.value }).mapValues { it.value.toMap() }

    fun toJson(map: Map<String, Progress>): JSONObject = JSONObject().apply {
        // Sortiert, damit derselbe Stand immer denselben Text ergibt (Änderungserkennung über Hash).
        map.toSortedMap().forEach { (k, p) ->
            put(k, JSONObject().put("p", p.positionMs).put("d", p.durationMs).put("f", p.finished).put("u", p.updatedAt).apply { if (p.watchedMs >= 0) put("w", p.watchedMs) })
        }
    }

    fun fromJson(o: JSONObject): Map<String, Progress> = o.keys().asSequence().associateWith { k ->
        val p = o.getJSONObject(k)
        Progress(p.optLong("p"), p.optLong("d"), p.optBoolean("f"), p.optLong("u"), p.optLong("w", -1))
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
