package de.marne.streamplayer.stats

import android.content.Context
import android.os.Build
import android.util.Log
import de.marne.streamplayer.App
import de.marne.streamplayer.BuildConfig
import de.marne.streamplayer.download.Offline
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/**
 * Nutzungszahlen für die Admin-Übersicht – nur Zahlen: wie oft, wie lange, was schiefging.
 * Keine Titel, keine Seitenadressen, keine Suchbegriffe; bei einer fehlgeschlagenen Video-Suche
 * nur die Domain. Abschaltbar (Einstellungen → Datenschutz); dann wird auch gelöscht, was schon
 * beim Server liegt ([forget]).
 *
 * Gezählt wird je Tag in einer kleinen Datei; geschickt wird beim Wechsel in den Hintergrund,
 * höchstens alle drei Stunden, immer die letzten 14 Tage – der Server überschreibt je Tag, so
 * sind Wiederholungen harmlos und nichts zählt doppelt.
 */
object Usage {
    private const val TAG = "Usage"
    private const val KEEP_DAYS = 14L
    private const val MIN_GAP_MS = 3 * 60 * 60 * 1000L

    private lateinit var file: File
    private val days = linkedMapOf<String, JSONObject>()
    private val domains = linkedMapOf<String, Int>()   // "tag|domain" → Anzahl
    private val crashes = linkedMapOf<String, Int>()   // "tag|signatur" → Anzahl

    fun init(context: Context) {
        file = File(context.filesDir, "usage.json")
        load()
        installCrashCounter()
    }

    private val enabled: Boolean get() = App.instance.settings.usageStats.value

    // ---------- Zählen ----------

    fun session() = add("sessions")
    fun play() = add("plays")
    fun watched(ms: Long) { if (ms > 0) add("watchMs", ms) }
    fun downloadStarted() = add("dlStarted")
    fun downloadDone() = add("dlDone")
    fun downloadFailed() = add("dlFailed")

    /** Video auf einer Seite nicht gefunden – nur die Domain wird gemerkt, nie die Adresse. */
    @Synchronized
    fun findFailed(host: String?) {
        if (!enabled) return
        add("findFailed")
        val d = host?.lowercase()?.removePrefix("www.")?.take(80)?.takeIf { it.isNotBlank() } ?: return
        val k = "${today()}|$d"
        domains[k] = (domains[k] ?: 0) + 1
        save()
    }

    @Synchronized
    private fun add(field: String, n: Long = 1) {
        if (!enabled) return
        val d = days.getOrPut(today()) { JSONObject() }
        d.put(field, d.optLong(field) + n)
        save()
    }

    /** Absturz: Typ und erste Stelle im eigenen Code, sonst nichts (keine Nachricht – die kann Adressen enthalten). */
    private fun installCrashCounter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                if (enabled) synchronized(this) {
                    val frame = e.stackTrace.firstOrNull { it.className.startsWith("de.marne.") }
                    val sig = (e::class.java.simpleName + (frame?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "")).take(160)
                    val k = "${today()}|$sig"
                    crashes[k] = (crashes[k] ?: 0) + 1
                    days.getOrPut(today()) { JSONObject() }.let { it.put("crashes", it.optLong("crashes") + 1) }
                    save()
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    // ---------- Schicken ----------

    /** In den Hintergrund gewechselt: schicken, wenn es Zeit ist. */
    fun maybeSend() {
        if (!enabled || BuildConfig.STATS_URL.isEmpty()) return
        val prefs = App.instance.settings
        if (System.currentTimeMillis() - prefs.usageLastSent < MIN_GAP_MS) return
        val body = synchronized(this) { report() }
        post("/v1/report", body) { ok -> if (ok) prefs.usageLastSent = System.currentTimeMillis() }
    }

    /** Abgeschaltet: beim Server alles zu dieser Installation löschen, hier auch. */
    @Synchronized
    fun forget() {
        days.clear(); domains.clear(); crashes.clear(); save()
        if (BuildConfig.STATS_URL.isEmpty()) return
        post("/v1/forget", JSONObject().put("install", installId()).toString()) {}
    }

    private fun report(): String {
        val app = App.instance
        val dl = app.downloads.items.value.filter { it.state == de.marne.streamplayer.download.DlState.DONE }
        val user = app.cloud.user.value
        return JSONObject()
            .put("install", installId())
            .put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("lang", app.settings.language.value.tag)
            .put("account", user?.let { JSONObject().put("uid", it.uid).put("name", it.name ?: it.email?.substringBefore('@') ?: "") } ?: JSONObject.NULL)
            .put("counts", JSONObject()
                .put("titles", app.library.titles.value.size)
                .put("libraries", app.library.libraries.value.size)
                .put("profiles", app.profiles.profiles.value.size)
                .put("downloadsStored", dl.size)
                .put("bytesStored", Offline.bytes(dl)))
            .put("days", JSONArray().apply {
                days.forEach { (day, d) ->
                    put(JSONObject().put("day", day).apply {
                        listOf("sessions", "watchMs", "plays", "dlStarted", "dlDone", "dlFailed", "findFailed", "crashes").forEach { put(it, d.optLong(it)) }
                    })
                }
            })
            .put("findFailedDomains", JSONArray().apply {
                domains.forEach { (k, n) -> put(JSONObject().put("day", k.substringBefore('|')).put("domain", k.substringAfter('|')).put("count", n)) }
            })
            .put("crashes", JSONArray().apply {
                crashes.forEach { (k, n) -> put(JSONObject().put("day", k.substringBefore('|')).put("sig", k.substringAfter('|')).put("count", n)) }
            })
            .toString()
    }

    private fun post(path: String, body: String, done: (Boolean) -> Unit) {
        val req = Request.Builder().url(BuildConfig.STATS_URL + path)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        App.instance.http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.i(TAG, "nicht geschickt: ${e.message}"); done(false) }
            override fun onResponse(call: Call, response: Response) { response.use { done(it.isSuccessful) } }
        })
    }

    // ---------- Speicher ----------

    /** Zufällige Kennung dieser Installation – nicht an Gerät oder Person gebunden. */
    fun installId(): String = App.instance.settings.installId

    private fun today() = LocalDate.now().toString()

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val o = JSONObject(file.readText())
        o.optJSONObject("days")?.let { d -> d.keys().forEach { days[it] = d.getJSONObject(it) } }
        o.optJSONObject("domains")?.let { d -> d.keys().forEach { domains[it] = d.getInt(it) } }
        o.optJSONObject("crashes")?.let { d -> d.keys().forEach { crashes[it] = d.getInt(it) } }
    }

    private fun save() {
        val cut = LocalDate.now().minusDays(KEEP_DAYS).toString()
        days.keys.filter { it < cut }.forEach(days::remove)
        domains.keys.filter { it.substringBefore('|') < cut }.forEach(domains::remove)
        crashes.keys.filter { it.substringBefore('|') < cut }.forEach(crashes::remove)
        runCatching {
            val o = JSONObject()
                .put("days", JSONObject().apply { days.forEach { (k, v) -> put(k, v) } })
                .put("domains", JSONObject(domains as Map<*, *>))
                .put("crashes", JSONObject(crashes as Map<*, *>))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    @Suppress("unused")
    private fun newId() = UUID.randomUUID().toString()
}
