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
            fun section(items: List<CatalogTitle>) {
                items.forEachIndexed { i, t ->
                    rows += Row.Item(t, key(t), summary(t), needsTmdb(t), first = i == 0, last = i == items.lastIndex)
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
                unlinked = ofKind.count(::needsTmdb),
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
 * „Cover & Text von TMDB“ für viele Titel. Läuft in der App, nicht im Bildschirm: Bei Tausenden
 * Titeln dauert das lange, und früher brach es ab, sobald man einen Titel zum Bearbeiten öffnete.
 */
class TmdbFill(private val app: CreatorApp) {
    data class State(val done: Int, val total: Int)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start(ids: List<String>) {
        if (job?.isActive == true || ids.isEmpty()) return
        _state.value = State(0, ids.size)
        job = scope.launch {
            var ok = 0
            var tried = 0
            try {
                for (id in ids) {
                    val t = app.store.get(id)
                    val tid = t?.tmdb?.id
                    if (t != null && tid != null) {
                        val kind = if (t.type == CatalogType.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
                        runCatching { app.tmdb.details(kind, tid) }.getOrNull()?.let { d ->
                            // Neu aus dem Speicher holen: der Titel kann inzwischen bearbeitet sein.
                            app.store.get(id)?.let { cur -> app.store.put(fromTmdb(cur, d.result)); ok++ }
                        }
                        delay(250) // TMDB nicht hetzen
                    }
                    tried++
                    _state.value = State(tried, ids.size)
                }
            } finally {
                _state.value = null
                val msg = when {
                    tried < ids.size -> "Stopped – %,d of %,d updated.".format(ok, ids.size)
                    ok == ids.size -> "%,d titles updated.".format(ok)
                    else -> "%,d of %,d updated – TMDB didn't answer for the rest.".format(ok, ids.size)
                }
                // Über die App melden: Der Bildschirm, der es gestartet hat, ist vielleicht längst zu.
                app.imports.notice.value = msg
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
