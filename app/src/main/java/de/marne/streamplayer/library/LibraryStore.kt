package de.marne.streamplayer.library

import android.content.Context
import de.marne.streamplayer.detect.UrlClassifier
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class LibraryKind { LINK, FILE }

/**
 * Ein Eintrag der Bibliothek.
 * LINK: [url] ist die Seite; Abspielen läuft über „Nur ansehen“ (Stream-Adressen verfallen, Seiten nicht).
 * FILE: [url] ist die content://-Adresse eines fertigen Downloads; [pageUrl] die Herkunft.
 */
data class LibraryItem(
    val id: String,
    val kind: LibraryKind,
    val title: String,
    val url: String,
    val pageUrl: String?,
    val thumbnail: String?,
    val durationMs: Long?,
    val mime: String?,
    val addedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("title", title); put("url", url)
        put("pageUrl", pageUrl); put("thumbnail", thumbnail); put("durationMs", durationMs)
        put("mime", mime); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = LibraryItem(
            id = o.getString("id"),
            kind = LibraryKind.valueOf(o.getString("kind")),
            title = o.optString("title"),
            url = o.getString("url"),
            pageUrl = o.optString("pageUrl").takeIf { !o.isNull("pageUrl") && it.isNotEmpty() },
            thumbnail = o.optString("thumbnail").takeIf { !o.isNull("thumbnail") && it.isNotEmpty() },
            durationMs = if (o.isNull("durationMs") || !o.has("durationMs")) null else o.optLong("durationMs"),
            mime = o.optString("mime").takeIf { !o.isNull("mime") && it.isNotEmpty() },
            addedAt = o.optLong("addedAt"),
        )
    }
}

/** Die Bibliothek als JSON in filesDir, wie die Download-Liste. */
@OptIn(FlowPreview::class)
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    init {
        scope.launch { _items.drop(1).debounce(500).collect { save(it) } }
    }

    fun containsLink(url: String): Boolean = find(url) != null

    private fun find(url: String): LibraryItem? {
        val key = UrlClassifier.key(url)
        return _items.value.firstOrNull { UrlClassifier.key(it.url) == key }
    }

    /** Link merken; gibt es ihn schon, werden nur fehlende Angaben ergänzt. */
    fun addLink(url: String, title: String?, thumbnail: String?) {
        if (find(url) != null) {
            enrich(url, title, thumbnail)
            return
        }
        val item = LibraryItem(
            id = UUID.randomUUID().toString(),
            kind = LibraryKind.LINK,
            title = title?.takeIf { it.isNotBlank() } ?: hostOf(url),
            url = url,
            pageUrl = url,
            thumbnail = thumbnail,
            durationMs = null,
            mime = null,
            addedAt = System.currentTimeMillis(),
        )
        _items.update { listOf(item) + it }
    }

    fun addFile(uri: String, title: String, pageUrl: String?, thumbnail: String?, durationMs: Long?, mime: String?) {
        if (find(uri) != null) return
        val item = LibraryItem(UUID.randomUUID().toString(), LibraryKind.FILE, title, uri, pageUrl, thumbnail, durationMs, mime, System.currentTimeMillis())
        _items.update { listOf(item) + it }
    }

    /** Fehlende Titel/Vorschaubilder nachtragen, z. B. nach dem ersten Ansehen. */
    fun enrich(url: String, title: String?, thumbnail: String?, durationMs: Long? = null) {
        val key = UrlClassifier.key(url)
        _items.update { list ->
            list.map { item ->
                if (UrlClassifier.key(item.url) != key) item
                else item.copy(
                    title = if (item.title == hostOf(item.url) && !title.isNullOrBlank()) title else item.title,
                    thumbnail = item.thumbnail ?: thumbnail,
                    durationMs = item.durationMs ?: durationMs,
                )
            }
        }
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        _items.update { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun removeLink(url: String) {
        val key = UrlClassifier.key(url)
        _items.update { list -> list.filterNot { it.kind == LibraryKind.LINK && UrlClassifier.key(it.url) == key } }
    }

    /** Download gelöscht: Kachel der Datei mit entfernen, sonst zeigt sie ins Leere. */
    fun removeFile(uri: String) {
        _items.update { list -> list.filterNot { it.kind == LibraryKind.FILE && it.url == uri } }
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    private fun load(): List<LibraryItem> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { LibraryItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<LibraryItem>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
