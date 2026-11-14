package de.marne.streamplayer.update

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object None : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Ready(val version: String) : UpdateState
    /** Store-Variante: neuere Version liegt in Atelier, aktualisiert wird dort. */
    data class InStore(val version: String) : UpdateState
}

/**
 * Selbst-Update für Player und Creator, beide per APK verteilt (Vorbild: HashRush). Beim Start
 * nachsehen, ob der Update-Worker eine höhere versionCode hat, die APK still im Hintergrund laden,
 * prüfen und dann Androids Installationsdialog anbieten. Ganz ohne Tippen geht es nicht – still
 * installieren darf nur ein App-Store.
 *
 * Jede App ruft [configure] in `Application.onCreate` mit der Adresse ihrer version.json auf
 * (Player: `/version.json`, Creator: `/creator/version.json`); die Stücke liegen daneben.
 * Debug-Builds übergeben nichts und fragen nie: sie heißen `….debug` und sind anders signiert –
 * das Release-Update ließe sich nicht darüber installieren, sondern käme als zweite App daneben.
 */
object Updater {
    private var versionUrl = ""

    fun configure(versionJsonUrl: String) { versionUrl = versionJsonUrl }

    /**
     * Store-Variante (Flavor „store“): nichts selbst laden, sondern Atelier fragen ([AtelierLink]) und
     * zum Aktualisieren dorthin schicken. Die Standalone-Variante ruft stattdessen [configure].
     */
    fun configureStore() { storeMode = true }
    private var storeMode = false
    val isStore get() = storeMode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Ob ein fertig geladenes Update gerade als Dialog angeboten wird. „Später“ blendet nur den aus. */
    private val _offer = MutableStateFlow(false)
    val offer: StateFlow<Boolean> = _offer.asStateFlow()

    val enabled get() = versionUrl.isNotEmpty() || storeMode
    private var checked = false

    private fun apk(context: Context) = File(File(context.cacheDir, "update").apply { mkdirs() }, "update.apk")

    private fun currentCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    enum class CheckResult { UpToDate, Found, Failed }

    /**
     * So lange gilt die Antwort der letzten Prüfung. Wer in den Einstellungen mehrmals tippt,
     * bekommt sie noch einmal, ohne dass der Worker gefragt wird.
     */
    private const val COOLDOWN_MS = 100_000L
    private val lock = Mutex()
    private var lastCheckAt = -COOLDOWN_MS
    private var lastResult = CheckResult.UpToDate

    /** Einmal pro App-Start. Fehler (kein Netz, Worker weg) bleiben still – dann eben beim nächsten Mal. */
    fun check(context: Context) {
        if (checked || !enabled) return
        checked = true
        val app = context.applicationContext
        scope.launch { look(app) }
    }

    /** Von Hand aus den Einstellungen. */
    suspend fun checkNow(context: Context): CheckResult =
        if (!enabled) CheckResult.UpToDate else look(context.applicationContext)

    private suspend fun look(app: Context): CheckResult = if (storeMode) lookStore(app) else lookSelf(app)

    /** Store-Variante: Atelier kennt die neueste Version. Ohne Atelier gilt „aktuell“ – es gibt ja keinen Weg zum Update. */
    private suspend fun lookStore(app: Context): CheckResult = lock.withLock {
        val latest = withContext(Dispatchers.IO) { AtelierLink.latest(app) }
        if (latest != null && latest.code > currentCode(app)) {
            _state.value = UpdateState.InStore(latest.name)
            _offer.value = true
            CheckResult.Found
        } else {
            _state.value = UpdateState.None
            CheckResult.UpToDate
        }
    }

    private suspend fun lookSelf(app: Context): CheckResult = lock.withLock {
        // Schon geladen, aber „Später“ getippt: wieder anbieten.
        (_state.value as? UpdateState.Ready)?.takeIf { apk(app).exists() }?.let {
            _offer.value = true
            return@withLock CheckResult.Found
        }
        if (_state.value is UpdateState.Downloading) return@withLock CheckResult.Found
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckAt < COOLDOWN_MS) return@withLock lastResult
        lastCheckAt = now
        lastResult = try {
            val info = withContext(Dispatchers.IO) {
                val c = URL(versionUrl).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8_000
                    c.readTimeout = 8_000
                    c.useCaches = false
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
            }
            if (info.getLong("versionCode") <= currentCode(app)) {
                apk(app).delete() // Rest eines früheren Updates
                CheckResult.UpToDate
            } else {
                _state.value = UpdateState.Downloading(info.getString("versionName"), 0f)
                scope.launch { download(app, info) }
                CheckResult.Found
            }
        } catch (e: Exception) {
            // Ohne Netz gleich wieder versuchen dürfen.
            lastCheckAt = -COOLDOWN_MS
            CheckResult.Failed
        }
        lastResult
    }

    private suspend fun download(app: Context, info: JSONObject) {
        runCatching {
            val file = apk(app)
            val version = info.getString("versionName")
            withContext(Dispatchers.IO) {
                val size = info.getLong("size").toFloat()
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                file.outputStream().use { out ->
                    // In Stücken: eine Datei des Workers darf höchstens 25 MB groß sein.
                    val parts = info.getJSONArray("parts")
                    for (i in 0 until parts.length()) {
                        val c = URL(URL(versionUrl), parts.getString(i)).openConnection() as HttpURLConnection
                        try {
                            c.connectTimeout = 15_000
                            c.readTimeout = 30_000
                            c.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    done += n
                                    _state.value = UpdateState.Downloading(version, done / size)
                                }
                            }
                        } finally {
                            c.disconnect()
                        }
                    }
                }
                // Nur anbieten, was vollständig und unverändert angekommen ist.
                if (digest.digest().joinToString("") { "%02x".format(it) } != info.getString("sha256")) {
                    file.delete()
                    error("Prüfsumme stimmt nicht")
                }
            }
            _state.value = UpdateState.Ready(version)
            _offer.value = true
        }.onFailure {
            _state.value = UpdateState.None
            // Abgebrochener Download: die nächste Prüfung soll wirklich neu fragen.
            lastCheckAt = -COOLDOWN_MS
            lastResult = CheckResult.Failed
        }
    }

    /**
     * Öffnet Androids Installationsdialog. Beim ersten Mal fehlt die Erlaubnis „Unbekannte Apps
     * installieren“ – dann führt der Knopf zuerst dorthin, und das Angebot bleibt stehen.
     */
    fun install(context: Context) {
        if (_state.value is UpdateState.InStore) { _offer.value = false; AtelierLink.open(context); return }
        val file = apk(context)
        if (!file.exists()) { _state.value = UpdateState.None; _offer.value = false; return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _offer.value = false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        // ACTION_VIEW statt einer PackageInstaller-Sitzung: so läuft es bei HashRush auf demselben Handy.
        // Der FileProvider „<paket>.files“ muss den Cache-Ordner „update/“ freigeben (res/xml/file_paths.xml).
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun later() { _offer.value = false }

    fun versionName(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "–"
}
