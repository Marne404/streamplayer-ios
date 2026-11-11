package de.marne.streamplayer.library

import android.content.Context
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
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
            pageUrl = url, backdrop = image, description = description,
        )
        _titles.update { listOf(t) + it }
        return t
    }

    /**
     * Titel aus einem Katalog anlegen oder aktualisieren. Wiedererkannt wird über die Katalog-ID,
     * sonst über Art + Titel (+ Jahr). Fortschritt, Downloads und eigenes Cover bleiben erhalten.
     */
    fun upsert(c: CatalogTitle, localPoster: String?, localBackdrop: String?): Pair<LibTitle, Boolean> {
        val kind = if (c.type == CatalogType.SERIES) TitleKind.SERIES else TitleKind.MOVIE
        val existing = _titles.value.firstOrNull { it.sourceId == c.id }
            ?: _titles.value.firstOrNull { it.kind == kind && it.title.equals(c.title, ignoreCase = true) && (c.year == null || it.year == null || it.year == c.year) }
        val firstEpisode = if (kind == TitleKind.SERIES) Episodes.generate(c.template, c.seasons, c.episodes).firstOrNull()?.url else null
        val base = existing ?: LibTitle(id = newId(), kind = kind, title = c.title, pageUrl = null)
        val updated = base.copy(
            kind = kind,
            title = c.title,
            pageUrl = (if (kind == TitleKind.MOVIE) c.url else firstEpisode ?: c.template) ?: base.pageUrl,
            template = c.template,
            seasons = c.seasons,
            overrides = c.episodes,
            poster = localPoster ?: c.poster?.takeIf { !it.startsWith("data:") },
            backdrop = localBackdrop ?: c.backdrop?.takeIf { !it.startsWith("data:") } ?: base.backdrop,
            description = c.description ?: base.description,
            year = c.year ?: base.year,
            tmdbId = c.tmdb?.id ?: base.tmdbId,
            tmdbQuery = c.tmdb?.query,
            sourceId = c.id,
        )
        _titles.update { list -> if (existing != null) list.map { if (it.id == base.id) updated else it } else listOf(updated) + list }
        return updated to (existing != null)
    }

    /** TMDB-Angaben eintragen. Fehlen der Serie Staffeln, kommen sie von TMDB. */
    fun applyTmdb(id: String, info: TmdbInfo, seasons: List<SeasonSpec>?) = update(id) { t ->
        t.copy(
            tmdb = info,
            tmdbId = info.id,
            seasons = if (t.seasons.isEmpty() && seasons != null) seasons else t.seasons,
        )
    }

    fun setEpisodeInfo(id: String, info: Map<String, EpInfo>) = update(id) { it.copy(episodeInfo = it.episodeInfo + info) }

    fun setCustomPoster(id: String, path: String?) = update(id) { it.copy(customPoster = path) }

    /** Fertiger Download ohne Titel in der Mediathek: wird ein Film mit Offline-Datei. */
    fun addOfflineMovie(uri: String, title: String, pageUrl: String?, image: String?, durationMs: Long?): LibTitle {
        _titles.value.firstOrNull { uri in it.offline.values }?.let { return it }
        val id = newId()
        val t = LibTitle(
            id = id, kind = TitleKind.MOVIE, title = title, pageUrl = pageUrl, backdrop = image,
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
            backdrop = t.backdrop ?: image,
            description = t.description ?: description?.takeIf { it.isNotBlank() },
            durationMs = t.durationMs ?: durationMs?.takeIf { t.kind == TitleKind.MOVIE },
        )
    }

    fun rename(id: String, title: String) {
        if (title.isNotBlank()) update(id) { it.copy(title = title.trim()) }
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

    // --- Sync ---

    /**
     * Titel aus dem Konto übernehmen. Downloads bleiben, sie gehören dem Gerät. [replacing]: ein
     * lokales Doppel (gleicher Katalog-Eintrag, andere ID), das durch diesen Titel ersetzt wird.
     */
    fun putRemote(t: LibTitle, replacing: String? = null) = _titles.update { list ->
        val old = list.firstOrNull { it.id == t.id }
        val dup = replacing?.let { r -> list.firstOrNull { it.id == r } }
        val merged = t.copy(offline = (dup?.offline.orEmpty()) + (old?.offline.orEmpty()))
        val rest = list.filterNot { it.id == replacing }
        if (old != null) rest.map { if (it.id == t.id) merged else it }
        else (rest + merged).sortedByDescending { it.addedAt }
    }

    /**
     * Sicherung einspielen. Ersetzen: die Bibliothek ist danach die der Datei (Downloads dieses Geräts
     * bleiben an ihren Titeln). Zusammenführen: Vorhandenes bleibt, Neues kommt dazu; wiedererkannt
     * über ID oder Katalog-ID. Rückgabe: Datei-ID → ID hier, wo sie abweichen (Fortschritt, Listen).
     */
    fun importBackup(incoming: List<LibTitle>, replace: Boolean): Map<String, String> {
        val idMap = mutableMapOf<String, String>()
        // Eigene Cover sind Dateien dieses Geräts: aus einer fremden Sicherung gibt es sie hier nicht.
        fun local(t: LibTitle) = t.copy(customPoster = t.customPoster?.takeIf { java.io.File(it).exists() })
        _titles.update { list ->
            if (replace) {
                incoming.map { t -> local(t).copy(offline = list.firstOrNull { it.id == t.id }?.offline.orEmpty() + t.offline) }
            } else {
                val added = incoming.mapNotNull { t ->
                    val same = list.firstOrNull { it.id == t.id } ?: t.sourceId?.let { sid -> list.firstOrNull { it.sourceId == sid } }
                    if (same != null) { if (same.id != t.id) idMap[t.id] = same.id; null } else local(t)
                }
                (list + added).sortedByDescending { it.addedAt }
            }
        }
        return idMap
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
                backdrop = s("thumbnail"),
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
