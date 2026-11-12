package de.marne.streamplayer.creator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import de.marne.streamplayer.catalog.Catalog
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class CreatorApp : Application() {
    lateinit var store: CatalogStore
        private set
    lateinit var prefs: CreatorPrefs
        private set
    lateinit var tmdb: Tmdb
        private set
    lateinit var imports: ImportSession
        private set
    lateinit var fill: TmdbFill
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Der Creator spricht immer Englisch (auch die gemeinsamen Teile aus core, z. B. „Check for updates“).
        de.marne.streamplayer.ui.Lang.current = de.marne.streamplayer.ui.Lang.Code.EN
        // Selbst-Update: liegt beim Worker unter /creator/, der Player unter /. Im Debug-Build leer.
        de.marne.streamplayer.update.Updater.configure(BuildConfig.UPDATE_URL.ifEmpty { null }?.let { "$it/creator/version.json" }.orEmpty())
        prefs = CreatorPrefs(this)
        store = CatalogStore(this)
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        tmdb = Tmdb(http, key = { prefs.tmdbKey.value }, language = { prefs.language.value })
        imports = ImportSession(this, http)
        fill = TmdbFill(this)
    }

    companion object {
        lateinit var instance: CreatorApp
            private set
    }
}

class CreatorPrefs(context: Context) {
    private val p = context.getSharedPreferences("creator", Context.MODE_PRIVATE)
    private val _tmdbKey = MutableStateFlow(p.getString("tmdb_key", "") ?: "")
    val tmdbKey: StateFlow<String> = _tmdbKey.asStateFlow()
    // TMDB-Sprache: von Hand gesetzt, sonst die des Geräts (de-DE, fr-FR …) – die Oberfläche ist Englisch.
    private val _language = MutableStateFlow(p.getString("language", null) ?: java.util.Locale.getDefault().toLanguageTag())
    val language: StateFlow<String> = _language.asStateFlow()
    private val _theme = MutableStateFlow(p.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    // Übersichts-Import: Pause zwischen zwei Abrufen und Abrufe pro Durchgang.
    private val _crawlPause = MutableStateFlow(p.getLong("crawl_pause", 1000L))
    val crawlPause: StateFlow<Long> = _crawlPause.asStateFlow()
    private val _crawlBudget = MutableStateFlow(p.getInt("crawl_budget", 300))
    val crawlBudget: StateFlow<Int> = _crawlBudget.asStateFlow()

    fun setTmdbKey(v: String) { _tmdbKey.value = v.trim(); p.edit().putString("tmdb_key", v.trim()).apply() }
    fun setCrawlPause(ms: Long) { _crawlPause.value = ms; p.edit().putLong("crawl_pause", ms).apply() }
    fun setCrawlBudget(n: Int) { _crawlBudget.value = n; p.edit().putInt("crawl_budget", n).apply() }
    fun setLanguage(v: String) { _language.value = v.trim(); p.edit().putString("language", v.trim()).apply() }
    fun setTheme(v: String) { _theme.value = v; p.edit().putString("theme", v).apply() }
}

/**
 * Der Katalog, an dem gearbeitet wird – im selben Format, das der Player importiert.
 *
 * Gelesen wird im Hintergrund und Titel für Titel, gespeichert ebenso: Ein Katalog mit 11 000
 * Serien ließ den Creator früher beim Start (Lesen als ein Text auf dem Haupt-Thread) und beim
 * Speichern (ein Text für alles, alle paar Sekunden) aus dem Speicher laufen.
 */
@OptIn(FlowPreview::class)
class CatalogStore(private val context: Context) {
    private val file = File(context.filesDir, "catalog.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _catalog = MutableStateFlow(EMPTY)
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /** false, solange die Datei gelesen wird. Bis dahin wird nichts gespeichert – sonst überschriebe ein leerer Stand den echten. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Beiseitegelegte Stände (unlesbar oder mit kaputten Einträgen) – zum Zurückholen statt still verloren. */
    data class Spares(val files: List<File>, val titles: Int, val newest: Long)
    private val _spares = MutableStateFlow<Spares?>(null)
    val spares: StateFlow<Spares?> = _spares.asStateFlow()

    private val saveLock = Mutex()
    /** Was zuletzt auf der Platte steht (gleiches Objekt = nichts zu tun). */
    @Volatile private var saved: Catalog? = null
    @Volatile private var lastSave = 0L

    init {
        scope.launch {
            val loaded = load()
            // Was schon vor dem Ende des Lesens dazukam (geteilter Link …), bleibt vorn.
            _catalog.update { cur ->
                if (cur.titles.isEmpty()) loaded
                else loaded.copy(titles = cur.titles + loaded.titles.filter { t -> cur.titles.none { it.id == t.id } })
            }
            saved = loaded
            _ready.value = true
            findSpares()
            // Kurz gesammelt speichern; große Kataloge seltener (jedes Speichern schreibt alles).
            _catalog.debounce(400).collectLatest { c ->
                if (c === saved) return@collectLatest
                val gap = if (c.titles.size > 2_000) BIG_GAP_MS else 0L
                val wait = lastSave + gap - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                flush()
            }
        }
    }

    /** Jetzt speichern, falls sich etwas geändert hat (beim Verlassen der App, vor dem Merken einer Linkliste). */
    suspend fun flush() = withContext(NonCancellable + Dispatchers.IO) {
        saveLock.withLock {
            val c = _catalog.value
            if (!_ready.value || c === saved) return@withLock
            if (save(c)) saved = c
            lastSave = System.currentTimeMillis()
        }
    }

    fun flushLater() { scope.launch { flush() } }

    fun get(id: String): CatalogTitle? = _catalog.value.titles.firstOrNull { it.id == id }

    /** Speichert einen Titel; ändert sich die ID (z. B. durch Umbenennen), wird der alte ersetzt. */
    fun put(t: CatalogTitle, replacingId: String = t.id) = _catalog.update { c ->
        val i = c.titles.indexOfFirst { it.id == replacingId }
        c.copy(titles = if (i >= 0) c.titles.toMutableList().also { it[i] = t } else listOf(t) + c.titles)
    }

    fun remove(id: String) = _catalog.update { c -> c.copy(titles = c.titles.filterNot { it.id == id }) }

    fun rename(name: String) = _catalog.update { it.copy(name = name.ifBlank { null }) }

    /** Fremden Katalog übernehmen: gleiche IDs werden ersetzt, neue angehängt. */
    fun merge(other: Catalog): Int {
        var n = 0
        _catalog.update { c ->
            val byId = c.titles.associateBy { it.id }.toMutableMap()
            n = 0
            other.titles.forEach { byId[it.id] = it; n++ }
            c.copy(titles = byId.values.toList(), name = c.name ?: other.name)
        }
        return n
    }

    /** Nur hinzufügen, was fehlt – beim Zurückholen eines alten Stands gewinnt, was seitdem bearbeitet wurde. */
    private fun addMissing(other: Catalog): Int {
        var n = 0
        _catalog.update { c ->
            val have = c.titles.mapTo(HashSet()) { it.id }
            val extra = other.titles.filter { it.id !in have }
            n = extra.size
            c.copy(titles = c.titles + extra, name = c.name ?: other.name)
        }
        return n
    }

    /**
     * Vorläufige ID („neue-serie“) beim Verlassen des Editors durch eine aus dem Namen ersetzen.
     * Nicht beim Tippen: ein Identitätswechsel mitten in der Eingabe schluckt Zeichen.
     */
    fun finalizeId(id: String): String {
        val t = get(id) ?: return id
        // Angelegt, aber nichts eingetragen: wieder weg, statt als „Ohne Namen“ liegenzubleiben.
        if (t.title.isBlank() && t.url == null && t.template == null && t.seasons.isEmpty() && t.tmdb == null) {
            remove(id)
            return id
        }
        if (!Regex("^(neue-serie|neuer-film)(-\\d+)?$").matches(t.id) || t.title.isBlank()) return id
        val nid = uniqueId(CatalogJson.slug(t.title, t.year), except = t.id)
        put(t.copy(id = nid), replacingId = t.id)
        return nid
    }

    /** Eine noch nicht vergebene ID. */
    fun uniqueId(base: String, except: String? = null): String {
        val taken = _catalog.value.titles.mapTo(HashSet()) { it.id }.apply { except?.let { remove(it) } }
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    /** Für die Weitergabe: nur Titel mit Namen. Zweiter Wert: wie viele zurückbleiben. */
    fun forExport(): Pair<Catalog, Int> {
        val c = _catalog.value
        val ready = c.titles.filter { it.title.isNotBlank() }
        return c.copy(titles = ready) to (c.titles.size - ready.size)
    }

    // ---------- Beiseitegelegte Stände ----------

    private fun spareFiles() = context.filesDir.listFiles { f -> f.name.startsWith("catalog.broken-") && f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }

    private fun findSpares() {
        val files = spareFiles()
        if (files.isEmpty()) { _spares.value = null; return }
        // Gezählt wird der größte Stand – mehrere Kopien sind meist dieselbe Datei aus mehreren Startversuchen.
        val most = files.maxOf { f -> runCatching { f.bufferedReader().use { CatalogJson.read(it, strict = false, partial = true).first.titles.size } }.getOrDefault(0) }
        _spares.value = Spares(files, most, files.first().lastModified())
    }

    /** Fehlendes aus allen beiseitegelegten Ständen übernehmen, danach die Kopien löschen. Rückgabe: wie viele Titel dazukamen. */
    suspend fun restoreSpares(): Int = withContext(Dispatchers.IO) {
        var n = 0
        val files = spareFiles()
        for (f in files) {
            val c = runCatching { f.bufferedReader().use { CatalogJson.read(it, strict = false, partial = true).first } }.getOrNull() ?: continue
            n += addMissing(c)
        }
        // Erst sicher gespeichert, dann die Kopien weg.
        flush()
        if (saved === _catalog.value) files.forEach { it.delete() }
        findSpares()
        n
    }

    suspend fun deleteSpares() = withContext(Dispatchers.IO) {
        spareFiles().forEach { it.delete() }
        findSpares()
    }

    // ---------- Datei ----------

    private fun load(): Catalog {
        if (!file.exists()) {
            // Abgebrochenes Speichern: die fertige Zwischendatei ist dann der neueste Stand.
            val tmp = File(file.parentFile, file.name + ".tmp")
            if (!tmp.exists() || tmp.length() == 0L || !tmp.renameTo(file)) return EMPTY
        }
        return try {
            val (c, problems) = file.bufferedReader().use { CatalogJson.read(it, strict = false, skipBroken = true, partial = true) }
            // Einzelne kaputte Einträge: der Rest wird gelesen, die Datei bleibt als Kopie – das nächste Speichern ließe sie sonst weg.
            if (problems.isNotEmpty()) file.copyTo(spareFile(), overwrite = true)
            c
        } catch (e: Throwable) {
            // Auch OutOfMemoryError: nie still verwerfen. Umbenennen statt kopieren – kostet keinen Platz,
            // und das nächste Speichern kann die Datei nicht mehr überschreiben.
            if (!file.renameTo(spareFile())) file.copyTo(spareFile(), overwrite = true)
            EMPTY
        }
    }

    private fun spareFile() = File(file.parentFile, "catalog.broken-${System.currentTimeMillis()}.json")

    private fun save(c: Catalog): Boolean = try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered(256 * 1024).writer(Charsets.UTF_8).use { CatalogJson.write(c, it) }
        tmp.renameTo(file)
    } catch (e: Exception) {
        // Platte voll o. Ä.: alter Stand bleibt, beim nächsten Mal wieder versuchen.
        android.util.Log.w("CatalogStore", "save failed", e)
        false
    }

    companion object {
        private val EMPTY = Catalog(emptyList(), "My catalog")
        /** Große Kataloge: höchstens so oft speichern (ein Import ändert alle paar Sekunden etwas). */
        private const val BIG_GAP_MS = 15_000L
    }
}

object Share {
    private val players = listOf("de.marne.streamplayer", "de.marne.streamplayer.debug")

    /**
     * Katalog an den Player geben: direkt, wenn er installiert ist, sonst über die Auswahl.
     * Die Datei wird Titel für Titel geschrieben (große Kataloge passen nicht als ein Text in den Speicher).
     */
    suspend fun sendToPlayer(context: Context, catalog: Catalog, name: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, CatalogJson.slug(name).ifEmpty { "katalog" } + ".json")
        kotlinx.coroutines.withContext(Dispatchers.IO) { f.bufferedWriter().use { CatalogJson.write(catalog, it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installed = players.firstOrNull { p -> runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess }
        if (installed != null) {
            send.setPackage(installed)
            context.grantUriPermission(installed, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(Intent.createChooser(send, "Send catalog").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun playerInstalled(context: Context) = players.any { p -> runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess }

    /**
     * Eigenes Bild in den Katalog einbetten: verkleinert, als data:-URI. Übersteht so jede Weitergabe.
     * 1000×1500 statt früher 500×750: ein Poster wird im Player auch über die volle Breite gezeigt
     * (Hero, Detailseite) und war dort sichtbar unscharf. Kostet je Cover etwa 200–300 KB im Katalog.
     */
    fun embedImage(context: Context, uri: Uri, maxW: Int = 1000, maxH: Int = 1500): String? = runCatching {
        // Erst Maße, dann grob vorverkleinert dekodieren – ein großes Kamerafoto sprengt sonst den Speicher.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = minOf(1f, maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun openInBrowser(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
