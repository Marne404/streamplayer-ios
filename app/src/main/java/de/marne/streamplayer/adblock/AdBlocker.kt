package de.marne.streamplayer.adblock

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import de.marne.streamplayer.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Werbeblocker mit den Listen, die auch uBlock Origin standardmäßig nutzt.
 * Startbestand liegt im APK (assets/adblock), Aktualisierungen in filesDir/adblock.
 */
class AdBlocker(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: Settings,
) {
    data class FilterList(val id: String, val name: String, val url: String)

    data class Status(
        val ready: Boolean = false,
        val rules: Int = 0,
        val updatedAt: Instant? = null,
        val updating: Boolean = false,
        val error: String? = null,
    )

    @Volatile
    private var rules: RuleSet? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Eigener Thread mit etwas niedrigerer Priorität: Das Auswerten von ~130.000 Regeln soll
    // nie mit dem Start der WebView um die CPU konkurrieren.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "adblock")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val ready = CountDownLatch(1)
    private val updateLock = Mutex()
    private val dir = File(context.filesDir, "adblock")
    private val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)

    /** Lädt die Regeln im Hintergrund; sind die Listen älter als 4 Tage, werden sie erneuert. */
    fun start() {
        scope.launch {
            load()
            // Erster Start: die mitgelieferten Listen zählen als frisch, kein Sofort-Download.
            val last = prefs.getLong(KEY_CHECKED, bundledStand()?.toEpochMilli() ?: 0)
            if (System.currentTimeMillis() - last > MAX_AGE_MS) update()
        }
    }

    // ---------- Abfragen (aus beliebigen Threads) ----------

    /** Blockieren aktiv für diese Seite? Aus in den Einstellungen oder Seite erlaubt: nein. */
    fun activeFor(pageHost: String?): Boolean =
        settings.adblock.value && rules != null &&
            (pageHost == null || RuleSet.suffixes(pageHost).none { it in settings.allowlist.value })

    /** Läuft im Netzwerk-Thread der WebView; wartet beim Kaltstart kurz auf die Regeln. */
    fun shouldBlock(url: String, pageUrl: String?): Boolean {
        if (rules == null && settings.adblock.value) ready.await(4, TimeUnit.SECONDS)
        val r = rules ?: return false
        val pageHost = pageUrl?.let(RuleSet::hostOf)
        if (!activeFor(pageHost)) return false
        val host = RuleSet.hostOf(url) ?: return false
        return r.blocks(url, host, pageHost)
    }

    fun blocksNavigation(url: String, gesture: Boolean, pageUrl: String?): Boolean {
        val r = rules ?: return false
        if (!activeFor(pageUrl?.let(RuleSet::hostOf))) return false
        val host = RuleSet.hostOf(url) ?: return false
        return r.blocksNavigation(host, gesture)
    }

    fun blocksPopup(url: String, pageUrl: String?): Boolean {
        val r = rules ?: return false
        if (!activeFor(pageUrl?.let(RuleSet::hostOf))) return false
        val host = RuleSet.hostOf(url) ?: return false
        return r.blocksPopup(host)
    }

    fun cssFor(frameUrl: String, mainFrame: Boolean, pageUrl: String?): String? {
        val r = rules ?: return null
        if (!activeFor(pageUrl?.let(RuleSet::hostOf))) return null
        val host = RuleSet.hostOf(frameUrl) ?: return null
        return r.cssFor(host, mainFrame).ifEmpty { null }
    }

    // ---------- Laden und Aktualisieren ----------

    private fun load() {
        val t0 = SystemClock.elapsedRealtime()
        val parser = FilterParser()
        LISTS.forEach { list -> readList(list)?.let(parser::add) }
        val set = parser.build()
        rules = set
        ready.countDown()
        val updatedAt = prefs.getLong(KEY_UPDATED, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: bundledStand()
        _status.update { it.copy(ready = true, rules = set.ruleCount, updatedAt = updatedAt) }
        Log.i(TAG, "${set.ruleCount} Regeln in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun readList(list: FilterList): String? {
        val f = File(dir, "${list.id}.txt")
        if (f.exists()) runCatching { return f.readText() }
        return runCatching { context.assets.open("adblock/${list.id}.txt").bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun bundledStand(): Instant? = runCatching {
        Instant.parse(context.assets.open("adblock/stand.txt").bufferedReader().use { it.readText().trim() })
    }.getOrNull()

    /** Holt alle Listen neu. Eine Liste, die nicht kommt, bleibt beim alten Stand. */
    fun updateNow() {
        scope.launch { update() }
    }

    private suspend fun update() = updateLock.withLock {
        _status.update { it.copy(updating = true, error = null) }
        dir.mkdirs()
        var ok = 0
        for (list in LISTS) {
            val got = runCatching {
                http.newCall(Request.Builder().url(list.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
            }.getOrNull()
            // Plausibel? Eine echte Liste hat Tausende Zeilen; Fehlerseiten nicht.
            if (got != null && got.lineSequence().take(5000).count() > 1000) {
                val tmp = File(dir, "${list.id}.tmp")
                tmp.writeText(got)
                tmp.renameTo(File(dir, "${list.id}.txt"))
                ok++
            }
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_CHECKED, now).apply()
        if (ok > 0) {
            prefs.edit().putLong(KEY_UPDATED, now).apply()
            load()
        }
        _status.update {
            it.copy(updating = false, error = if (ok == 0) "Listen nicht erreichbar. Später noch einmal." else null)
        }
    }

    companion object {
        private const val TAG = "AdBlocker"
        private const val KEY_CHECKED = "checked_at"
        private const val KEY_UPDATED = "updated_at"
        private const val MAX_AGE_MS = 4L * 24 * 60 * 60 * 1000

        val LISTS = listOf(
            FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt"),
            FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
            FilterList("peterlowe", "Peter Lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"),
        )
    }
}
