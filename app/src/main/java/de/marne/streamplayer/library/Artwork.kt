package de.marne.streamplayer.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import de.marne.streamplayer.catalog.Tmdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * „Alle Bilder vorab laden“: Cover und Banner (auf Wunsch auch Folgenbilder) aller Titel in den
 * Bild-Speicher holen – danach sind sie ohne Netz da. Geladen wird genau die Adresse, die die
 * Oberfläche zeigt (Tmdb.large), sonst träfe der Speicher nie.
 *
 * Anlass für den Umbau: Der Lauf hing an der offenen App – App zu, Lauf weg, kein Fortsetzen.
 * Ohne Netz zählte er in Sekunden alles als Fehler, und bei großen Bibliotheken legte er für jedes
 * Bild sofort eine Coroutine an. Jetzt:
 * - Der Plan liegt auf der Platte; ein Vordergrund-Dienst ([LibraryWorkService]) hält die App am
 *   Leben, und beim nächsten Öffnen geht ein abgebrochener Lauf von selbst weiter.
 * - Fortsetzen heißt: von vorn, aber was schon im Speicher liegt, wird nur nachgeschlagen –
 *   ohne Netz und ohne Entpacken. Das übersteht auch eine Bibliothek, die sich inzwischen geändert hat.
 * - Ohne Netz (oder bei „nur WLAN“ im Mobilfunk) wartet er, statt zu scheitern.
 */
object Artwork {
    /** [waiting]: wartet auf (WLAN-)Netz. */
    data class Run(val done: Int, val total: Int, val failed: Int, val running: Boolean, val waiting: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _run = MutableStateFlow<Run?>(null)
    val run: StateFlow<Run?> = _run.asStateFlow()

    /** Grobe Größen je Bild (TMDB, die Größen aus [Tmdb.large]) – für die Abschätzung vor dem Start. */
    const val COVER_BYTES = 130L * 1024
    const val BANNER_BYTES = 260L * 1024
    const val STILL_BYTES = 420L * 1024

    data class Counts(val covers: Int, val banners: Int, val stills: Int) {
        fun bytes(episodes: Boolean) = covers * COVER_BYTES + banners * BANNER_BYTES + if (episodes) stills * STILL_BYTES else 0
    }

    /** Nur zählen – ohne für jede Serie alle Folgen zu erzeugen (das ruckelte bei großen Bibliotheken). */
    fun count(titles: List<LibTitle>): Counts {
        val covers = HashSet<String>()
        val banners = HashSet<String>()
        var stills = 0
        titles.forEach { t ->
            Tmdb.large(t.cover)?.takeIf { it.startsWith("http") }?.let(covers::add)
            Tmdb.large(t.wide)?.takeIf { it.startsWith("http") && it !in covers }?.let(banners::add)
            if (t.kind == TitleKind.SERIES) stills += t.episodeInfo.values.count { it.still != null }
        }
        return Counts(covers.size, banners.size, stills)
    }

    /**
     * Reihenfolge: Cover, Banner, Folgenbilder. Ganz am Ende werden Cover und Banner noch einmal
     * nachgeschlagen (s. [start]) – der Bild-Speicher wirft bei Platzmangel das am längsten nicht
     * Benutzte hinaus; so sind das die Folgenbilder, nicht die Cover.
     */
    fun urls(titles: List<LibTitle>, episodes: Boolean): List<String> {
        val sorted = titles.sortedBy { it.id }
        val out = LinkedHashSet<String>()
        sorted.forEach { t -> Tmdb.large(t.cover)?.takeIf { it.startsWith("http") }?.let(out::add) }
        sorted.forEach { t -> Tmdb.large(t.wide)?.takeIf { it.startsWith("http") }?.let(out::add) }
        if (episodes) sorted.forEach { t ->
            if (t.kind == TitleKind.SERIES && t.episodeInfo.isNotEmpty()) t.episodes().forEach { e -> Tmdb.large(e.still)?.takeIf { it.startsWith("http") }?.let(out::add) }
        }
        return out.toList()
    }

    // ---------- Lauf ----------

    fun start(context: Context, episodes: Boolean) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        savePlan(app, Plan(active = true, episodes = episodes))
        _run.value = Run(0, 0, 0, running = true)
        LibraryWorkService.start(app)
        job = scope.launch { runPlan(app, episodes) }
    }

    /** Beim Öffnen der App: Ein abgebrochener Lauf geht weiter. */
    fun resume(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val plan = loadPlan(app)
        if (plan.active) {
            start(app, plan.episodes)
        } else if (_run.value == null && plan.total > 0) {
            // Letztes Ergebnis zeigen (Einstellungen): „Fertig: x von y“.
            _run.value = Run(plan.done, plan.total, plan.failed, running = false)
        }
    }

    fun cancel(context: Context) {
        job?.cancel()
        job = null
        val app = context.applicationContext
        val r = _run.value
        savePlan(app, Plan(active = false, episodes = false, done = r?.done ?: 0, total = r?.total ?: 0, failed = r?.failed ?: 0))
        _run.value = r?.copy(running = false, waiting = false)
    }

    private suspend fun runPlan(context: Context, episodes: Boolean) {
        val loader = SingletonImageLoader.get(context)
        val disk = loader.diskCache
        val titles = de.marne.streamplayer.App.instance.library.titles.value
        val list = urls(titles, episodes)
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val waiting = java.util.concurrent.atomic.AtomicBoolean(false)
        fun publish(running: Boolean = true) {
            _run.value = Run(done.get(), list.size, failed.get(), running, waiting.get())
        }
        publish()

        suspend fun one(url: String): Boolean {
            // Schon da: nur nachschlagen – kein Netz, kein Entpacken. (Nachschlagen zählt im Speicher
            // auch als „benutzt“, s. Reihenfolge in [urls].)
            if (disk?.openSnapshot(url)?.use { true } == true) return true
            var attempt = 0
            while (true) {
                // Ohne Netz oder bei „nur WLAN“ im Mobilfunk: warten statt scheitern.
                while (!networkOk(context)) {
                    waiting.set(true)
                    publish()
                    delay(5_000)
                }
                if (waiting.getAndSet(false)) publish()
                val result = loader.execute(
                    ImageRequest.Builder(context).data(url)
                        // Nur auf die Platte, nicht in den Arbeitsspeicher; klein entpacken spart Rechenzeit –
                        // gespeichert wird trotzdem die ganze Datei.
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .size(64, 96)
                        .build()
                )
                if (result is SuccessResult) return true
                val e = (result as? ErrorResult)?.throwable
                if (e is CancellationException) throw e
                val http = (e as? coil3.network.HttpException)?.response?.code
                // Gibt es nicht (404 …): erledigt. Netz- oder Serverfehler: ein paar Mal mit Pause.
                if (http != null && http in 400..499 && http != 429) return false
                if (++attempt >= 4) return false
                delay(2_000L * attempt)
            }
        }

        run {
            // Vier gleichzeitig: schnell genug, ohne TMDB oder das Netz zu fluten. Feste Arbeiter
            // statt einer Coroutine je Bild – bei Zehntausenden Bildern lagen die sonst alle im Speicher.
            val workers = List(4) {
                scope.launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= list.size) break
                        val ok = try { one(list[i]) } catch (e: CancellationException) { throw e } catch (e: IOException) { false } catch (e: Exception) { false }
                        done.incrementAndGet()
                        if (!ok) failed.incrementAndGet()
                        publish()
                    }
                }
            }
            workers.forEach { it.join() }
            // Cover und Banner zuletzt noch einmal anfassen: die sind dann die „zuletzt benutzten“ und
            // bleiben, wenn der Speicher voll ist.
            if (episodes) urls(titles, false).forEach { url -> disk?.openSnapshot(url)?.close() }
            savePlan(context, Plan(active = false, episodes = episodes, done = done.get(), total = list.size, failed = failed.get()))
            publish(running = false)
        }
    }

    private fun networkOk(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val wifiOnly = de.marne.streamplayer.App.instance.settings.wifiOnly.value
        !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    // ---------- Plan auf der Platte ----------

    private data class Plan(val active: Boolean, val episodes: Boolean, val done: Int = 0, val total: Int = 0, val failed: Int = 0)

    private fun planFile(context: Context) = File(context.filesDir, "artwork.json")

    private fun savePlan(context: Context, p: Plan) = runCatching {
        planFile(context).writeText(
            JSONObject().put("active", p.active).put("episodes", p.episodes).put("done", p.done).put("total", p.total).put("failed", p.failed).toString()
        )
    }

    private fun loadPlan(context: Context): Plan = runCatching {
        val o = JSONObject(planFile(context).readText())
        Plan(o.optBoolean("active"), o.optBoolean("episodes"), o.optInt("done"), o.optInt("total"), o.optInt("failed"))
    }.getOrDefault(Plan(active = false, episodes = false))

    // ---------- Speicher ----------

    /** Belegter Platz des Bild-Speichers. */
    fun cacheBytes(context: Context): Long = SingletonImageLoader.get(context).diskCache?.size ?: 0

    /** Höchstgröße des Bild-Speichers. */
    fun cacheMax(context: Context): Long = SingletonImageLoader.get(context).diskCache?.maxSize ?: 0

    /**
     * Wie groß der Bild-Speicher sein darf: ein Viertel von (frei + schon belegt), mindestens 1,5 GB,
     * höchstens 8 GB. Früher fest 1,5 GB – bei Tausenden Titeln verdrängten spätere Bilder die früh
     * geladenen wieder. [used]: zuletzt gemessene Belegung (den Ordner zu durchlaufen dauerte zu lange).
     */
    fun cacheLimit(dir: File, used: Long): Long {
        val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
        return ((free + used) / 4).coerceIn(MIN_CACHE, MAX_CACHE)
    }

    private const val MIN_CACHE = 1_500L * 1024 * 1024
    private const val MAX_CACHE = 8L * 1024 * 1024 * 1024

    fun clear(context: Context) {
        cancel(context)
        SingletonImageLoader.get(context).diskCache?.clear()
        SingletonImageLoader.get(context).memoryCache?.clear()
        planFile(context).delete()
        _run.value = null
    }
}
