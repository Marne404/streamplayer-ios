package de.marne.streamplayer.creator

import de.marne.streamplayer.catalog.Catalog
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.IdentityHashMap

/**
 * Was die Katalogliste zeigt: gefiltert, sortiert, in Buchstaben gegliedert und gezählt. Wird im
 * Hintergrund berechnet – bei Tausenden Titeln darf das weder Tastatur noch Liste aufhalten.
 */
class CatalogView(
    val series: Int,
    val movies: Int,
    /** Titel der gewählten Art (vor Suche und Filter). */
    val total: Int,
    val unlinked: Int,
    val siteInfo: Int,
    val rows: List<Row>,
    /** Buchstabe → Index seiner Kopfzeile in [rows] (nur bei A–Z). */
    val letters: List<Pair<String, Int>>,
) {
    enum class Filter { ALL, NO_TMDB, SITE }
    enum class Sort { AZ, NEW }

    sealed interface Row {
        val key: String

        class Head(val label: String) : Row {
            override val key get() = "h:$label"
        }

        /** [first]/[last]: Anfang und Ende eines Abschnitts – dort rundet die Karte ab. */
        class Item(val title: CatalogTitle, override val key: String, val summary: String, val unlinked: Boolean, val first: Boolean, val last: Boolean) : Row
    }

    companion object {
        val EMPTY = CatalogView(0, 0, 0, 0, 0, emptyList(), emptyList())

        /** Aufbereitete Titel aus dem letzten Durchgang: Unveränderte Titel sind dasselbe Objekt. */
        private var folded = IdentityHashMap<CatalogTitle, String>()

        @Synchronized
        fun of(c: Catalog, kind: CatalogType, filter: Filter, sort: Sort, query: String): CatalogView {
            val next = IdentityHashMap<CatalogTitle, String>(c.titles.size)
            for (t in c.titles) next[t] = folded[t] ?: fold(t.title)
            folded = next

            val ofKind = c.titles.filter { it.type == kind }
            // Wortanfänge: „bad“ findet „Breaking Bad“, „12“ aber nicht „Folge 512“.
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            val shown = ofKind.filter { t ->
                when (filter) {
                    Filter.NO_TMDB -> needsTmdb(t)
                    Filter.SITE -> hasSiteInfo(t)
                    Filter.ALL -> true
                } && (words.isEmpty() || (" " + next.getValue(t)).let { f -> words.all { w -> " $w" in f || w == t.year?.toString() } })
            }
            val rows = ArrayList<Row>(shown.size + 30)
            val letters = ArrayList<Pair<String, Int>>()
            // Schlüssel müssen eindeutig sein – auch wenn eine alte Datei eine ID doppelt enthält.
            val seen = HashSet<String>()
            fun key(t: CatalogTitle): String {
                var k = "t:" + t.id
                var n = 2
                while (!seen.add(k)) k = "t:" + t.id + "#" + n++
                return k
            }
            // Das Schild „No TMDB“ nur, wenn es etwas unterscheidet – nicht an jeder Zeile, wenn alle so sind.
            val unlinkedCount = ofKind.count(::needsTmdb)
            val mark = filter != Filter.NO_TMDB && unlinkedCount in 1 until ofKind.size
            fun section(items: List<CatalogTitle>) {
                items.forEachIndexed { i, t ->
                    rows += Row.Item(t, key(t), summary(t), mark && needsTmdb(t), first = i == 0, last = i == items.lastIndex)
                }
            }
            if (sort == Sort.AZ) {
                // Alles ohne Buchstaben vorn unter „#“ (Ziffern, andere Schriften), dann A–Z.
                val sorted = shown.sortedWith(compareBy<CatalogTitle> { letterOf(next.getValue(it)) != "#" }.thenBy { next.getValue(it) }.thenBy { it.year ?: 0 })
                var start = 0
                while (start < sorted.size) {
                    val l = letterOf(next.getValue(sorted[start]))
                    var end = start
                    while (end < sorted.size && letterOf(next.getValue(sorted[end])) == l) end++
                    letters += l to rows.size
                    rows += Row.Head(l)
                    section(sorted.subList(start, end))
                    start = end
                }
            } else {
                // Neu Übernommenes steht im Katalog vorn.
                section(shown)
            }
            return CatalogView(
                series = c.titles.count { it.type == CatalogType.SERIES },
                movies = c.titles.count { it.type == CatalogType.MOVIE },
                total = ofKind.size,
                unlinked = unlinkedCount,
                siteInfo = ofKind.count(::hasSiteInfo),
                rows = rows,
                letters = letters,
            )
        }

        private fun letterOf(folded: String): String {
            val c = folded.firstOrNull { it != ' ' } ?: return "#"
            return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
        }

        /** Klein, ohne Akzente („Amélie“ → „amelie“), Satzzeichen werden Leerzeichen. */
        fun fold(s: String): String {
            val src = if (s.any { it.code >= 128 }) Normalizer.normalize(s, Normalizer.Form.NFD) else s
            val out = StringBuilder(src.length)
            for (ch in src) {
                val c = ch.lowercaseChar()
                when {
                    c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                    Character.isLetterOrDigit(c) -> out.append(c)
                    else -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                }
            }
            return out.toString().trim()
        }
    }
}

/**
 * TMDB für viele Titel auf einmal: Cover & Text nachziehen ([Mode.COVERS]) oder Titel ohne TMDB
 * automatisch zuordnen ([Mode.MATCH]). Läuft in der App, nicht im Bildschirm (früher brach es ab,
 * sobald man einen Titel öffnete), und hält sie über den Dienst der Linkliste am Leben. Was
 * fertig ist, steht sofort im Katalog – abgebrochen, noch einmal tippen: weiter mit dem Rest.
 */
class TmdbFill(private val app: CreatorApp) {
    enum class Mode { COVERS, MATCH }

    data class State(val mode: Mode, val done: Int, val total: Int, val found: Int)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(ids: List<String>, mode: Mode) {
        if (running || ids.isEmpty()) return
        _state.value = State(mode, 0, ids.size, 0)
        keyProblem = null
        if (!app.imports.bulk.running) BulkService.start(app)
        job = scope.launch {
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val found = java.util.concurrent.atomic.AtomicInteger(0)
            fun publish() {
                _state.value = State(mode, done.get(), ids.size, found.get())
                BulkService.fillProgress(app, _state.value)
            }
            try {
                // Drei gleichzeitig: 11 000 Titel einzeln wären über eine Stunde; TMDB erlaubt deutlich mehr.
                List(3) {
                    launch {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= ids.size) break
                            if (one(ids[i], mode)) found.incrementAndGet()
                            done.incrementAndGet()
                            publish()
                            delay(120) // TMDB nicht hetzen
                        }
                    }
                }.forEach { it.join() }
            } finally {
                val n = done.get()
                val f = found.get()
                _state.value = null
                if (!app.imports.bulk.running) BulkService.stop(app)
                // Über die App melden: Der Bildschirm, der es gestartet hat, ist vielleicht längst zu.
                app.imports.notice.value = when {
                    keyProblem != null -> "Stopped: ${keyProblem}. Check the TMDB key in Settings."
                    mode == Mode.MATCH && n < ids.size -> "Stopped – %,d of %,d checked, %,d matched. Tap again for the rest.".format(n, ids.size, f)
                    mode == Mode.MATCH -> "%,d of %,d matched on TMDB. The rest: pick by hand.".format(f, ids.size)
                    n < ids.size -> "Stopped – %,d of %,d updated.".format(f, ids.size)
                    f == ids.size -> "%,d titles updated.".format(f)
                    else -> "%,d of %,d updated – TMDB didn't answer for the rest.".format(f, ids.size)
                }
            }
        }
    }

    /** Ein Titel. Rückgabe: geändert (zugeordnet bzw. aktualisiert). */
    private suspend fun one(id: String, mode: Mode): Boolean {
        val t = app.store.get(id) ?: return false
        val kind = if (t.type == CatalogType.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
        return when (mode) {
            Mode.COVERS -> {
                val tid = t.tmdb?.id ?: return false
                val d = patiently { app.tmdb.details(kind, tid) } ?: return false
                // Neu aus dem Speicher holen: der Titel kann inzwischen bearbeitet sein.
                app.store.get(id)?.let { cur -> app.store.put(fromTmdb(cur, d.result)); true } ?: false
            }
            Mode.MATCH -> {
                if (t.tmdb?.id != null || t.title.isBlank()) return false
                // Derselbe Suchbegriff wie im Editor (eigener Suchbegriff vor dem Namen).
                val query = t.tmdb?.query ?: t.title
                val results = patiently {
                    app.tmdb.search(query, kind, t.year).ifEmpty { if (t.year != null) app.tmdb.search(query, kind) else emptyList() }
                } ?: return false
                val r = pickMatch(query, t.year, results) ?: return false
                app.store.get(id)?.takeIf { it.tmdb?.id == null }?.let { cur ->
                    app.store.put(fromTmdb(cur.copy(tmdb = de.marne.streamplayer.catalog.TmdbRef(r.id), year = cur.year ?: r.year), r))
                    true
                } ?: false
            }
        }
    }

    /**
     * Mit Geduld: Zu viele Anfragen (429), Server- oder Netzfehler sind kein „nicht gefunden“ –
     * früher zählte jeder solche Aussetzer als nicht erkannt. Abgelehnter Schlüssel: alles anhalten.
     */
    private suspend fun <T> patiently(block: suspend () -> T): T? {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Tmdb.TmdbException) {
                when (e.code) {
                    0, 401 -> { keyProblem = e.message; job?.cancel(); return null }
                    404 -> return null
                }
                if (++attempt >= 5) return null
                delay(2_000L * attempt)
            } catch (e: java.io.IOException) {
                if (++attempt >= 8) return null
                delay(2_000L * attempt)
            } catch (e: Exception) {
                return null
            }
        }
    }

    /** Warum angehalten wurde, wenn TMDB den Schlüssel ablehnt. */
    @Volatile private var keyProblem: String? = null

    fun stop() {
        job?.cancel()
    }

    companion object {
        /**
         * Sicher genug für eine Zuordnung ohne Nachfrage?
         * - Gleicher Name (ohne Akzente und Satzzeichen) wie der TMDB-Titel oder dessen Originaltitel,
         *   Jahr passt (±1) oder ist unbekannt.
         * - Oder TMDB findet genau einen Titel und das Jahr widerspricht nicht. Anlass: Mehr als 4 000
         *   Serien blieben ohne Zuordnung, obwohl die Suche im Editor genau einen – richtigen – Treffer
         *   zeigte; Titel aus Linklisten haben meist kein Jahr, und das war hier Pflicht.
         * - Oder unter mehreren Treffern enthält genau einer den Namen (bzw. umgekehrt), Jahr passt.
         * Alles andere bleibt unter „No TMDB“ zum Aussuchen.
         */
        fun pickMatch(title: String, year: Int?, results: List<Tmdb.Result>): Tmdb.Result? {
            val want = CatalogView.fold(title)
            if (want.isEmpty()) return null
            fun yearOk(r: Tmdb.Result) = year == null || r.year == null || kotlin.math.abs(r.year!! - year) <= 1
            val top = results.take(8)
            top.firstOrNull { r ->
                yearOk(r) && (CatalogView.fold(r.title) == want || r.originalTitle?.let(CatalogView::fold) == want)
            }?.let { return it }
            results.singleOrNull()?.let { return it.takeIf(::yearOk) }
            fun near(a: String) = want.length >= 4 && a.length >= 4 && (a.contains(want) || want.contains(a))
            return top.filter { r -> yearOk(r) && (near(CatalogView.fold(r.title)) || r.originalTitle?.let { near(CatalogView.fold(it)) } == true) }.singleOrNull()
        }
    }
}
