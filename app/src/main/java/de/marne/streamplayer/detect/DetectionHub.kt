package de.marne.streamplayer.detect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sammelstelle für alles, was auf der aktuellen Seite nach Video aussieht.
 *
 * Zwei Ebenen melden hierher: der WebViewClient (jede Netzwerk-Anfrage, aus einem
 * Hintergrund-Thread) und das eingeschleuste Skript (DOM, play-Events, XHR/fetch,
 * aus dem Hauptthread). Deshalb ist alles hinter einem Lock.
 */
class DetectionHub(private val scope: CoroutineScope, private val prober: Prober) {

    private val lock = Any()
    private var generation = 0
    private var page = ""
    private val known = LinkedHashMap<String, Candidate>()
    private val children = HashSet<String>()
    private val dirCounts = HashMap<String, Int>()
    private var hasManifest = false

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    /** Alle Funde der Seite, auch ungeprüfte und verworfene. Die Oberfläche filtert selbst. */
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val _meta = MutableStateFlow(PageMeta())
    val meta: StateFlow<PageMeta> = _meta.asStateFlow()

    /** Neue Seite: alles vergessen. Gleiches Dokument (nur #fragment anders): nichts tun. */
    fun reset(pageUrl: String) {
        synchronized(lock) {
            if (UrlClassifier.key(page) == UrlClassifier.key(pageUrl)) return
            generation++
            page = pageUrl
            known.clear()
            children.clear()
            dirCounts.clear()
            hasManifest = false
        }
        _candidates.value = emptyList()
        _meta.value = PageMeta()
    }

    fun updateMeta(title: String, image: String?) {
        _meta.value = PageMeta(title.trim(), image?.takeIf { it.startsWith("http") })
    }

    fun report(
        url: String,
        source: Source,
        mime: String? = null,
        referer: String? = null,
        title: String? = null,
        poster: String? = null,
    ) {
        val kind = UrlClassifier.classify(url, mime) ?: return
        val key = UrlClassifier.key(url)
        val passive = source == Source.NETWORK || source == Source.XHR
        val gen: Int
        val candidate: Candidate

        synchronized(lock) {
            val existing = known[key]
            if (existing != null) {
                // Später gemeldete Details (Poster, Titel aus dem DOM) nachtragen.
                if ((existing.thumbnail == null && poster != null) || (existing.title == null && title != null)) {
                    known[key] = existing.copy(thumbnail = existing.thumbnail ?: poster, title = existing.title ?: title)
                    publishLocked()
                }
                return
            }
            if (key in children) return

            if (kind == MediaKind.FILE && passive) {
                if (UrlClassifier.looksLikeSegment(url)) return
                // Wo ein Manifest geladen wurde, sind nachfolgende Video-Antworten fast immer dessen Teile.
                if (hasManifest) return
                // Viele gleichartige Dateien aus demselben Ordner: gestückelter Stream.
                val dir = UrlClassifier.dir(url)
                val n = (dirCounts[dir] ?: 0) + 1
                dirCounts[dir] = n
                if (n > MAX_FILES_PER_DIR) {
                    known.entries.removeAll { (k, c) ->
                        c.kind == MediaKind.FILE && (c.source == Source.NETWORK || c.source == Source.XHR) && UrlClassifier.dir(k) == dir
                    }
                    publishLocked()
                    return
                }
            }
            if (kind != MediaKind.FILE) hasManifest = true

            gen = generation
            candidate = Candidate(
                url = url,
                kind = kind,
                source = source,
                pageUrl = page,
                referer = referer ?: page.ifBlank { null },
                title = title?.takeIf { it.isNotBlank() },
                thumbnail = poster,
                mime = mime,
            )
            known[key] = candidate
            publishLocked()
        }

        scope.launch {
            val probed = prober.probe(candidate)
            synchronized(lock) {
                if (gen != generation) return@launch
                val current = known[key] ?: return@launch
                known[key] = probed.copy(
                    title = current.title ?: probed.title,
                    thumbnail = current.thumbnail ?: probed.thumbnail,
                )
                if (probed.children.isNotEmpty()) {
                    children += probed.children.map(UrlClassifier::key)
                    known.keys.removeAll(children)
                }
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        _candidates.value = known.values.toList()
    }

    private companion object {
        const val MAX_FILES_PER_DIR = 3
    }
}
