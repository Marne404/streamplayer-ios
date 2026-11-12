package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ergänzt Titel über TMDB: Poster, Hintergrund, Beschreibung, Jahr, Laufzeit; bei Serien
 * fehlende Staffeln samt Folgenzahl und je Folge Name, Bild, Inhalt.
 *
 * Katalog-Angaben gewinnen immer – TMDB füllt nur, was fehlt (siehe [LibTitle.cover] usw.).
 * Ohne feste TMDB-ID wird nur gesucht, wenn „automatisch erkennen“ an ist.
 */
class Enricher(
    private val tmdb: Tmdb,
    private val store: LibraryStore,
    private val settings: Settings,
    dir: File,
) {
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val lastError: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Was noch dran ist (ID → erzwingen), in Reihenfolge – und auf der Platte. Anlass: Die Schlange
     * lag nur im Speicher. Wurde die App während eines großen Imports geschlossen, blieben alle
     * restlichen Titel für immer ohne TMDB-Daten; die Detailseite meldete „Bei TMDB nicht gefunden“,
     * obwohl der Katalog die TMDB-ID mitbrachte (Cover von TMDB war ja schon da).
     */
    private val pending = LinkedHashMap<String, Boolean>()
    private val inFlight = HashSet<String>()
    private val file = File(dir, "enrich-pending.txt")
    private var done = 0
    private var lastError: String? = null
    /** Kein oder abgelehnter Schlüssel: anhalten, bis wieder etwas eingereiht wird (neuer Schlüssel, Neustart). */
    @Volatile private var halted = false
    /** Weckt wartende Arbeiter (jede Änderung der Schlange zählt hoch). */
    private val wake = MutableStateFlow(0L)
    private val saveLock = Mutex()
    @Volatile private var dirtySince = 0L
    /** Folgen schon einmal versucht (diese Sitzung) – nicht bei jedem Öffnen der Detailseite erneut. */
    private val episodesTried = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        load()
        publish()
        // Mehrere Arbeiter: Tausende Titel einzeln nacheinander dauerten Stunden. Vier bleiben weit
        // unter dem, was TMDB erlaubt.
        repeat(WORKERS) { scope.launch { work() } }
    }

    val available: Boolean get() = tmdb.configured

    /** Wie viele Titel noch auf TMDB warten. */
    val pendingCount: Int get() = synchronized(pending) { pending.size }

    /**
     * Was TMDB inzwischen mehr liefert: Genres & Co. (seit 1.7.0) und ein Ersatz-Cover auf Englisch
     * oder ohne Sprache, wenn es in der Wunschsprache keins gab (seit 1.8.0).
     */
    private fun needsUpgrade(t: LibTitle): Boolean {
        val tm = t.tmdb ?: return false
        return tm.genres == null || (tm.poster == null && t.poster == null && t.customPoster == null)
    }

    /**
     * Beim Start: Titel mit Lücken (siehe [needsUpgrade]) und Titel, deren TMDB-ID feststeht, deren
     * Daten aber nie geladen wurden (abgebrochener Abgleich älterer Versionen), einmal nachladen.
     */
    fun upgradeOld() {
        enqueue(store.titles.value.filter { needsUpgrade(it) || (it.tmdb == null && it.tmdbId != null) }.map { it.id })
    }

    fun enqueue(ids: List<String>, force: Boolean = false) {
        if (!tmdb.configured || ids.isEmpty()) return
        synchronized(pending) {
            ids.forEach { id -> pending[id] = force || pending[id] == true }
        }
        halted = false
        changed(saveNow = true)
    }

    /**
     * Sofort, an der Schlange vorbei – für die gerade geöffnete Detailseite. Holt auch die Folgen,
     * die der Abgleich großer Bibliotheken auslässt (s. [episodesInQueue]).
     */
    fun prioritize(id: String) {
        if (!tmdb.configured) return
        val t = store.get(id) ?: return
        val needsInfo = t.tmdb == null && (t.tmdbId != null || t.tmdbQuery != null || settings.tmdbAuto.value)
        val needsEpisodes = t.kind == TitleKind.SERIES && t.episodeInfo.isEmpty() && id !in episodesTried
        if (!needsInfo && !needsEpisodes) return
        scope.launch {
            val ok = runCatching { enrich(id, force = false, episodes = true) }.isSuccess
            if (ok) synchronized(pending) { if (id !in inFlight) pending.remove(id) }
            changed()
        }
    }

    // ---------- Arbeit ----------

    private suspend fun work() {
        while (true) {
            val seen = wake.value
            val next = if (halted) null else synchronized(pending) {
                pending.entries.firstOrNull { it.key !in inFlight }?.also { inFlight += it.key }?.let { it.key to it.value }
            }
            if (next == null) {
                wake.first { it != seen }
                continue
            }
            val (id, force) = next
            val keep = process(id, force)
            synchronized(pending) {
                inFlight -= id
                if (!keep) { pending.remove(id); done++ }
                if (pending.isEmpty()) done = 0
            }
            changed()
        }
    }

    /** Ein Titel, mit Geduld bei Netz- und Serverfehlern. Rückgabe: in der Schlange lassen (angehalten). */
    private suspend fun process(id: String, force: Boolean): Boolean {
        var attempt = 0
        while (true) {
            try {
                enrich(id, force, episodes = force || episodesInQueue())
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Tmdb.TmdbException) {
                lastError = e.message
                when (e.code) {
                    // Kein oder abgelehnter Schlüssel: alles bleibt liegen, bis es weitergehen kann.
                    0, 401 -> { halted = true; return true }
                    404 -> return false
                    // Zu viele Anfragen, Server-Fehler: warten und noch einmal, irgendwann aufgeben.
                    else -> if (++attempt >= 5) return false else delay(backoff(attempt))
                }
            } catch (e: IOException) {
                // Netz weg: warten statt aufgeben – sonst liefe die Schlange ohne Netz in Sekunden leer.
                lastError = e.message
                publish()
                delay(backoff(++attempt))
            } catch (e: Exception) {
                lastError = e.message
                return false
            }
        }
    }

    private fun backoff(attempt: Int) = (2_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(60_000L)

    /**
     * Folgen (Name, Bild, Inhalt je Folge) beim Abgleich mitholen? Bei sehr großen Bibliotheken nicht:
     * Das wären je Serie mehrere Anfragen und für Tausende Serien Hunderte MB im Speicher. Dann
     * kommen sie, sobald man die Serie öffnet ([prioritize]).
     */
    private fun episodesInQueue() = store.titles.value.size <= BIG_LIBRARY

    private suspend fun enrich(id: String, force: Boolean, episodes: Boolean) {
        val t = store.get(id) ?: return
        val kind = if (t.kind == TitleKind.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
        // Schon ergänzt – außer es fehlt etwas, das TMDB inzwischen liefern kann: dann einmal nachholen.
        val needInfo = t.tmdb == null || needsUpgrade(t) || force
        val needEpisodes = episodes && kind == Tmdb.Kind.TV && (t.episodeInfo.isEmpty() || force)
        if (!needInfo && !needEpisodes) return
        val tmdbId = t.tmdbId ?: t.tmdb?.id ?: run {
            if (!settings.tmdbAuto.value && t.tmdbQuery == null) return
            tmdb.search(t.tmdbQuery ?: t.title, kind, t.year).firstOrNull()?.id
        } ?: return

        if (needInfo) {
            val d = tmdb.details(kind, tmdbId)
            val r = d.result
            val seasons = d.seasons.filter { it.number > 0 && it.episodeCount > 0 }.map { SeasonSpec(it.number, it.episodeCount) }
            store.applyTmdb(
                id,
                TmdbInfo(
                    r.id, r.title, r.poster, r.backdrop, r.overview, r.year, d.runtimeMin,
                    genres = d.genres, rating = d.rating, votes = d.votes, tagline = d.tagline, cast = d.cast, creators = d.creators,
                ),
                seasons,
            )
        }

        // Nur Lücken nachholen braucht die Folgen nicht noch einmal: die sind schon da.
        if (needEpisodes) {
            episodesTried += id
            val current = store.get(id) ?: return
            val info = mutableMapOf<String, EpInfo>()
            for (season in current.seasons) {
                val eps = runCatching { tmdb.season(tmdbId, season.number) }.getOrNull() ?: continue
                // TMDB zählt je Staffel ab 1; die Seite vielleicht ab „start“ weiter. Zuordnung über die Position.
                eps.sortedBy { it.episode }.forEachIndexed { i, e ->
                    if (i < season.episodes) info["${season.number}:${season.start + i}"] = EpInfo(e.name, e.overview, e.still, e.runtimeMin)
                }
            }
            if (info.isNotEmpty()) store.setEpisodeInfo(id, info)
        }
    }

    // ---------- Stand ----------

    private fun publish() {
        val (n, d) = synchronized(pending) { pending.size to done }
        _status.value = Status(running = n > 0 && !halted, done = d, total = d + n, lastError = lastError)
    }

    private fun changed(saveNow: Boolean = false) {
        wake.update { it + 1 }
        publish()
        val now = System.currentTimeMillis()
        if (dirtySince == 0L) dirtySince = now
        // Auf die Platte: beim Einreihen sofort, beim Abarbeiten gesammelt alle paar Sekunden.
        if (saveNow || now - dirtySince > 5_000 || pendingCount == 0) {
            dirtySince = 0L
            scope.launch { save() }
        }
    }

    private suspend fun save() = saveLock.withLock {
        val lines = synchronized(pending) { pending.map { (id, force) -> if (force) "!$id" else id } }
        runCatching {
            if (lines.isEmpty()) { file.delete(); return@runCatching }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } }
            tmp.renameTo(file)
        }
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        file.forEachLine { l ->
            val line = l.trim()
            if (line.isNotEmpty()) pending[line.removePrefix("!")] = line.startsWith("!")
        }
    }

    private companion object {
        const val WORKERS = 4
        const val BIG_LIBRARY = 3_000
    }
}
