package de.marne.streamplayer.creator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.marne.streamplayer.importer.FetchException
import de.marne.streamplayer.importer.Importer
import de.marne.streamplayer.importer.LinkItem
import de.marne.streamplayer.importer.Throttle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Große Linklisten (Hunderte bis Tausende Links, z. B. eine .txt mit einem Link pro Zeile).
 * Der normale Massenimport lässt jeden Entwurf prüfen und hört bei 200 auf – bei 2500 Links ginge
 * das nicht. Hier:
 * - Was schon im Katalog steht, wird übersprungen (abwählbar).
 * - Jeder Link wird gelesen und gleich übernommen; neue Titel ohne TMDB-Treffer findet man danach
 *   unter dem Filter „No TMDB“.
 * - Nacheinander, mit derselben Bremse wie der Übersichts-Import (robots.txt, Pause je Abruf,
 *   Rückzug bei 429) – eine große Liste darf eine Seite nicht überrennen.
 * - Der Stand liegt in einer Datei: Pause, App zu, Handy neu gestartet – „Fortsetzen“ macht weiter.
 * - Läuft im Hintergrund weiter (Vordergrund-Dienst mit Fortschritt in der Benachrichtigung).
 */
class BulkImport(private val app: CreatorApp, private val session: ImportSession, private val http: OkHttpClient) {

    data class Link(val url: String, val name: String?, val status: Int = WAITING, val error: String? = null)

    data class State(
        val links: List<Link>,
        val running: Boolean = false,
        /** Gerade gelesener Link und Schritt („Lade Staffel 2 …“). */
        val current: String? = null,
        /** Warum angehalten (Seite bremst, Zeitlimit des Systems …). */
        val halt: String? = null,
        /** Für die Restzeit: wann und bei wie vielen erledigten dieser Lauf begann. */
        val runStart: Long = 0,
        val doneAtStart: Int = 0,
    ) {
        val total get() = links.size
        val waiting get() = links.count { it.status == WAITING }
        val added get() = links.count { it.status == ADDED }
        val updated get() = links.count { it.status == UPDATED }
        val failed get() = links.count { it.status == FAILED }
        val skipped get() = links.count { it.status == SKIPPED }
        val done get() = total - waiting
        /** Restzeit aus dem bisherigen Tempo dieses Laufs; null, solange zu wenig gelaufen ist. */
        val etaMs: Long? get() {
            val n = done - doneAtStart
            if (!running || n < 3 || runStart == 0L) return null
            return (System.currentTimeMillis() - runStart) / n * waiting
        }
    }

    private val file = File(app.filesDir, "bulk.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _state = MutableStateFlow(load())
    /** null: keine große Liste in Arbeit. */
    val state: StateFlow<State?> = _state.asStateFlow()

    val running: Boolean get() = _state.value?.running == true

    /**
     * Neue Liste vorbereiten (noch nicht starten). [skipKnown]: was schon im Katalog steht, gleich
     * als übersprungen markieren. Rückgabe: wie viele davon schon bekannt sind.
     */
    fun prepare(items: List<LinkItem>, skipKnown: Boolean = true) {
        stop()
        val known = knownKeys()
        val links = items.map { l ->
            if (skipKnown && key(l.url) in known) Link(l.url, l.name, SKIPPED, "Already in the catalog")
            else Link(l.url, l.name)
        }
        set(State(links))
    }

    /** Bekannte Links umschalten: überspringen oder doch lesen (aktualisiert dann Staffeln). */
    fun setSkipKnown(skip: Boolean) {
        val s = _state.value ?: return
        if (s.running) return
        val known = knownKeys()
        set(s.copy(links = s.links.map { l ->
            when {
                skip && l.status == WAITING && key(l.url) in known -> l.copy(status = SKIPPED, error = "Already in the catalog")
                !skip && l.status == SKIPPED -> l.copy(status = WAITING, error = null)
                else -> l
            }
        }))
    }

    fun knownCount(): Int = _state.value?.links?.count { it.status == SKIPPED } ?: 0

    fun start(context: Context) {
        val s = _state.value ?: return
        if (s.running || s.waiting == 0) return
        if (session.busy) { session.notice.value = "Finish or cancel the current import first."; return }
        set(s.copy(running = true, halt = null, runStart = System.currentTimeMillis(), doneAtStart = s.done))
        BulkService.start(context)
        val plain = HttpFetcher(http, session.userAgent)
        // Kein Abruf-Budget wie beim Übersichts-Import (die Liste ist ja gewollt), aber dieselbe Pause.
        val throttle = Throttle(plain, Int.MAX_VALUE, app.prefs.crawlPause.value)
        val web = throttle.wrap(plain)
        val br = throttle.wrap(session.browser)
        job = scope.launch {
            try {
                while (true) {
                    val cur = _state.value ?: break
                    val i = cur.links.indexOfFirst { it.status == WAITING }
                    if (i < 0) break
                    val link = cur.links[i]
                    val host = link.url.substringAfter("://").substringBefore('/').removePrefix("www.")
                    update { it.copy(current = host) }
                    val result: Link = try {
                        if (!withContext(Dispatchers.Default) { throttle.allowed(link.url) }) link.copy(status = FAILED, error = Throttle.DISALLOWED)
                        else {
                            // Eine Seite, die auf Bestätigung wartet (Captcha), hält höchstens ein paar
                            // Minuten auf – dann weiter mit dem nächsten Link, dieser kommt in die Fehler.
                            val d = withTimeout(PER_LINK_MS) {
                                withContext(Dispatchers.Default) {
                                    Importer(web, br, app.tmdb, pauseMs = 0, maxPages = PER_TITLE).analyze(link.url) { step ->
                                        update { it.copy(current = "$host · $step") }
                                    }
                                }
                            }
                            val name = (link.name ?: d.title.title).trim()
                            if (name.isEmpty()) link.copy(status = FAILED, error = "No title found on the page")
                            else {
                                val (_, existed) = session.save(d, name)
                                link.copy(status = if (existed) UPDATED else ADDED, error = null)
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        withContext(NonCancellable) { session.browser.release() }
                        link.copy(status = FAILED, error = "Took too long (confirmation page?)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        link.copy(status = FAILED, error = e.message ?: "The page couldn't be read.")
                    }
                    update { s2 -> s2.copy(links = s2.links.toMutableList().also { it[i] = result }) }
                    BulkService.progress(app, _state.value)
                    // Seite bremst (zweimal 429): für heute Schluss, später fortsetzen.
                    throttle.stop?.let { stop ->
                        update { it.copy(halt = Throttle.message(stop)) }
                        break
                    }
                    delay(BETWEEN_LINKS_MS)
                }
            } finally {
                withContext(NonCancellable) {
                    update { it.copy(running = false, current = null) }
                    session.browser.release()
                    BulkService.stop(app)
                    BulkService.finished(app, _state.value)
                }
            }
        }
    }

    /** Anhalten; der Stand bleibt, „Fortsetzen“ macht weiter. */
    fun pause(reason: String? = null) {
        job?.cancel()
        job = null
        if (reason != null) update { it.copy(halt = reason) }
    }

    /** Fehlgeschlagene noch einmal einreihen. */
    fun retryFailed() = update { s -> s.copy(links = s.links.map { if (it.status == FAILED) it.copy(status = WAITING, error = null) else it }, halt = null) }

    /** Liste verwerfen (Übernommenes bleibt im Katalog). */
    fun clear() {
        stop()
        _state.value = null
        persist()
    }

    /** Fehlgeschlagene Links als Text – zum Teilen oder später erneut Einfügen. */
    fun failedText(): String = _state.value?.links?.filter { it.status == FAILED }?.joinToString("\n") { "${it.url}\t${it.error.orEmpty()}" }.orEmpty()

    private fun stop() {
        job?.cancel()
        job = null
    }

    // ---------- Was schon im Katalog steht ----------

    /**
     * Schlüssel bekannter Quellen: Filmseiten, und für Serien der Teil des Musters vor der ersten
     * Lücke samt allen Elternpfaden – ein Link auf die Übersicht „…/serie/foo“ passt so zu
     * „…/serie/foo/staffel-{s}/episode-{e}“.
     */
    private fun knownKeys(): Set<String> = buildSet {
        app.store.catalog.value.titles.forEach { t ->
            t.url?.let { add(key(it)) }
            listOfNotNull(t.template).plus(t.seasons.mapNotNull { it.template }).forEach { tpl ->
                var k = key(tpl.substringBefore('{'))
                // Elternpfade bis zur Domain, ohne die Domain selbst (sonst passte jede Seite).
                while ('/' in k) {
                    add(k)
                    k = k.substringBeforeLast('/')
                }
            }
        }
    }

    private fun key(u: String) = u.substringAfter("://").removePrefix("www.").substringBefore('#').substringBefore('?').trimEnd('/', '-', '_').lowercase()

    // ---------- Speicher ----------

    private fun update(block: (State) -> State) {
        val s = _state.value ?: return
        set(block(s))
    }

    private var lastSave = 0L
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()

    private fun set(s: State) {
        _state.value = s
        // Laufend höchstens alle 15 Sekunden auf die Platte – der Schritt ändert sich ständig, und
        // jedes Mal wird auch der Katalog gesichert (s. persist).
        val now = System.currentTimeMillis()
        if (!s.running || now - lastSave > 15_000) {
            lastSave = now
            persist()
        }
    }

    /**
     * Erst den Stand der Liste nehmen, dann den Katalog sichern, dann die Liste: So meldet die Datei
     * nie einen Link als „übernommen“, dessen Titel noch nicht im gespeicherten Katalog steht.
     * Stirbt der Prozess, werden höchstens die letzten Links noch einmal gelesen (dann „aktualisiert“).
     */
    private fun persist() = io.launch {
        saveLock.withLock {
            val s = _state.value
            app.store.flush()
            if (s == null) file.delete() else save(s)
        }
    }

    private fun save(s: State) = runCatching {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("{\"links\":[")
            s.links.forEachIndexed { i, it ->
                if (i > 0) w.write(",")
                w.write("\n")
                w.write(JSONObject().put("u", it.url).put("n", it.name).put("s", it.status).put("e", it.error).toString())
            }
            w.write("\n]}\n")
        }
        tmp.renameTo(file)
    }

    private fun load(): State? = runCatching {
        if (!file.exists()) return null
        val a = JSONObject(file.readText()).getJSONArray("links")
        // Nach einem Neustart läuft nichts: angehalten, „Fortsetzen“ macht weiter.
        State(List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Link(o.getString("u"), o.optString("n").takeIf { it.isNotBlank() && it != "null" }, o.optInt("s"), o.optString("e").takeIf { it.isNotBlank() && it != "null" })
        })
    }.getOrNull()

    companion object {
        const val WAITING = 0
        const val ADDED = 1
        const val UPDATED = 2
        const val FAILED = 3
        const val SKIPPED = 4
        private const val BETWEEN_LINKS_MS = 600L
        private const val PER_TITLE = 40
        private const val PER_LINK_MS = 4 * 60_000L
    }
}

/**
 * Hält den Creator am Leben, solange eine große Liste läuft, und zeigt den Fortschritt. Die Arbeit
 * selbst macht [BulkImport] – der Dienst ist nur der Grund für Android, den Prozess nicht zu beenden.
 */
class BulkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        val n = notification(this, CreatorApp.instance.imports.bulk.state.value)
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    /** Android 15+: Datenabgleich darf nur begrenzt laufen – dann sauber anhalten statt abgewürgt zu werden. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        CreatorApp.instance.imports.bulk.pause("Android paused the import after several hours – tap Resume to continue.")
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "bulk"
        private const val ID = 41

        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, BulkService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BulkService::class.java))
        }

        private var lastShown = 0L

        fun progress(context: Context, s: BulkImport.State?) {
            val now = System.currentTimeMillis()
            if (now - lastShown < 1_000) return
            lastShown = now
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, s)) }
        }

        /** TMDB für viele Titel ([TmdbFill]) nutzt denselben Dienst; Fortschritt in derselben Meldung. */
        fun fillProgress(context: Context, s: TmdbFill.State?) {
            s ?: return
            val now = System.currentTimeMillis()
            if (now - lastShown < 1_000) return
            lastShown = now
            channel(context)
            val title = if (s.mode == TmdbFill.Mode.MATCH) "Matching on TMDB" else "Taking covers from TMDB"
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText("%,d of %,d · %,d found".format(s.done, s.total, s.found))
                .setProgress(s.total, s.done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(context))
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, n) }
        }

        /** Fertig oder angehalten: eine bleibende Meldung mit dem Ergebnis. */
        fun finished(context: Context, s: BulkImport.State?) {
            s ?: return
            channel(context)
            val text = if (s.waiting == 0) "Done: ${s.added} added, ${s.updated} updated, ${s.failed} failed."
            else "Paused at ${s.done} of ${s.total}. ${s.halt.orEmpty()}"
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Big link list")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open(context))
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID + 1, n) }
        }

        private fun notification(context: Context, s: BulkImport.State?): Notification {
            val text = s?.let { "${it.done} of ${it.total} · ${it.added} added · ${it.failed} failed" } ?: "Starting …"
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Importing a big link list")
                .setContentText(text)
                .setProgress(s?.total ?: 0, s?.done ?: 0, s == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(context))
                .build()
        }

        private fun open(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CHANNEL, "Big link lists", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
