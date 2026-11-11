package de.marne.streamplayer.creator

import android.webkit.WebSettings
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.singleEpisodeAsMovie
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.importer.Draft
import de.marne.streamplayer.importer.Importer
import de.marne.streamplayer.importer.LinkItem
import de.marne.streamplayer.importer.LinkList
import de.marne.streamplayer.importer.Overview
import de.marne.streamplayer.importer.Throttle
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
        /**
         * Massenimport: jeder Link ein eigener Titel. [skipped]: Links über [LinkList.MAX] hinaus.
         * [crawl]: gesetzt, wenn die Links aus einer Übersicht stammen.
         */
        data class Batch(val items: List<BatchItem>, val running: Boolean, val skipped: Int = 0, val crawl: Crawl? = null) : State {
            val finished: Int get() = items.count { it.status is ItemStatus.Ready || it.status is ItemStatus.Failed }
            val chosen: List<BatchItem> get() = items.filter { (it.status as? ItemStatus.Ready)?.include == true }
            /** Was gelesen werden soll – ohne Übersprungene. */
            val planned: Int get() = items.count { it.status !is ItemStatus.Skipped }
            val waiting: Int get() = items.count { it.status is ItemStatus.Waiting }
        }
    }

    /**
     * Stand eines Übersichts-Imports. [scanning]: Schritt, solange die Übersicht selbst gelesen
     * wird. [halt]: warum der Durchgang angehalten hat (Abrufgrenze, die Seite bremst).
     */
    data class Crawl(
        val host: String,
        val scanning: String?,
        val listPages: Int = 0,
        val more: Boolean = false,
        val used: Int = 0,
        val budget: Int,
        val halt: String? = null,
    )

    /** [label]: Name aus dem Verweis der Übersicht – bis die Seite selbst gelesen ist. */
    data class BatchItem(val link: LinkItem, val status: ItemStatus, val label: String? = null) {
        val name: String get() = link.name
            ?: (status as? ItemStatus.Ready)?.draft?.title?.title?.takeIf { it.isNotBlank() }
            ?: label.orEmpty()
    }

    sealed interface ItemStatus {
        data object Waiting : ItemStatus
        /** Übersichts-Import: abgewählt, bevor er gelesen wurde – kostet keine Abrufe. */
        data object Skipped : ItemStatus
        data class Running(val step: String) : ItemStatus
        data class Ready(val draft: Draft, val include: Boolean = true) : ItemStatus
        data class Failed(val message: String) : ItemStatus
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    val input = MutableStateFlow("")
    /** Eingabe ist eine Übersicht mit vielen Titeln statt Links zu einzelnen Titeln. */
    val overview = MutableStateFlow(false)
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
            text == null -> fail("Can’t read the file.")
            LinkList.isCatalog(text) -> notice.value = runCatching {
                val n = app.store.merge(CatalogJson.parse(text, strict = false))
                if (n == 1) "1 title imported from the catalog." else "$n titles imported from the catalog."
            }.getOrElse { it.message ?: "Can’t read the catalog." }
            LinkList.parse(text).isEmpty() -> { fail("There’s no link in the file."); _requests.value++ }
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

    /** Übersichts-Import: eine Bremse für alle Abrufe, über „Weiterlesen“ hinweg (robots.txt, Pause). */
    private var throttle: Throttle? = null
    private var crawlHttp: HttpFetcher? = null

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
            _state.value = State.Failed("That’s not a web address. It starts with http:// or https://.")
            return
        }
        stopJob()
        val run = gen
        nameOverride.value = links.firstOrNull()?.name
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        _state.value = State.Running("Loading the page …", host)
        val importer = importer()
        job = scope.launch {
            try {
                // Jsoup und die Struktur-Analyse rechnen spürbar bei großen Seiten – nicht im Hauptthread.
                val d = withContext(Dispatchers.Default) { importer.analyze(url) { step -> _state.value = State.Running(step, host) } }
                _state.value = State.Done(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "The page couldn’t be read.")
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
                    setItem(i, ItemStatus.Running("Loading the page …"))
                    val result = try {
                        val d = withContext(Dispatchers.Default) { importer.analyze(link.url) { step -> setItem(i, ItemStatus.Running(step)) } }
                        val named = link.name != null || d.title.title.isNotBlank()
                        ItemStatus.Ready(d, include = named)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ItemStatus.Failed(e.message ?: "The page couldn’t be read.")
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

    /** Eintrag im Massenimport an- oder abwählen. Beim Übersichts-Import auch, bevor er gelesen ist. */
    fun toggle(index: Int) = _state.update { s ->
        if (s !is State.Batch) s
        else s.copy(items = s.items.mapIndexed { i, item ->
            val st = item.status
            when {
                i != index -> item
                st is ItemStatus.Ready -> item.copy(status = st.copy(include = !st.include))
                s.crawl != null && st is ItemStatus.Waiting -> item.copy(status = ItemStatus.Skipped)
                s.crawl != null && st is ItemStatus.Skipped -> item.copy(status = ItemStatus.Waiting)
                else -> item
            }
        })
    }

    private fun setCrawl(block: (Crawl) -> Crawl) = _state.update { s ->
        if (s is State.Batch && s.crawl != null) s.copy(crawl = block(s.crawl)) else s
    }

    /**
     * „Ganze Übersicht“: die Seite (und ihre Folgeseiten) nach Titeln absuchen und dann jeden Titel
     * lesen wie einen einzelnen Link – nacheinander, alle Abrufe über eine [Throttle]: Pause,
     * Abrufe pro Durchgang, robots.txt, Bremse bei 429. An der Grenze hält der Durchgang an,
     * [more] setzt ihn fort.
     */
    fun startOverview(text: String = input.value) {
        val url = Importer.normalize(text)
        input.value = url ?: text
        if (url == null) {
            _state.value = State.Failed("That’s not a web address. It starts with http:// or https://.")
            return
        }
        stopJob()
        val run = gen
        val prefs = app.prefs
        val plain = HttpFetcher(http, userAgent)
        val t = Throttle(plain, prefs.crawlBudget.value, prefs.crawlPause.value)
        throttle = t
        crawlHttp = plain
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val crawl = Crawl(host = host, scanning = "Loading the overview …", budget = t.budget)
        _state.value = State.Batch(emptyList(), running = true, crawl = crawl)
        job = scope.launch {
            try {
                val scan = withContext(Dispatchers.Default) {
                    Overview.scan(url, t.wrap(plain), t.wrap(browser), MAX_LIST_PAGES, LinkList.MAX) { page, found ->
                        setCrawl {
                            it.copy(scanning = if (page == 1) "Loading the overview …" else "Overview, page $page · " + if (found == 1) "1 title" else "$found titles", used = t.used)
                        }
                    }
                }
                _state.value = State.Batch(
                    scan.titles.map { BatchItem(LinkItem(it.url), ItemStatus.Waiting, label = it.name) },
                    running = true,
                    crawl = crawl.copy(scanning = null, listPages = scan.pages, more = scan.more, used = t.used),
                )
                readPending(t, plain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run == gen) _state.value = State.Failed(e.message ?: "The overview couldn’t be read.")
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

    /** Nächster Durchgang des Übersichts-Imports: neues Abrufbudget, weiter mit dem, was wartet. */
    fun more() {
        val s = _state.value as? State.Batch ?: return
        val crawl = s.crawl ?: return
        val t = throttle ?: return
        val plain = crawlHttp ?: return
        if (s.running || s.waiting == 0) return
        stopJob()
        val run = gen
        t.refill(app.prefs.crawlBudget.value)
        _state.value = s.copy(running = true, crawl = crawl.copy(halt = null, used = 0, budget = t.budget))
        job = scope.launch {
            try {
                readPending(t, plain)
            } finally {
                withContext(NonCancellable) {
                    if (run == gen) {
                        _state.update { st -> if (st is State.Batch) halted(st) else st }
                        browser.release()
                    }
                }
            }
        }
    }

    /**
     * Wartende Titel lesen, bis keiner mehr wartet oder der Durchgang an seine Grenze kommt. Ein
     * Titel bekommt höchstens so viele Abrufe, wie noch übrig sind. Reicht das nicht für alle
     * Staffeln, oder bremst die Seite mittendrin, bleibt er wartend und wird beim nächsten
     * Durchgang neu gelesen – statt unvollständig in der Liste zu landen.
     */
    private suspend fun readPending(t: Throttle, plain: HttpFetcher) {
        val http = t.wrap(plain)
        val br = t.wrap(browser)
        while (true) {
            val s = _state.value as? State.Batch ?: return
            val i = s.items.indexOfFirst { it.status is ItemStatus.Waiting }
            if (i < 0) break
            val stop = t.stop
            if (stop != null || t.remaining < MIN_PER_TITLE) {
                setCrawl { it.copy(halt = Throttle.message(stop ?: Throttle.Stop.BUDGET), used = t.used) }
                return
            }
            val item = s.items[i]
            if (!withContext(Dispatchers.Default) { t.allowed(item.link.url) }) {
                setItem(i, ItemStatus.Failed(Throttle.DISALLOWED))
                continue
            }
            setItem(i, ItemStatus.Running("Loading the page …"))
            val limit = minOf(PER_TITLE, t.remaining)
            val importer = Importer(http, br, app.tmdb, pauseMs = 0, maxPages = limit)
            val result: ItemStatus? = try {
                val d = withContext(Dispatchers.Default) {
                    importer.analyze(item.link.url) { step ->
                        setItem(i, ItemStatus.Running(step))
                        setCrawl { it.copy(used = t.used) }
                    }
                }
                if (t.stop != null || (d.unread.isNotEmpty() && limit < PER_TITLE)) null
                else ItemStatus.Ready(d, include = d.title.title.isNotBlank() || item.label != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (t.stop != null) null else ItemStatus.Failed(e.message ?: "The page couldn’t be read.")
            }
            if (result == null) {
                setItem(i, ItemStatus.Waiting)
                setCrawl { it.copy(halt = Throttle.message(t.stop ?: Throttle.Stop.BUDGET), used = t.used) }
                return
            }
            setItem(i, result)
            setCrawl { it.copy(used = t.used) }
        }
        setCrawl { it.copy(halt = null, used = t.used) }
    }

    /**
     * Was noch wartet, bleibt als „angehalten“ sichtbar; Fertiges bleibt wählbar. Beim
     * Übersichts-Import bleibt Wartendes wartend – „Weiterlesen“ macht dort weiter.
     */
    private fun halted(s: State.Batch) = s.copy(
        running = false,
        items = s.items.map { item ->
            when {
                s.crawl != null && item.status is ItemStatus.Running -> item.copy(status = ItemStatus.Waiting)
                s.crawl != null -> item
                item.status is ItemStatus.Waiting || item.status is ItemStatus.Running -> item.copy(status = ItemStatus.Failed("Stopped"))
                else -> item
            }
        },
        // Von Hand angehalten (beim Lesen der Übersicht oder mitten in den Titeln): sagen, statt „Lese …“ stehen zu lassen.
        crawl = s.crawl?.let { c ->
            val open = c.scanning != null || s.items.any { it.status is ItemStatus.Waiting || it.status is ItemStatus.Running }
            c.copy(scanning = null, halt = c.halt ?: "Stopped.".takeIf { open })
        },
    )

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
        (t.type == draft.title.type &&
            ((draft.title.template != null && t.template == draft.title.template) || (draft.title.url != null && t.url == draft.title.url))) ||
            // Früher als „Serie mit einer Folge“ übernommen, jetzt als Film erkannt: derselbe Titel.
            (draft.title.type == CatalogType.MOVIE && draft.title.url != null &&
                t.singleEpisodeAsMovie().let { it.type == CatalogType.MOVIE && it.url == draft.title.url && t.type == CatalogType.SERIES })
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
        val t = if (old != null && old.type != d.title.type) {
            // Art hat sich geändert (Ein-Folgen-„Serie“ → Film): neu übernehmen, eigene Angaben behalten.
            d.title.copy(id = old.id, title = name, year = old.year ?: d.title.year, poster = old.poster?.takeIf { it.startsWith("data:") } ?: d.title.poster, description = d.title.description ?: old.description)
        } else if (old != null) {
            val titles = old.seasons.associate { it.number to it.title }
            old.copy(
                template = d.title.template ?: old.template,
                seasons = d.title.seasons.map { s -> s.copy(title = s.title ?: titles[s.number]) }.ifEmpty { old.seasons },
                url = d.title.url ?: old.url,
                tmdb = old.tmdb ?: d.title.tmdb,
                year = old.year ?: d.title.year,
                // Jetzt bei TMDB gefunden: Cover, Banner und Text von dort statt der alten von der Seite.
                // Ein eigenes Bild (data:) bleibt.
                poster = if (d.linked != null) old.poster?.takeIf { it.startsWith("data:") } ?: d.title.poster ?: old.poster else old.poster ?: d.title.poster,
                backdrop = if (d.linked != null) d.title.backdrop ?: old.backdrop else old.backdrop ?: d.title.backdrop,
                description = if (d.linked != null) d.title.description ?: old.description else old.description ?: d.title.description,
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
        /** Übersichts-Import: höchstens so viele Seiten der Übersicht selbst. */
        const val MAX_LIST_PAGES = 20
        /** Höchstens so viele Abrufe für einen Titel (wie beim einzelnen Link). */
        private const val PER_TITLE = 40
        /** Darunter fängt ein Durchgang keinen neuen Titel mehr an: Seite, Browser, Übersicht. */
        private const val MIN_PER_TITLE = 3
        private const val MAX_FILE = 2 * 1024 * 1024
    }
}
