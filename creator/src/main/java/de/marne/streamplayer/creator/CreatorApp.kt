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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** Der Katalog, an dem gearbeitet wird – im selben Format, das der Player importiert. */
@OptIn(FlowPreview::class)
class CatalogStore(private val context: Context) {
    private val file = File(context.filesDir, "catalog.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _catalog = MutableStateFlow(load())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    init {
        scope.launch { _catalog.drop(1).debounce(400).collect { save(it) } }
    }

    fun get(id: String): CatalogTitle? = _catalog.value.titles.firstOrNull { it.id == id }

    /** Speichert einen Titel; ändert sich die ID (z. B. durch Umbenennen), wird der alte ersetzt. */
    fun put(t: CatalogTitle, replacingId: String = t.id) = _catalog.update { c ->
        val exists = c.titles.any { it.id == replacingId }
        c.copy(titles = if (exists) c.titles.map { if (it.id == replacingId) t else it } else listOf(t) + c.titles)
    }

    fun remove(id: String) = _catalog.update { c -> c.copy(titles = c.titles.filterNot { it.id == id }) }

    fun rename(name: String) = _catalog.update { it.copy(name = name.ifBlank { null }) }

    /** Fremden Katalog übernehmen: gleiche IDs werden ersetzt, neue angehängt. */
    fun merge(other: Catalog): Int {
        var n = 0
        _catalog.update { c ->
            val byId = c.titles.associateBy { it.id }.toMutableMap()
            other.titles.forEach { byId[it.id] = it; n++ }
            c.copy(titles = byId.values.toList(), name = c.name ?: other.name)
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
        val taken = _catalog.value.titles.map { it.id }.filter { it != except }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    fun json(): String = CatalogJson.write(_catalog.value)

    /** Für die Weitergabe: nur Titel mit Namen. Zweiter Wert: wie viele zurückbleiben. */
    fun exportJson(): Pair<String, Int> {
        val c = _catalog.value
        val ready = c.titles.filter { it.title.isNotBlank() }
        return CatalogJson.write(c.copy(titles = ready)) to (c.titles.size - ready.size)
    }

    private fun load(): Catalog {
        if (!file.exists()) return Catalog(emptyList(), "Mein Katalog")
        return runCatching { CatalogJson.parse(file.readText(), strict = false) }.getOrElse {
            // Nie still verwerfen: die unlesbare Datei beiseitelegen, statt sie beim nächsten Speichern zu überschreiben.
            file.copyTo(File(file.parentFile, "catalog.broken-${System.currentTimeMillis()}.json"), overwrite = true)
            Catalog(emptyList(), "Mein Katalog")
        }
    }

    private fun save(c: Catalog) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(CatalogJson.write(c))
        tmp.renameTo(file)
    }
}

object Share {
    private val players = listOf("de.marne.streamplayer", "de.marne.streamplayer.debug")

    /** Katalog an den Player geben: direkt, wenn er installiert ist, sonst über die Auswahl. */
    fun sendToPlayer(context: Context, json: String, name: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, CatalogJson.slug(name).ifEmpty { "katalog" } + ".json")
        f.writeText(json)
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
            context.startActivity(Intent.createChooser(send, "Katalog senden").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
