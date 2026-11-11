package de.marne.streamplayer.library

import android.content.Context
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.singleEpisodeAsMovie
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

    private val libFile = File(context.filesDir, "libraries.json")
    private val _libraries = MutableStateFlow(loadLibraries())
    /** Alle Bibliotheken, [Library.MAIN] immer zuerst. */
    val libraries: StateFlow<List<Library>> = _libraries.asStateFlow()

    /**
     * Wohin neue Titel kommen (Browser, „In Bibliothek“, Katalog ohne eigene Wahl): die gerade
     * gezeigte Bibliothek – setzt AppRoot. Bei „Alle“ die erste.
     */
    @Volatile var target: String = Library.MAIN

    init {
        scope.launch { _titles.drop(1).debounce(400).collect { save(it) } }
        scope.launch { _libraries.drop(1).collect { saveLibraries(it) } }
        if (!file.exists() && _titles.value.isNotEmpty()) scope.launch { save(_titles.value) }
    }

    fun get(id: String): LibTitle? = _titles.value.firstOrNull { it.id == id }

    // --- Bibliotheken ---

    // In eine Bibliothek eines Freundes kommt nichts: dorthin schreibt nur er.
    private fun newTarget(): String? = target.takeIf { it != Library.MAIN && it != Library.ALL && _libraries.value.any { l -> l.id == it && !l.joined } }

    fun createLibrary(name: String, color: Int): Library {
        val l = Library(newId(), name.trim(), color)
        _libraries.update { it + l }
        return l
    }

    fun updateLibrary(id: String, name: String, color: Int) =
        _libraries.update { list -> list.map { if (it.id == id) it.copy(name = if (id == Library.MAIN && name.isBlank()) "" else name.trim(), color = color) else it } }

    /**
     * Bibliothek weg; ihre Titel gehen nach [moveTo] (Standard: die erste) – gelöscht wird kein Titel.
     * Steht ein Titel noch in einer anderen Bibliothek, bleibt er nur dort.
     */
    fun deleteLibrary(id: String, moveTo: String = Library.MAIN) {
        if (id == Library.MAIN) return
        val to = moveTo.takeIf { it != id && it != Library.MAIN }
        _titles.update { list ->
            list.map { t ->
                when {
                    t.libraryId == id -> {
                        val rest = t.alsoIn - id
                        if (rest.isEmpty()) t.copy(libraryId = to)
                        else t.copy(libraryId = rest.first().takeIf { it != Library.MAIN }, alsoIn = rest.drop(1))
                    }
                    id in t.alsoIn -> t.copy(alsoIn = t.alsoIn - id)
                    else -> t
                }
            }
        }
        _libraries.update { list -> list.filterNot { it.id == id } }
    }

    fun setLibrary(l: Library) = _libraries.update { list -> if (list.any { it.id == l.id }) list.map { if (it.id == l.id) l else it } else list + l }

    /** Titel aus der Bibliothek eines Freundes übernehmen; eigene Downloads daran bleiben. */
    fun putShared(sid: String, t: LibTitle) = _titles.update { list ->
        val old = list.firstOrNull { it.id == t.id }
        val nt = t.copy(libraryId = sid, alsoIn = emptyList(), offline = old?.offline.orEmpty(), customPoster = t.customPoster?.takeIf { it.startsWith("http") })
        if (old != null) list.map { if (it.id == t.id) nt.copy(tmdb = nt.tmdb ?: old.tmdb, episodeInfo = old.episodeInfo + nt.episodeInfo) else it } else listOf(nt) + list
    }

    fun removeShared(sid: String, titleId: String) = _titles.update { list -> list.filterNot { it.id == titleId && it.libraryId == sid } }

    /** Freundes-Bibliothek verlassen oder zurückgezogen: sie und ihre Titel sind weg. */
    fun removeJoined(sid: String) {
        _titles.update { list -> list.filterNot { it.libraryId == sid } }
        _libraries.update { list -> list.filterNot { it.id == sid } }
    }

    fun moveTitle(id: String, libraryId: String) = setLibraries(id, listOf(libraryId))

    /**
     * In genau diesen Bibliotheken soll der Titel stehen. Mindestens eine bleibt; die bisherige
     * eigentliche Bibliothek bleibt die eigentliche, solange sie dabei ist. Bibliotheken von
     * Freunden zählen nicht – dorthin schreibt nur der Freund.
     */
    fun setLibraries(id: String, libs: List<String>) {
        val own = _libraries.value.filterNot { it.joined }.mapTo(HashSet()) { it.id }
        val wanted = libs.distinct().filter { it in own }
        if (wanted.isEmpty()) return
        update(id) { t ->
            val home = if (t.library in wanted) t.library else wanted.first()
            t.copy(libraryId = home.takeIf { it != Library.MAIN }, alsoIn = wanted - home)
        }
    }

    /** Abgleich: Bibliothek aus dem Konto übernehmen bzw. entfernen. */
    fun putRemoteLibrary(l: Library) = _libraries.update { list ->
        if (list.any { it.id == l.id }) list.map { if (it.id == l.id) l else it } else list + l
    }

    fun removeRemoteLibrary(id: String) {
        if (id == Library.MAIN) return
        _libraries.update { list -> list.filterNot { it.id == id } }
    }

    /** Sicherung: Bibliotheken übernehmen (Ersetzen: genau die der Datei, MAIN bleibt immer). */
    fun importLibraries(incoming: List<Library>, replace: Boolean) = _libraries.update { list ->
        val merged = if (replace) incoming else list + incoming.filter { n -> list.none { it.id == n.id } }
        withMain(merged)
    }

    private fun withMain(list: List<Library>): List<Library> {
        val main = list.firstOrNull { it.id == Library.MAIN } ?: Library(Library.MAIN, "")
        return listOf(main) + list.filter { it.id != Library.MAIN }.distinctBy { it.id }
    }

    private fun loadLibraries(): List<Library> = withMain(runCatching {
        if (!libFile.exists()) return@runCatching emptyList()
        val a = JSONArray(libFile.readText())
        List(a.length()) { Library.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList()))

    private fun saveLibraries(list: List<Library>) {
        val tmp = File(libFile.parentFile, libFile.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(libFile)
    }

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
            pageUrl = url, backdrop = image, description = description, libraryId = newTarget(),
        )
        _titles.update { listOf(t) + it }
        return t
    }

    /** Wie ein Katalog-Titel hier ankam. */
    enum class Upserted {
        NEW, UPDATED,
        /** Stand schon in einer anderen Bibliothek (oder bei einem Freund): Angaben von dort übernommen, nichts neu gesucht. */
        ADOPTED,
    }

    /**
     * Titel aus einem Katalog anlegen oder aktualisieren. Wiedererkannt wird über die Katalog-ID,
     * sonst über Art + Titel (+ Jahr). Fortschritt, Downloads und eigenes Cover bleiben erhalten.
     *
     * Steht der Titel schon in einer anderen eigenen Bibliothek, kommt die gewählte dazu – derselbe
     * Titel mit Cover, TMDB-Angaben, Folgen, Fortschritt und Downloads, ohne neue Suche. Steht er
     * nur in der Bibliothek eines Freundes, entsteht ein eigener, der dessen TMDB-Angaben mitnimmt.
     */
    fun upsert(c0: CatalogTitle, localPoster: String?, localBackdrop: String?, libraryId: String? = null): Pair<LibTitle, Upserted> {
        // Ein-Folgen-„Serie“ ohne TMDB-Verknüpfung ist ein Film (siehe singleEpisodeAsMovie).
        val c = if (c0.tmdb == null) c0.singleEpisodeAsMovie() else c0
        val kind = if (c.type == CatalogType.SERIES) TitleKind.SERIES else TitleKind.MOVIE
        val libs = _libraries.value
        val joined = libs.filter { it.joined }.mapTo(HashSet()) { it.id }
        val all = _titles.value
        fun sameTitle(t: LibTitle) = t.kind == kind && t.title.equals(c.title, ignoreCase = true) && (c.year == null || t.year == null || t.year == c.year)
        // Titel von Freunden sind nur geliehen: nie überschreiben, höchstens als Vorlage nehmen.
        val existing = all.firstOrNull { it.sourceId == c.id && it.library !in joined }
            ?: all.firstOrNull { it.library !in joined && sameTitle(it) }
        val donor = if (existing != null) null else
            (all.firstOrNull { it.sourceId == c.id && it.library in joined } ?: all.firstOrNull { it.library in joined && sameTitle(it) })
                ?.takeIf { it.kind == kind }
        val firstEpisode = if (kind == TitleKind.SERIES) Episodes.generate(c.template, c.seasons, c.episodes).firstOrNull()?.url else null
        // Gewählte Bibliothek (bei „Alle“ keine bestimmte: Neues kommt in die erste).
        val dest = (libraryId ?: target).takeIf { it == Library.MAIN || libs.any { l -> l.id == it && !l.joined } }
        val base = existing ?: LibTitle(
            id = newId(), kind = kind, title = c.title, pageUrl = null,
            libraryId = dest?.takeIf { it != Library.MAIN },
            tmdb = donor?.tmdb, tmdbId = donor?.tmdbId, episodeInfo = donor?.episodeInfo.orEmpty(), durationMs = donor?.durationMs,
        )
        // Schon da, aber woanders: Er bleibt dort und steht nun auch in der gewählten.
        val adopt = existing != null && dest != null && !existing.isIn(dest)
        // War er bisher eine (Ein-Folgen-)Serie und ist jetzt ein Film: Folgen-Angaben, TMDB-Serie und
        // Folgen-Schlüssel gehören nicht mehr dazu.
        val toMovie = existing != null && existing.kind == TitleKind.SERIES && kind == TitleKind.MOVIE
        val updated = (if (toMovie) base.copy(
            tmdb = null, tmdbId = null, episodeInfo = emptyMap(),
            offline = base.offline.mapKeys { (k, _) -> if (k.startsWith("e:")) "m:${base.id}" else k },
        ) else base).copy(
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
            tmdbId = c.tmdb?.id ?: if (toMovie) null else base.tmdbId,
            tmdbQuery = c.tmdb?.query,
            sourceId = c.id,
            alsoIn = if (adopt) base.alsoIn + dest!! else base.alsoIn,
        )
        _titles.update { list -> if (existing != null) list.map { if (it.id == base.id) updated else it } else listOf(updated) + list }
        if (toMovie) de.marne.streamplayer.App.instance.progress.episodeToMovie(base.id)
        val how = when {
            adopt || donor?.tmdb != null -> Upserted.ADOPTED
            existing != null -> Upserted.UPDATED
            else -> Upserted.NEW
        }
        return updated to how
    }

    /** TMDB-Angaben eintragen. Fehlen der Serie Staffeln, kommen sie von TMDB. */
    fun applyTmdb(id: String, info: TmdbInfo, seasons: List<SeasonSpec>?) = update(id) { t ->
        t.copy(
            tmdb = info,
            tmdbId = info.id,
            seasons = if (t.seasons.isEmpty() && seasons != null) seasons else t.seasons,
        )
    }

    /**
     * Einmalig für ältere Importe: „Serien“ mit genau einer Folge und ohne TMDB-Treffer werden zu
     * Filmen (Seite = die der Folge). Rückgabe: die IDs – deren Fortschritt muss mitziehen, und
     * TMDB soll sie nun als Film suchen.
     */
    fun fixSingleEpisodeSeries(): List<String> {
        val fixed = mutableListOf<String>()
        _titles.update { list ->
            list.map { t ->
                if (t.kind != TitleKind.SERIES || t.tmdb != null) return@map t
                val eps = t.episodes()
                if (eps.size != 1) return@map t
                val e = eps.single()
                fixed += t.id
                t.copy(
                    kind = TitleKind.MOVIE, pageUrl = e.url, template = null, seasons = emptyList(), overrides = emptyList(),
                    episodeInfo = emptyMap(), tmdbId = null,
                    offline = t.offline.mapKeys { (k, _) -> if (k.startsWith("e:")) "m:${t.id}" else k },
                )
            }
        }
        return fixed
    }

    /** Von Hand gewählter TMDB-Eintrag. Anderer Eintrag: Folgen-Angaben des alten weg, sie gehören zu ihm. */
    fun setTmdbMatch(id: String, tmdbId: Int) = update(id) { t ->
        if (t.tmdbId == tmdbId) t else t.copy(tmdbId = tmdbId, episodeInfo = emptyMap())
    }

    fun setEpisodeInfo(id: String, info: Map<String, EpInfo>) = update(id) { it.copy(episodeInfo = it.episodeInfo + info) }

    fun setCustomPoster(id: String, path: String?) = update(id) { it.copy(customPoster = path) }

    /** Fertiger Download ohne Titel in der Mediathek: wird ein Film mit Offline-Datei. */
    fun addOfflineMovie(uri: String, title: String, pageUrl: String?, image: String?, durationMs: Long?): LibTitle {
        _titles.value.firstOrNull { uri in it.offline.values }?.let { return it }
        val id = newId()
        val t = LibTitle(
            id = id, kind = TitleKind.MOVIE, title = title, pageUrl = pageUrl, backdrop = image,
            durationMs = durationMs, offline = mapOf("m:$id" to uri), libraryId = newTarget(),
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
        fun local(t: LibTitle) = t.copy(customPoster = t.customPoster?.takeIf { it.startsWith("http") || java.io.File(it).exists() })
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
