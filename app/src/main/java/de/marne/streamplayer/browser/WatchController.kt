package de.marne.streamplayer.browser

import android.os.SystemClock
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.detect.ProbeStatus
import de.marne.streamplayer.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Modus „Nur ansehen“: Link rein, Video raus. Die Seite lädt in der normalen WebView – verdeckt,
 * aber auf dem Bildschirm, weil viele Player sich erst aufbauen, wenn sie sichtbar sind.
 *
 * Ablauf: Seite laden → Player fragen (detect.js tut das ohnehin) → findet sich nichts, das Video
 * stumm anstoßen → besten Fund wählen → eigener Player.
 */
class WatchController(
    private val browser: BrowserController,
    private val hub: DetectionHub,
    private val scope: CoroutineScope,
) {
    /**
     * Wer fragt und wozu. key: Fortschritts-Schlüssel (Bibliothek); download: statt abspielen laden;
     * fromLibrary: danach zurück in die Bibliothek statt die Ergebnis-Ansicht zu zeigen.
     */
    data class Request(
        val key: String? = null,
        val label: String? = null,
        val titleId: String? = null,
        val download: Boolean = false,
        val fromLibrary: Boolean = false,
    )

    var request: Request = Request()
        private set

    sealed interface State {
        data object Idle : State
        /** phase: 0 Seite lädt, 1 Player fragen, 2 stumm anstoßen, 3 gefunden – treibt den Ladebalken. */
        data class Searching(val host: String, val step: String, val phase: Int = 0) : State
        /** autoStart: Player sofort öffnen; danach false, dann zeigt die Ansicht die Auswahl. */
        data class Ready(val candidate: Candidate, val title: String, val thumbnail: String?, val autoStart: Boolean) : State
        data class NotFound(val host: String) : State
        data class Protected(val title: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var startUrl: String? = null

    val active: Boolean get() = _state.value != State.Idle

    fun start(input: String, request: Request = Request()) {
        val url = BrowserController.toUrl(input) ?: return
        stopSearch()
        this.request = request
        val host = Format.host(url)
        startUrl = url
        // Sofort leeren, nicht erst beim ersten Netzwerk-Request: sonst zählt ein Fund der alten Seite.
        hub.reset(url)
        browser.setAutoplay(true)
        browser.load(url)
        _state.value = State.Searching(request.label ?: host, "Seite lädt …")

        job = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var nudged = false
            var firstHit = 0L
            while (isActive) {
                delay(300)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - t0
                val all = hub.candidates.value.filter { it.status == ProbeStatus.OK }
                val usable = all.filter { !it.drm }

                if (usable.isNotEmpty()) {
                    if (firstHit == 0L) {
                        firstHit = now
                        _state.value = State.Searching(host, "Gefunden. Player startet …", 3)
                    }
                    // Kurz weitersammeln: Oft kommen Manifest und Qualitäten gleich hinterher.
                    if (now - firstHit >= GRACE_MS) {
                        val best = pick(usable)
                        browser.pausePage()
                        browser.setAutoplay(false)
                        val ready = State.Ready(
                            candidate = best,
                            title = titleFor(best, hub.meta.value, browser.title.value),
                            thumbnail = best.thumbnail ?: hub.meta.value.image,
                            autoStart = true,
                        )
                        // Titel in der Bibliothek ohne Bild/Titel? Jetzt wissen wir beides.
                        val library = de.marne.streamplayer.App.instance.library
                        val id = request.titleId ?: library.findByPage(url)?.id
                        if (id != null) library.enrich(id, ready.title, ready.thumbnail, null, best.durationMs)
                        _state.value = ready
                        return@launch
                    }
                } else {
                    val loaded = !browser.loading.value
                    if (!nudged && ((loaded && elapsed > NUDGE_AFTER_MS) || elapsed > NUDGE_LATEST_MS)) {
                        nudged = true
                        browser.nudgePlayback()
                        _state.value = State.Searching(host, "Starte das Video stumm …", 2)
                    } else if (!nudged && loaded) {
                        _state.value = State.Searching(host, "Frage den Player …", 1)
                    }
                }

                if (elapsed > TIMEOUT_MS) {
                    browser.setAutoplay(false)
                    browser.pausePage()
                    val protectedOne = all.firstOrNull { it.drm }
                    _state.value = if (protectedOne != null) {
                        State.Protected(titleFor(protectedOne, hub.meta.value, browser.title.value))
                    } else {
                        State.NotFound(host)
                    }
                    return@launch
                }
            }
        }
    }

    /** Player ist offen; beim Zurückkommen zeigt die Ansicht „Nochmal“, „Laden“, „Seite anzeigen“. */
    fun playerOpened() {
        val s = _state.value
        if (s is State.Ready) _state.value = s.copy(autoStart = false)
    }

    /** Suche abbrechen und zur Startseite. */
    fun cancel() {
        stopSearch()
        browser.stop()
        browser.pausePage()
        browser.showStart()
        _state.value = State.Idle
    }

    /** Ansicht weg, Seite bleibt sichtbar – zum selbst Starten oder Weiterklicken. */
    fun showPage() {
        stopSearch()
        _state.value = State.Idle
    }

    private fun stopSearch() {
        job?.cancel()
        job = null
        browser.setAutoplay(false)
    }

    /** Das Hauptvideo, nicht der Werbeclip: Streams vor Dateien, dann das längste bzw. größte. */
    private fun pick(list: List<Candidate>): Candidate = list.sortedWith(
        compareByDescending<Candidate> { it.kind != MediaKind.FILE }
            .thenByDescending { it.durationMs ?: 0 }
            .thenByDescending { it.sizeBytes ?: 0 }
    ).first()

    private companion object {
        const val GRACE_MS = 1500L
        const val NUDGE_AFTER_MS = 4000L
        const val NUDGE_LATEST_MS = 10_000L
        const val TIMEOUT_MS = 30_000L
    }
}
