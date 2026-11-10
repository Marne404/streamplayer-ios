package de.marne.streamplayer.creator

import android.webkit.WebSettings
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.importer.Draft
import de.marne.streamplayer.importer.Importer
import de.marne.streamplayer.importer.LinkItem
import de.marne.streamplayer.importer.LinkList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * „Aus Link übernehmen“ – ein Link oder viele. Lebt in der App, nicht im Bildschirm: Drehen oder
 * kurz wegwechseln bricht nichts ab.
 */
class ImportSession(private val app: CreatorApp, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        data class Running(val step: String, val host: String) : State
        data class Done(val draft: Draft, val busy: Boolean = false) : State
        data class Failed(val message: String) : State
        /** Massenimport: jeder Link ein eigener Titel. [skipped]: Links über [LinkList.MAX] hinaus. */
        data class Batch(val items: List<BatchItem>, val running: Boolean, val skipped: Int = 0) : State {
            val finished: Int get() = items.count { it.status is ItemStatus.Ready || it.status is ItemStatus.Failed }
            val chosen: List<BatchItem> get() = items.filter { (it.status as? ItemStatus.Ready)?.include == true }
        }
    }

    data class BatchItem(val link: LinkItem, val status: ItemStatus) {
        val name: String get() = link.name ?: (status as? ItemStatus.Ready)?.draft?.title?.title.orEmpty()
    }

    sealed interface ItemStatus {
        data object Waiting : ItemStatus
        data class Running(val step: String) : ItemStatus
        data class Ready(val draft: Draft, val include: Boolean = true) : ItemStatus
        data class Failed(val message: String) : ItemStatus
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    val input = MutableStateFlow("")
    /** Eigener Name, falls der erkannte nicht passt. Überlebt das Wählen eines anderen TMDB-Treffers. */
    val nameOverride = MutableStateFlow<String?>(null)

    val browser = BrowserFetcher(app)
    private var job: Job? = null
    /**
     * Zählt Läufe. Anlass: Ein abgebrochener Lauf räumt erst später auf (finally). Ohne Zähler
     * hätte er dabei den Zustand und den Browser des schon neu gestarteten Laufs angefasst.
     */
    private var gen = 0

    private fun stopJob() {
        job?.cancel()
        job = null
        gen++
        scope.launch { browser.release() }
    }

    /** Zählt geteilte Links hoch; die Oberfläche öffnet bei jeder Änderung den Import. */
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()

    /** Link(s) von außen (Teilen, Datei): Import öffnen und gleich loslegen. */
    fun request(text: String) {
        start(text)
        _requests.value++
    }

    /** Kurze Meldung für die Snackbar, wenn sie nicht aus dem Bildschirm selbst kommt (Teilen einer Datei). */
    val notice = MutableStateFlow<String?>(null)

    /**
     * Datei mit Links (JSON oder Text) – gewählt oder geteilt. Ein Katalog im Streamplayer-Format
     * wird stattdessen übernommen wie mit „Datei öffnen“, statt seine Muster als Links zu lesen.
     */
    fun openFile(context: android.content.Context, uri: android.net.Uri) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_FILE).toByteArray().toString(Charsets.UTF_8) }
        }.getOrNull()
        when {
            text == null -> fail("Die Datei lässt sich nicht lesen.")
            LinkList.isCatalog(text) -> notice.value = runCatching {
                val n = app.store.merge(CatalogJson.parse(text, strict = false))
                if (n == 1) "1 Titel aus dem Katalog übernommen." else "$n Titel aus dem Katalog übernommen."
            }.getOrElse { it.message ?: "Der Katalog lässt sich nicht lesen." }
            LinkList.parse(text).isEmpty() -> { fail("In der Datei steht kein Link."); _requests.value++ }
            else -> request(text)
        }
    }

    private fun fail(message: String) {
        stopJob()
        _state.value = State.Failed(message)
    }

    private val userAgent by lazy {
        runCatching { WebSettings.getDefaultUserAgent(app) }.getOrDefault("Mozilla/5.0 (Linux; Android 14) Mobile")
    }

    private fun importer() = Importer(HttpFetcher(http, userAgent), browser, app.tmdb)

    /** Ein Link → Entwurf zum Prüfen. Mehrere → Massenimport. */
    fun start(text: String = input.value) {
        val links = LinkList.parse(text)
        if (links.size >= 2) {
            input.value = links.joinToString("\n") { it.url }
            startBatch(links)
            return
        }
        val url = links.firstOrNull()?.url ?: Importer.normalize(text)
        input.value = url ?: text
        if (url == null) {
            _state.value = State.Failed("Das ist keine Adresse. Sie beginnt mit http:// oder https://.")
            return
        }
        stopJob()
        val run = gen
        nameOverride.value = links.firstOrNull()?.name
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        _state.value = State.Running("Lade die Seite …", host)
        val importer = importer()
        job = scope.launch {
            try {
                // Jsoup und die Struktur-Analyse rechnen spürbar bei großen Seiten – nicht im Hauptthread.
                val d = withContext(Dispatchers.Default) { importer.analyze(url) { step -> _state.value = State.Running(step, host) } }
                _state.value = State.Done(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "Die Seite ließ sich nicht lesen.")
            } finally {
                withContext(NonCancellable) { if (run == gen) browser.release() }
            }
        }
    }

    /**
     * Links nacheinander analysieren, nie parallel: Jede Analyse lädt selbst schon mehrere Seiten,
     * und eine Bestätigungsseite (Captcha) braucht den einen sichtbaren Browser.
     */
    private fun startBatch(all: List<LinkItem>) {
        stopJob()
        val run = gen
        val links = all.take(LinkList.MAX)
        _state.value = State.Batch(links.map { BatchItem(it, ItemStatus.Waiting) }, running = true, skipped = all.size - links.size)
        val importer = importer()
        job = scope.launch {
            try {
                links.forEachIndexed { i, link ->
                    if (i > 0) delay(BETWEEN_LINKS_MS)
                    setItem(i, ItemStatus.Running("Lade die Seite …"))
                    val result = try {
                        val d = withContext(Dispatchers.Default) { importer.analyze(link.url) { step -> setItem(i, ItemStatus.Running(step)) } }
                        val named = link.name != null || d.title.title.isNotBlank()
                        ItemStatus.Ready(d, include = named)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ItemStatus.Failed(e.message ?: "Die Seite ließ sich nicht lesen.")
                    }
                    setItem(i, result)
                }
            } finally {
                withContext(NonCancellable) {
                    if (run == gen) {
                        _state.update { s -> if (s is State.Batch) halted(s) else s }
                        browser.release()
                    }
                }
            }
        }
    }

    private fun setItem(index: Int, status: ItemStatus) = _state.update { s ->
        if (s !is State.Batch) s else s.copy(items = s.items.mapIndexed { i, item -> if (i == index) item.copy(status = status) else item })
    }

    /** Eintrag im Massenimport an- oder abwählen. */
    fun toggle(index: Int) = _state.update { s ->
        if (s !is State.Batch) s
        else s.copy(items = s.items.mapIndexed { i, item ->
            val st = item.status
            if (i == index && st is ItemStatus.Ready) item.copy(status = st.copy(include = !st.include)) else item
        })
    }

    /** Was noch wartet, bleibt als „angehalten“ sichtbar; Fertiges bleibt wählbar. */
    private fun halted(s: State.Batch) = s.copy(running = false, items = s.items.map { item ->
        if (item.status is ItemStatus.Waiting || item.status is ItemStatus.Running) item.copy(status = ItemStatus.Failed("Angehalten")) else item
    })

    /** Laufenden Massenimport anhalten (Fertiges bleibt), sonst wie [cancel]. */
    fun stop() {
        val b = _state.value as? State.Batch
        if (b?.running == true) {
            stopJob()
            _state.value = halted(b)
        } else {
            cancel()
        }
    }

    fun cancel() {
        stopJob()
        _state.value = State.Idle
    }

    fun reset() {
        cancel()
        input.value = ""
        nameOverride.value = null
    }

    /** Anderen TMDB-Treffer verknüpfen, oder mit null keinen. */
    fun link(result: Tmdb.Result?) {
        val importer = importer()
        update { importer.link(it, result) }
    }

    fun retype(type: CatalogType) {
        val importer = importer()
        val name = nameOverride.value
        update { d -> importer.retype(d, type, name ?: d.title.title) }
    }

    private fun update(block: suspend (Draft) -> Draft) {
        val done = _state.value as? State.Done ?: return
        if (done.busy) return
        _state.value = done.copy(busy = true)
        job = scope.launch {
            val next = runCatching { withContext(Dispatchers.Default) { block(done.draft) } }.getOrElse { e ->
                if (e is CancellationException) throw e
                done.draft
            }
            _state.value = State.Done(next)
        }
    }

    /** Titel, der schon aus derselben Quelle stammt: gleiches Muster bzw. gleiche Filmseite. */
    fun existing(draft: Draft): CatalogTitle? = app.store.catalog.value.titles.firstOrNull { t ->
        t.type == draft.title.type &&
            ((draft.title.template != null && t.template == draft.title.template) || (draft.title.url != null && t.url == draft.title.url))
    }

    /** Einzelner Entwurf in den Katalog. */
    fun commit(): CatalogTitle? {
        val done = _state.value as? State.Done ?: return null
        val name = (nameOverride.value ?: done.draft.title.title).trim()
        if (name.isEmpty()) return null
        val (t, _) = save(done.draft, name)
        reset()
        return t
    }

    /** Alle gewählten Einträge des Massenimports. Rückgabe: (neu, aktualisiert). */
    fun commitBatch(): Pair<Int, Int> {
        val batch = _state.value as? State.Batch ?: return 0 to 0
        if (batch.running) return 0 to 0
        var added = 0
        var updated = 0
        batch.chosen.forEach { item ->
            val d = (item.status as ItemStatus.Ready).draft
            val name = item.name.trim().ifEmpty { return@forEach }
            val (_, wasThere) = save(d, name)
            if (wasThere) updated++ else added++
        }
        reset()
        return added to updated
    }

    /**
     * In den Katalog. Gibt es den Titel schon (gleiche Quelle), wird nur aktualisiert, was die
     * Seite liefert – Staffeln, Muster, neue Folgen. Eigene Namen, Bilder und Ausnahmen bleiben.
     */
    private fun save(d: Draft, name: String): Pair<CatalogTitle, Boolean> {
        val store = app.store
        val old = existing(d)
        val t = if (old != null) {
            val titles = old.seasons.associate { it.number to it.title }
            old.copy(
                template = d.title.template ?: old.template,
                seasons = d.title.seasons.map { s -> s.copy(title = s.title ?: titles[s.number]) }.ifEmpty { old.seasons },
                url = d.title.url ?: old.url,
                tmdb = old.tmdb ?: d.title.tmdb,
                year = old.year ?: d.title.year,
                poster = old.poster ?: d.title.poster,
                description = old.description ?: d.title.description,
            )
        } else {
            d.title.copy(id = store.uniqueId(CatalogJson.slug(name, d.title.year)), title = name)
        }
        store.put(t, replacingId = old?.id ?: t.id)
        return t to (old != null)
    }

    companion object {
        /** Abstand zwischen zwei Links eines Massenimports, zusätzlich zur Pause innerhalb einer Analyse. */
        private const val BETWEEN_LINKS_MS = 600L
        private const val MAX_FILE = 2 * 1024 * 1024
    }
}
