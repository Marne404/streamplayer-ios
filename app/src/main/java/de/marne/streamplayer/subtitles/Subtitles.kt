package de.marne.streamplayer.subtitles

import de.marne.streamplayer.ui.locale

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import de.marne.streamplayer.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Eine Untertitel-Datei: auf der Seite gefunden (http), selbst gewählt oder mit einem Download
 * gesichert (Datei hier). [lang]: Sprachcode, wenn bekannt; [label]: Name für die Auswahl.
 */
// SubTrack selbst liegt im gemeinsamen Modul :ui; hier die Android-Teile mit gleichen Namen.

/** Format aus der Endung; ohne erkennbare Endung WebVTT – das ist im Netz bei Weitem am häufigsten. */
val SubTrack.mime: String get() = Subtitles.mimeFor(url)

val SubTrack.name: String get() = label?.takeIf { it.isNotBlank() }
    ?: lang?.let { runCatching { Locale.forLanguageTag(it).getDisplayLanguage(de.marne.streamplayer.ui.Lang.locale).takeIf(String::isNotBlank) }.getOrNull() }
    ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "Untertitel" }

fun SubTrack.toJson(): JSONObject = JSONObject().put("url", url).put("lang", lang).put("label", label).put("referer", referer)

/** Für den Player. [default]: gleich einschalten (selbst gewählte Datei). */
fun SubTrack.toConfiguration(default: Boolean = false): MediaItem.SubtitleConfiguration {
    val uri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)
    return MediaItem.SubtitleConfiguration.Builder(uri)
        .setMimeType(mime)
        .setLanguage(lang)
        .setLabel(name)
        .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

fun SubTrack.Companion.fromJson(o: JSONObject) = SubTrack(
    o.getString("url"),
    o.optString("lang").takeIf { it.isNotBlank() && it != "null" },
    o.optString("label").takeIf { it.isNotBlank() && it != "null" },
    o.optString("referer").takeIf { it.isNotBlank() && it != "null" },
)

fun SubTrack.Companion.listToJson(l: List<SubTrack>) = JSONArray().apply { l.forEach { put(it.toJson()) } }
fun SubTrack.Companion.listFromJson(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { fromJson(a.getJSONObject(it)) }

object Subtitles {
    private val exts = setOf("vtt", "webvtt", "srt", "ass", "ssa", "ttml", "dfxp")

    fun extension(url: String): String = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Sieht die Adresse nach einer Untertitel-Datei aus? */
    fun looksLike(url: String): Boolean {
        if (extension(url) !in exts) return false
        // Teilstücke von HLS-Untertiteln heißen „0.vtt“, „fileSequence12.webvtt“ – keine ganzen Dateien.
        val name = url.substringBefore('?').substringAfterLast('/')
        return !Regex("""^\d+\.|filesequence\d+""", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }

    fun mimeFor(url: String): String = when (extension(url)) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }

    fun dir(context: Context) = File(context.filesDir, "subs").apply { mkdirs() }

    /**
     * Gefundene Untertitel neben einen Download legen (mit Referer und Cookies der Seite – ohne
     * die liefern viele Server nichts). Was nicht kommt, fehlt eben; der Download zählt trotzdem.
     */
    fun fetch(context: Context, http: OkHttpClient, subs: List<SubTrack>, prefix: String, userAgent: String?): List<SubTrack> =
        subs.mapIndexedNotNull { i, s ->
            runCatching {
                val req = Request.Builder().url(s.url).apply {
                    s.referer?.let { header("Referer", it) }
                    userAgent?.let { header("User-Agent", it) }
                    Http.cookiesFor(s.url)?.let { header("Cookie", it) }
                }.build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    val bytes = r.body?.bytes() ?: return@runCatching null
                    if (bytes.isEmpty() || bytes.size > 5_000_000) return@runCatching null
                    val ext = extension(s.url).takeIf { it in exts } ?: "vtt"
                    val f = File(dir(context), "$prefix-$i.$ext")
                    f.writeBytes(bytes)
                    s.copy(url = f.absolutePath, referer = null)
                }
            }.getOrNull()
        }
}

/**
 * Untertitel-Dateien auf dem Gerät, je Film/Folge (Schlüssel aus Keys) oder je Datei-Adresse
 * (Videos ohne Bibliothekseintrag). Liegt als JSON in filesDir; die Dateien selbst in „subs/“.
 */
class SubtitleStore(private val context: Context) {
    private val file = File(context.filesDir, "subtitles.json")
    private val map = MutableStateFlow(load())

    fun forKeys(vararg keys: String?): List<SubTrack> =
        keys.filterNotNull().flatMap { map.value[it].orEmpty() }.distinctBy { it.url }.filter { File(it.url).exists() }

    fun add(key: String, subs: List<SubTrack>) {
        if (subs.isEmpty()) return
        map.update { m -> m + (key to (m[key].orEmpty() + subs).distinctBy { it.url }) }
        save()
    }

    /** Video gelöscht: seine Untertitel gehen mit (nur Dateien, die kein anderer Eintrag nutzt). */
    fun removeFor(vararg keys: String?) {
        val ks = keys.filterNotNull().toSet()
        val gone = ks.flatMap { map.value[it].orEmpty() }
        map.update { m -> m - ks }
        val still = map.value.values.flatten().map { it.url }.toSet()
        gone.filter { it.url !in still }.forEach { File(it.url).delete() }
        save()
    }

    /** Selbst gewählte Datei ins eigene Verzeichnis kopieren – die Freigabe des Dateiwählers hält nicht ewig. */
    fun import(key: String, uri: Uri, name: String?): SubTrack? = runCatching {
        val display = name ?: uri.lastPathSegment ?: "untertitel.srt"
        val ext = Subtitles.extension(display).ifBlank { "srt" }
        val f = File(Subtitles.dir(context), "own-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        SubTrack(f.absolutePath, label = display.substringBeforeLast('.')).also { add(key, listOf(it)) }
    }.getOrNull()

    private fun load(): Map<String, List<SubTrack>> = runCatching {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { SubTrack.listFromJson(o.getJSONArray(it)) }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save() {
        val o = JSONObject()
        map.value.forEach { (k, l) -> o.put(k, SubTrack.listToJson(l)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
