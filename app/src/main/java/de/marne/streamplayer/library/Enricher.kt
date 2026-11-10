package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
) {
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val lastError: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        scope.launch {
            for ((id, force) in queue) {
                runCatching { enrich(id, force) }.onFailure { e -> _status.value = _status.value.copy(lastError = e.message) }
                val s = _status.value
                _status.value = s.copy(done = s.done + 1, running = s.done + 1 < s.total)
            }
        }
    }

    val available: Boolean get() = tmdb.configured

    /**
     * Was TMDB inzwischen mehr liefert: Genres & Co. (seit 1.7.0) und ein Ersatz-Cover auf Englisch
     * oder ohne Sprache, wenn es in der Wunschsprache keins gab (seit 1.8.0).
     */
    private fun needsUpgrade(t: LibTitle): Boolean {
        val tm = t.tmdb ?: return false
        return tm.genres == null || (tm.poster == null && t.poster == null && t.customPoster == null)
    }

    /** Titel mit Lücken (siehe [needsUpgrade]) einmal nachladen. Beim Start aufgerufen. */
    fun upgradeOld() {
        enqueue(store.titles.value.filter { needsUpgrade(it) }.map { it.id })
    }

    fun enqueue(ids: List<String>, force: Boolean = false) {
        if (!tmdb.configured || ids.isEmpty()) return
        val s = _status.value
        _status.value = if (s.running) s.copy(total = s.total + ids.size) else Status(true, 0, ids.size)
        ids.forEach { queue.trySend(it to force) }
    }

    private suspend fun enrich(id: String, force: Boolean) {
        val t = store.get(id) ?: return
        // Schon ergänzt – außer es fehlt etwas, das TMDB inzwischen liefern kann: dann einmal nachholen.
        if (t.tmdb != null && !needsUpgrade(t) && !force) return
        val kind = if (t.kind == TitleKind.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
        val tmdbId = t.tmdbId ?: run {
            if (!settings.tmdbAuto.value && t.tmdbQuery == null) return
            tmdb.search(t.tmdbQuery ?: t.title, kind, t.year).firstOrNull()?.id
        } ?: return

        val d = tmdb.details(kind, tmdbId)
        val r = d.result
        val seasons = d.seasons.filter { it.number > 0 && it.episodeCount > 0 }.map { SeasonSpec(it.number, it.episodeCount) }
        val upgradeOnly = t.tmdb != null && needsUpgrade(t) && !force
        store.applyTmdb(
            id,
            TmdbInfo(
                r.id, r.title, r.poster, r.backdrop, r.overview, r.year, d.runtimeMin,
                genres = d.genres, rating = d.rating, votes = d.votes, tagline = d.tagline, cast = d.cast, creators = d.creators,
            ),
            seasons,
        )

        // Nachholen braucht die Folgen nicht noch einmal: die sind schon da.
        if (kind == Tmdb.Kind.TV && !upgradeOnly) {
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
}
