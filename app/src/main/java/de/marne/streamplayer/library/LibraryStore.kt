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
import java.io.File
import java.util.UUID

/** Die Mediathek: Filme und Serien, als JSON in filesDir. */
@OptIn(FlowPreview::class)
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "titles.json")
    private val legacy = File(context.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _titles = MutableStateFlow(load())
    val titles: StateFlow<List<LibTitle>> = _titles.asStateFlow()

    init {
        scope.launch { _titles.drop(1).debounce(400).collect { save(it) } }
        if (!file.exists() && _titles.value.isNotEmpty()) scope.launch { save(_titles.value) }
    }

    fun get(id: String): LibTitle? = _titles.value.firstOrNull { it.id == id }

    fun findByPage(url: String): LibTitle? {
        val key = UrlClassifier.key(url)
        return _titles.value.firstOrNull { it.pageUrl != null && UrlClassifier.key(it.pageUrl) == key }
    }

    fun containsLink(url: String) = findByPage(url) != null

    fun addMovie(url: String, title: String?, image: String?, description: String? = null): LibTitle {
        findByPage(url)?.let { existing ->
            enrich(existing.id, title, image, description)
            return existing
        }
        val t = LibTitle(
            id = newId(), kind = TitleKind.MOVIE,
            title = title?.takeIf { it.isNotBlank() } ?: hostOf(url),
            pageUrl = url, image = image, description = description,
        )
        _titles.update { listOf(t) + it }
        return t
    }

    fun addSeries(title: String, template: String, seasons: List<Int>, image: String?, description: String?): LibTitle {
        val t = LibTitle(
            id = newId(), kind = TitleKind.SERIES, title = title.trim(),
            pageUrl = Pattern.fill(template, 1, 1), template = template, seasons = seasons,
            image = image, description = description,
        )
        _titles.update { listOf(t) + it }
        return t
    }

    /** Fertiger Download ohne Titel in der Mediathek: wird ein Film mit Offline-Datei. */
    fun addOfflineMovie(uri: String, title: String, pageUrl: String?, image: String?, durationMs: Long?): LibTitle {
        _titles.value.firstOrNull { uri in it.offline.values }?.let { return it }
        val id = newId()
        val t = LibTitle(
            id = id, kind = TitleKind.MOVIE, title = title, pageUrl = pageUrl, image = image,
            durationMs = durationMs, offline = mapOf("m:$id" to uri),
        )
        _titles.update { listOf(t) + it }
        return t
    }

    /** Download fertig: Datei an Film oder Folge hängen. */
    fun attachOffline(key: String, uri: String) {
        val id = Keys.parse(key)?.first ?: return
        update(id) { it.copy(offline = it.offline + (key to uri)) }
    }

    fun enrich(id: String, title: String?, image: String?, description: String? = null, durationMs: Long? = null) = update(id) { t ->
        t.copy(
            title = if ((t.title == hostOf(t.pageUrl.orEmpty()) || t.title.isBlank()) && !title.isNullOrBlank()) title else t.title,
            image = t.image ?: image,
            description = t.description ?: description?.takeIf { it.isNotBlank() },
            durationMs = t.durationMs ?: durationMs?.takeIf { t.kind == TitleKind.MOVIE },
        )
    }

    fun rename(id: String, title: String) {
        if (title.isNotBlank()) update(id) { it.copy(title = title.trim()) }
    }

    fun updateSeries(id: String, template: String, seasons: List<Int>) = update(id) {
        it.copy(template = template, seasons = seasons, pageUrl = Pattern.fill(template, 1, 1))
    }

    fun remove(id: String) = _titles.update { list -> list.filterNot { it.id == id } }

    fun removeLink(url: String) {
        val key = UrlClassifier.key(url)
        _titles.update { list -> list.filterNot { it.kind == TitleKind.MOVIE && it.pageUrl != null && UrlClassifier.key(it.pageUrl) == key && it.offline.isEmpty() } }
    }

    /** Download gelöscht: Verweis entfernen; ein Film, der nur aus dieser Datei bestand, verschwindet. */
    fun removeFile(uri: String) = _titles.update { list ->
        list.mapNotNull { t ->
            if (uri !in t.offline.values) t
            else {
                val rest = t.offline.filterValues { it != uri }
                if (t.kind == TitleKind.MOVIE && t.pageUrl == null && rest.isEmpty()) null else t.copy(offline = rest)
            }
        }
    }

    private fun update(id: String, change: (LibTitle) -> LibTitle) =
        _titles.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun newId() = UUID.randomUUID().toString()

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    private fun load(): List<LibTitle> = runCatching {
        if (file.exists()) {
            val arr = JSONArray(file.readText())
            List(arr.length()) { LibTitle.fromJson(arr.getJSONObject(it)) }
        } else migrate()
    }.getOrDefault(emptyList())

    /** Bibliothek bis 0.5 (Links und Dateien) wird zu Filmen. */
    private fun migrate(): List<LibTitle> {
        if (!legacy.exists()) return emptyList()
        val arr = JSONArray(legacy.readText())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val isFile = o.optString("kind") == "FILE"
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
            LibTitle(
                id = id, kind = TitleKind.MOVIE, title = o.optString("title"),
                pageUrl = if (isFile) s("pageUrl") else o.getString("url"),
                image = s("thumbnail"),
                durationMs = if (o.isNull("durationMs") || !o.has("durationMs")) null else o.optLong("durationMs"),
                offline = if (isFile) mapOf("m:$id" to o.getString("url")) else emptyMap(),
                addedAt = o.optLong("addedAt"),
            )
        }
    }

    private fun save(list: List<LibTitle>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
