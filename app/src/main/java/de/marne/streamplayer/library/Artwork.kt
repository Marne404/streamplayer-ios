package de.marne.streamplayer.library

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import de.marne.streamplayer.catalog.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * „Alle Bilder vorab laden“: Cover und Banner (auf Wunsch auch Folgenbilder) aller Titel in den
 * Bild-Speicher holen – danach sind sie ohne Netz da. Geladen wird genau die Adresse, die die
 * Oberfläche zeigt (Tmdb.large), sonst träfe der Speicher nie.
 */
object Artwork {
    data class Run(val done: Int, val total: Int, val failed: Int, val running: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _run = MutableStateFlow<Run?>(null)
    val run: StateFlow<Run?> = _run.asStateFlow()

    fun urls(titles: List<LibTitle>, episodes: Boolean): List<String> = buildList {
        titles.forEach { t ->
            listOf(t.cover, t.wide).forEach { u -> Tmdb.large(u)?.takeIf { it.startsWith("http") }?.let(::add) }
            if (episodes) t.episodes().forEach { e -> Tmdb.large(e.still)?.takeIf { it.startsWith("http") }?.let(::add) }
        }
    }.distinct()

    fun start(context: Context, titles: List<LibTitle>, episodes: Boolean) {
        if (job?.isActive == true) return
        val list = urls(titles, episodes)
        val loader = SingletonImageLoader.get(context)
        _run.value = Run(0, list.size, 0, true)
        job = scope.launch {
            // Vier gleichzeitig: schnell genug, ohne TMDB oder das Netz zu fluten.
            val gate = Semaphore(4)
            list.map { url ->
                launch {
                    gate.withPermit {
                        val ok = runCatching {
                            loader.execute(
                                ImageRequest.Builder(context).data(url)
                                    // Nur auf die Platte, nicht in den Arbeitsspeicher; klein entpacken spart Rechenzeit –
                                    // gespeichert wird trotzdem die ganze Datei.
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .size(64, 96)
                                    .build()
                            ) is SuccessResult
                        }.getOrDefault(false)
                        synchronized(this@Artwork) {
                            val r = _run.value ?: return@synchronized
                            _run.value = r.copy(done = r.done + 1, failed = r.failed + if (ok) 0 else 1)
                        }
                    }
                }
            }.forEach { it.join() }
            _run.value = _run.value?.copy(running = false)
        }
    }

    fun cancel() {
        job?.cancel()
        _run.value = _run.value?.copy(running = false)
    }

    /** Belegter Platz des Bild-Speichers. */
    fun cacheBytes(context: Context): Long = SingletonImageLoader.get(context).diskCache?.size ?: 0

    fun clear(context: Context) {
        SingletonImageLoader.get(context).diskCache?.clear()
        SingletonImageLoader.get(context).memoryCache?.clear()
        _run.value = null
    }
}
