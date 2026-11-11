package de.marne.streamplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.collectAsState
import de.marne.streamplayer.ui.design.GIcon
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import de.marne.streamplayer.ui.design.QuietButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import de.marne.streamplayer.browser.BrowserController
import de.marne.streamplayer.browser.BrowserScreen
import de.marne.streamplayer.browser.WatchController
import de.marne.streamplayer.library.ResolveInfo
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.tr
import de.marne.streamplayer.library.ResolveScreen
import android.app.ActivityOptions
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.detect.Prober
import de.marne.streamplayer.detect.Variant
import de.marne.streamplayer.download.DownloadItem
import de.marne.streamplayer.download.DownloadService
import de.marne.streamplayer.downloads.DownloadActions
import de.marne.streamplayer.downloads.DownloadsScreen
import de.marne.streamplayer.downloads.PendingRow
import de.marne.streamplayer.library.LinkActions
import de.marne.streamplayer.library.LinkSheet
import de.marne.streamplayer.library.DetailActions
import de.marne.streamplayer.library.DiscoverScreen
import de.marne.streamplayer.library.GridScreen
import de.marne.streamplayer.library.GridSpec
import de.marne.streamplayer.library.LibActions
import de.marne.streamplayer.library.LibState
import de.marne.streamplayer.library.MineScreen
import de.marne.streamplayer.library.QuickSheet
import de.marne.streamplayer.library.CategorySheet
import de.marne.streamplayer.library.Route
import de.marne.streamplayer.library.DetailScreen
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.HomeScreen
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Shelves
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.player.PlayerActivity
import de.marne.streamplayer.settings.SettingsScreen
import de.marne.streamplayer.ui.design.Hairline
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.isAppDark
import kotlinx.coroutines.launch

/** Navigationszustand, von der Activity gehalten: Intents (Teilen, Benachrichtigung) setzen ihn direkt. */
class AppNav {
    var tab by mutableIntStateOf(MainActivity.TAB_HOME)
    /** Browser ist ein Nebenraum über den Tabs, kein Tab mehr. */
    var browserOpen by mutableStateOf(false)
    /**
     * Seiten über dem Tab (Detail, Übersicht, Downloads), die oberste wird gezeigt. Ein Tabwechsel
     * leert den Stapel – wie bei Netflix, wo jeder Tab wieder bei sich anfängt.
     */
    val stack = androidx.compose.runtime.mutableStateListOf<Route>()
    /** Richtung der letzten Bewegung: vor (hinein) oder zurück – die Animation erzählt sie. */
    var forward by mutableStateOf(true)
    /** Kurzansicht eines Titels (Blatt). */
    var previewId by mutableStateOf<String?>(null)
    /** Kategorien-Blatt offen, mit Filter der Startseite (null = alles). */
    var categories by mutableStateOf(false)
    var categoriesKind by mutableStateOf<de.marne.streamplayer.library.TitleKind?>(null)
    /** Lupe auf der Startseite: Entdecken öffnen und das Suchfeld fokussieren. */
    var focusSearch by mutableStateOf(false)

    fun push(r: Route) {
        if (stack.lastOrNull() == r) return
        forward = true
        stack.add(r)
    }

    fun pop() {
        forward = false
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun selectTab(t: Int) {
        forward = true
        stack.clear()
        tab = t
    }

    fun openDetail(id: String, origin: String? = null) = push(Route.Detail(id, origin))

    /** „Zu Sammlung hinzufügen“ für diesen Titel; und ob gerade eine neue Sammlung benannt wird. */
    var collectFor by mutableStateOf<String?>(null)
    var naming by mutableStateOf(false)
    /** Geteilter Link: null = kein Sheet. */
    var linkUrl by mutableStateOf<String?>(null)
    /** Geteilte/geöffnete Katalog-Datei, wird beim nächsten Durchlauf importiert. */
    var pendingImport by mutableStateOf<Uri?>(null)
    /** Browser wurde nur zum Auflösen geöffnet: danach wieder schließen. */
    var resolving by mutableStateOf(false)
    /** Was der Ladebildschirm währenddessen zeigt. */
    var resolveInfo by mutableStateOf<ResolveInfo?>(null)
    /**
     * Der Player ist gestartet, liegt aber noch nicht davor: Der Ladebildschirm bleibt, bis die
     * Activity verdeckt ist, und verschwindet dann ohne Animation – sonst blitzt kurz die Bibliothek auf.
     */
    var handoff by mutableStateOf(false)
    var closeInstant by mutableStateOf(false)
    /**
     * Download aus der Bibliothek: Die Seite wird verdeckt geladen wie beim Abspielen, aber ohne
     * Ladebildschirm – nur eine kleine Leiste unten. Was währenddessen dazukommt, wartet in [queue].
     */
    var quiet by mutableStateOf<QuietJob?>(null)
    /** Die Seite wartet auf eine Prüfung („Bist du ein Mensch?“): Ladebildschirm und Bibliothek treten beiseite. */
    var challenge by mutableStateOf(false)
    val queue = androidx.compose.runtime.mutableStateListOf<QuietJob>()
    /** „Wer schaut?“: offen, und ob gleich im Verwalten-Modus (aus den Einstellungen). */
    var profilePicker by mutableStateOf(false)
    var profileManage by mutableStateOf(false)
    /** Auswahl muss getroffen werden (gesperrtes Profil beim Start): nicht wegtippbar. */
    var profileGate by mutableStateOf(false)
    /** Blatt „Bibliotheken“ offen. */
    var librariesOpen by mutableStateOf(false)
    /** Einladungscode zum Beitreten (Link, geteilter Text, Eingabe). */
    var joinCode by mutableStateOf<String?>(null)
    /** Katalog wartet auf die Wahl der Bibliothek. */
    var importAsk by mutableStateOf<Uri?>(null)
    /** Vom Widget: diesen Film bzw. diese Folge spielen (Schlüssel aus Keys). */
    var playKey by mutableStateOf<String?>(null)
    /** Blatt zu einem Download: Titel-ID und Schlüssel (Keys) von Film oder Folge. */
    var offlineFor by mutableStateOf<Pair<String, String>?>(null)

    /** Was gerade gesucht wird (true) oder wartet (false) – für OfflineState. */
    fun pending(): Map<String, Boolean> = buildMap {
        queue.forEach { put(it.key, false) }
        quiet?.let { put(it.key, true) }
    }
}

/** Ein Download, dessen Video noch gesucht wird: Titel, Folge (oder Film), Anzeige, Seite. */
data class QuietJob(val titleId: String, val season: Int?, val episode: Int?, val label: String, val url: String) {
    /** Fortschritts-Schlüssel (Keys) des Films bzw. der Folge. */
    val key: String get() = if (season != null && episode != null) "e:$titleId:$season:$episode" else "m:$titleId"
}

class MainActivity : ComponentActivity() {

    private lateinit var hub: DetectionHub
    private lateinit var browser: BrowserController
    private lateinit var watch: WatchController
    private val nav = AppNav()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = App.instance
        hub = DetectionHub(lifecycleScope, Prober(app.http) { browser.userAgent })
        browser = BrowserController(this, hub, app.settings, app.adblock)
        watch = WatchController(browser, hub, lifecycleScope)
        if (savedInstanceState == null) handleIntent(intent)
        de.marne.streamplayer.update.Updater.check(this)

        setContent {
            val theme by app.settings.theme.collectAsStateWithLifecycle()
            val dark = isAppDark(theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            IvoryInkTheme(dark) {
                val onboarded by app.settings.onboarded.collectAsStateWithLifecycle()
                // Willkommen → Bibliothek: der Ablauf blendet weg, die Bibliothek kommt leicht auf einen zu.
                androidx.compose.animation.AnimatedContent(
                    targetState = onboarded,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(420, delayMillis = 80)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(420, delayMillis = 80, easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1f, 0.5f, 1f)), initialScale = 0.97f)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
                    },
                    label = "onboarding",
                ) { done ->
                    if (done) AppRoot(browser, hub, watch, nav)
                    else de.marne.streamplayer.onboarding.OnboardingScreen(onFinished = { })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                // Einladung zu einer geteilten Bibliothek: streamplayer://join/<code>
                if (data.scheme == "streamplayer" && data.host == "join") {
                    data.lastPathSegment?.let { nav.browserOpen = false; nav.joinCode = it }
                    return
                }
                if (data.scheme == "http" || data.scheme == "https") openLink(data.toString()) else importCatalog(data)
            }
            Intent.ACTION_SEND -> {
                // Katalog-Datei aus dem Creator (oder einem Dateimanager) – sonst ein Link als Text.
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val invite = text?.takeIf { "streamplayer://join/" in it }?.let(de.marne.streamplayer.account.Sharing::codeIn)
                if (stream != null) importCatalog(stream)
                else if (invite != null) { nav.browserOpen = false; nav.joinCode = invite }
                else text
                    ?.let { Regex("""https?://\S+""").find(it)?.value }
                    ?.let(::openLink)
            }
            // Widget „Weiterschauen“: Film oder Folge spielen, sobald die Bibliothek bereit ist.
            ACTION_PLAY -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                nav.browserOpen = false
                nav.selectTab(TAB_HOME)
                nav.playKey = key
            }
            ACTION_SHOW_DOWNLOADS -> {
                nav.browserOpen = false
                nav.selectTab(TAB_MINE)
                nav.push(Route.Downloads)
            }
        }
    }

    /** Geteilter Link: kurzes Sheet mit „Ansehen“ und „Im Browser öffnen“. */
    private fun openLink(url: String) {
        nav.browserOpen = false
        nav.linkUrl = url
    }

    private fun importCatalog(uri: Uri) {
        nav.browserOpen = false
        nav.selectTab(TAB_HOME)
        nav.pendingImport = uri
    }

    override fun onPause() {
        browser.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW_DOWNLOADS = "de.marne.streamplayer.SHOW_DOWNLOADS"
        const val ACTION_PLAY = "de.marne.streamplayer.PLAY"
        const val EXTRA_KEY = "key"
        const val TAB_HOME = 0
        const val TAB_DISCOVER = 1
        const val TAB_MINE = 2
        // Einstellungen sind seit 1.9 kein Tab mehr, sondern eine Seite unter „Meins“ (Zahnrad).
    }
}

/** [label] ist eine Funktion, damit der Name beim Sprachwechsel mit umschaltet. */
private data class TabSpec(val label: () -> String, val icon: Int, val iconSelected: Int)

private val tabs = listOf(
    TabSpec({ tr("Start", "Home") }, Ic.library, Ic.libraryFilled),
    TabSpec({ tr("Entdecken", "Discover") }, Ic.search, Ic.search),
    TabSpec({ tr("Meins", "My Stuff") }, Ic.bookmark, Ic.bookmarkFilled),
)

@Composable
private fun AppRoot(browser: BrowserController, hub: DetectionHub, watch: WatchController, nav: AppNav) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val history by app.settings.history.collectAsStateWithLifecycle()
    val downloads by app.downloads.items.collectAsStateWithLifecycle()
    val titles by app.library.titles.collectAsStateWithLifecycle()
    val progress by app.progress.all.collectAsStateWithLifecycle()
    val fullscreen by browser.fullscreen.collectAsStateWithLifecycle()
    val watchState by watch.state.collectAsStateWithLifecycle()
    val enrichStatus by app.enricher.status.collectAsStateWithLifecycle()
    val profileList by app.profiles.profiles.collectAsStateWithLifecycle()
    val activeId by app.profiles.activeId.collectAsStateWithLifecycle()
    val activeProfile = profileList.firstOrNull { it.id == activeId } ?: profileList.first()
    // Bibliotheken: gezeigt wird die gewählte des Profils; ohne Wahl (oder wenn sie weg ist) mit
    // mehreren Bibliotheken „Alles“, sonst die eine.
    val libraries by app.library.libraries.collectAsStateWithLifecycle()
    val activeLibraries by app.settings.activeLibraries.collectAsStateWithLifecycle()
    val activeLibrary = activeLibraries[activeProfile.id]
        ?.takeIf { it == de.marne.streamplayer.library.Library.ALL && libraries.size > 1 || libraries.any { l -> l.id == it } }
        ?: if (libraries.size > 1) de.marne.streamplayer.library.Library.ALL else de.marne.streamplayer.library.Library.MAIN
    val visibleTitles = remember(titles, activeLibrary) {
        if (activeLibrary == de.marne.streamplayer.library.Library.ALL) titles else titles.filter { it.library == activeLibrary }
    }
    LaunchedEffect(activeLibrary) {
        app.library.target = if (activeLibrary == de.marne.streamplayer.library.Library.ALL) de.marne.streamplayer.library.Library.MAIN else activeLibrary
    }
    val libState = LibState(visibleTitles, progress, activeProfile, enrichStatus, downloads, nav.pending(), titles, libraries, activeLibrary)
    // Mehrere Profile: bei jedem Start fragen, wer schaut – wenn eingestellt. Sonst bleibt das letzte.
    val needsChoice by app.profiles.needsChoice.collectAsStateWithLifecycle()
    val askProfile by app.settings.askProfile.collectAsStateWithLifecycle()
    LaunchedEffect(needsChoice) {
        if (!needsChoice) return@LaunchedEffect
        // Gesperrtes Profil vom letzten Mal: immer fragen, auch wenn „Beim Start fragen“ aus ist –
        // sonst säße, wer die App öffnet, ohne PIN im gesperrten Profil.
        val gate = app.profiles.active.locked
        if ((askProfile && profileList.size > 1) || gate) {
            nav.profileManage = false
            nav.profileGate = gate
            nav.profilePicker = true
        } else app.profiles.chosen()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toast(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun doImport(uri: Uri, libraryId: String? = null) {
        // Mehrere Bibliotheken: erst fragen, wohin der Katalog soll.
        if (libraryId == null && app.library.libraries.value.count { !it.joined } > 1) {
            nav.importAsk = uri
            return
        }
        scope.launch {
            runCatching { app.importer.import(uri, libraryId) }
                .onSuccess { r ->
                    val hint = if (!app.enricher.available) tr(" Für Cover und Beschreibungen: TMDB-Schlüssel in den Einstellungen.", " For covers and descriptions: add a TMDB key in Settings.") else ""
                    toast(tr("Katalog importiert: ${r.summary}.$hint", "Catalog imported: ${r.summary}.$hint"))
                }
                .onFailure { toast(it.message ?: tr("Import fehlgeschlagen.", "Import failed.")) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::doImport) }
    LaunchedEffect(nav.pendingImport) {
        nav.pendingImport?.let { uri ->
            nav.pendingImport = null
            doImport(uri)
        }
    }

    var coverFor by remember { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = coverFor ?: return@rememberLauncherForActivityResult
        coverFor = null
        if (uri != null) scope.launch {
            app.importer.saveCustomPoster(uri, id)?.let { app.library.setCustomPoster(id, it) } ?: toast(tr("Bild lässt sich nicht lesen.", "Couldn't read the image."))
        }
    }

    fun startDownload(c: Candidate, v: Variant?, title: String, thumb: String?, libraryKey: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.downloads.enqueue(c, v, title, thumb, browser.userAgent, libraryKey, subtitles = hub.subtitles.value)
        DownloadService.kick(context)
        // Aus der Bibliothek zeigt das Zeichen an Film/Folge den Stand – eine Meldung wäre doppelt.
        if (libraryKey == null) toast(tr("Läuft. Du findest es unter Meins → Downloads.", "Downloading. Find it under My Stuff → Downloads."), tr("Ansehen", "View")) {
            nav.browserOpen = false
            nav.selectTab(MainActivity.TAB_MINE)
            nav.push(Route.Downloads)
        }
    }

    val preview: (Candidate, String) -> Unit = { c, title ->
        context.startActivity(
            PlayerActivity.intent(
                context, Uri.parse(c.url), title,
                mime = c.mime.takeIf { c.kind != MediaKind.FILE },
                referer = c.referer ?: c.pageUrl,
                userAgent = browser.userAgent,
                subtitles = hub.subtitles.value,
            )
        )
    }

    /** Titel/Bild/Beschreibung von der Seite holen und nachtragen. */
    fun fetchMeta(id: String, url: String) {
        scope.launch {
            app.meta.fetch(url)?.let { m -> app.library.enrich(id, m.title, m.image, m.description) }
        }
    }

    fun startQuiet(job: QuietJob) {
        nav.quiet = job
        nav.closeInstant = false
        nav.resolving = true
        nav.browserOpen = true
        watch.start(job.url, WatchController.Request(key = job.key, label = job.label, titleId = job.titleId, download = true, fromLibrary = true))
    }

    /**
     * Film oder Folge abspielen bzw. laden. Offline-Datei: direkt. Sonst Seite verdeckt laden,
     * Stream finden (WatchController), danach zurück in die Bibliothek.
     */
    fun resolve(t: LibTitle, ep: Episode?, download: Boolean, announce: Boolean = true) {
        val key = ep?.let { Keys.episode(t, it) } ?: Keys.movie(t)
        val label = if (ep != null) "${t.title} · ${ep.label}" else t.title
        val offline = t.offline[key]
        if (!download && offline != null) {
            context.startActivity(PlayerActivity.intent(context, Uri.parse(offline), label, key = key, startMs = app.progress.resumePosition(key)))
            return
        }
        val url = ep?.url ?: t.pageUrl ?: return
        if (download) {
            val job = QuietJob(t.id, ep?.season, ep?.number, label, url)
            if (nav.quiet == job || job in nav.queue) return
            // Läuft schon eine Suche (Download oder Abspielen): hinten anstellen, nicht abbrechen.
            if (nav.quiet != null || nav.resolving) {
                nav.queue.add(job)
                if (announce) toast(tr("Vorgemerkt: $label", "Queued: $label"))
            } else startQuiet(job)
            return
        }
        // Abspielen hat Vorrang: eine laufende Download-Suche wartet so lange vorne in der Schlange.
        nav.quiet?.let { nav.queue.add(0, it); nav.quiet = null }
        nav.resolveInfo = ResolveInfo(
            title = t.title,
            eyebrow = listOfNotNull(
                if (download) tr("Laden", "Download") else null,
                ep?.label ?: listOfNotNull(tr("Film", "Movie"), t.shownYear?.toString()).joinToString(" · "),
            ).joinToString(" · "),
            subtitle = ep?.title,
            // Nur echte Bilder ihrer Art: ein Breitbild als „Poster“ gequetscht sah doppelt aus.
            image = Tmdb.large(ep?.still ?: t.backdrop ?: t.tmdb?.backdrop),
            poster = Tmdb.large(t.customPoster ?: t.poster ?: t.tmdb?.poster),
            download = download,
        )
        nav.closeInstant = false
        nav.resolving = true
        nav.browserOpen = true
        watch.start(url, WatchController.Request(key = key, label = label, titleId = t.id, download = download, fromLibrary = true))
    }

    /** Stand jetzt, nicht beim letzten Zeichnen – für Aktionen, die in remember-Blöcken hängen. */
    fun offlineNow(t: LibTitle, key: String) =
        de.marne.streamplayer.download.Offline.state(key, app.library.get(t.id)?.offline?.get(key), app.downloads.items.value, nav.pending())

    /** Fertige Datei löschen: über ihren Download-Eintrag, sonst direkt (ältere Dateien ohne Eintrag). */
    fun deleteOfflineKey(t: LibTitle, key: String) {
        val uri = app.library.get(t.id)?.offline?.get(key) ?: return
        val item = app.downloads.items.value.firstOrNull { it.fileUri == uri }
        if (item != null) DownloadService.remove(context, item.id)
        else {
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            app.library.removeFile(uri)
        }
    }

    /** Suche abbrechen: aus der Schlange nehmen bzw. die laufende Suche stoppen. */
    fun cancelPending(key: String) {
        nav.queue.removeAll { it.key == key }
        if (nav.quiet?.key == key) watch.cancel()
    }

    fun playTitle(t: LibTitle) {
        if (t.kind == TitleKind.MOVIE) resolve(t, null, false)
        else resolve(t, Shelves.nextFor(t, app.progress.all.value)?.first ?: t.episodes().firstOrNull() ?: return, false)
    }

    // Stream gefunden: abspielen oder laden – und bei Aufträgen aus der Bibliothek dorthin zurück.
    LaunchedEffect(watchState) {
        if (watchState !is WatchController.State.Challenge) nav.challenge = false
        // Statistik: nur, dass auf dieser Domain nichts gefunden wurde – nie die Adresse.
        (watchState as? WatchController.State.NotFound)?.let { de.marne.streamplayer.stats.Usage.findFailed(it.host) }
        when (val s = watchState) {
            // Prüfung: Die Seite muss sichtbar werden, sonst kommt niemand an das Kästchen. Beim
            // Download im Hintergrund nicht einfach dazwischenfahren – erst fragen.
            is WatchController.State.Challenge -> {
                val job = nav.quiet
                if (job == null) nav.challenge = true
                else toast(tr("${job.label}: Seite will eine Bestätigung.", "${job.label}: the site wants a confirmation."), tr("Zeigen", "Show")) {
                    if (watch.state.value is WatchController.State.Challenge) nav.challenge = true
                }
            }
            is WatchController.State.Ready -> if (s.autoStart) {
                val r = watch.request
                if (r.download) {
                    startDownload(s.candidate, s.candidate.variants.firstOrNull(), r.label ?: s.title, s.thumbnail, r.key)
                    // Seite anhalten und schließen: sie würde sonst unsichtbar weiterspielen.
                    if (nav.quiet != null) watch.cancel() else watch.showPage()
                } else {
                    // Der Ladebildschirm ist dunkel, der Player schwarz: überblenden statt hereinschieben.
                    val fade = ActivityOptions.makeCustomAnimation(context, R.anim.player_enter, R.anim.hold).toBundle()
                    context.startActivity(
                        PlayerActivity.intent(
                            context, Uri.parse(s.candidate.url), r.label ?: s.title,
                            mime = s.candidate.mime.takeIf { s.candidate.kind != MediaKind.FILE },
                            referer = s.candidate.referer ?: s.candidate.pageUrl,
                            userAgent = browser.userAgent,
                            key = r.key,
                            startMs = r.key?.let(app.progress::resumePosition) ?: 0,
                            subtitles = hub.subtitles.value,
                        ),
                        fade,
                    )
                    if (r.fromLibrary) {
                        if (nav.resolving) nav.handoff = true
                        watch.showPage()
                    } else watch.playerOpened()
                }
            }
            WatchController.State.Idle -> {
                nav.quiet = null
                if (nav.resolving && !nav.handoff) {
                    nav.resolving = false
                    nav.browserOpen = false
                }
            }
            // Im Hintergrund nichts gefunden: Bescheid geben, Seite zum Selbst-Starten anbieten.
            is WatchController.State.NotFound, is WatchController.State.Protected -> nav.quiet?.let { job ->
                watch.cancel()
                val why = if (watchState is WatchController.State.Protected) tr("geschützt (DRM)", "protected (DRM)") else tr("kein Video gefunden", "no video found")
                toast("${job.label}: $why.", tr("Seite öffnen", "Open page")) {
                    nav.browserOpen = true
                    browser.load(job.url)
                }
            }
            else -> Unit
        }
    }

    // Wartende Downloads nacheinander suchen – nur solange die App vorn ist: Im Hintergrund hält
    // Android die WebView an, und die Suche fände nichts.
    val resumed by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(nav.quiet, nav.resolving, nav.queue.size, resumed) {
        if (nav.quiet == null && !nav.resolving && nav.queue.isNotEmpty() && resumed.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            kotlinx.coroutines.delay(300)
            if (nav.quiet == null && !nav.resolving && nav.queue.isNotEmpty()) startQuiet(nav.queue.removeAt(0))
        }
    }

    // Übergabe an den Player: erst wenn er davor liegt, still wegräumen. Startet er nicht
    // (Absturz, abgelehnter Intent), nach ein paar Sekunden trotzdem – sichtbar ausblenden.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && nav.handoff) {
                nav.closeInstant = true
                nav.handoff = false
                nav.resolving = false
                nav.browserOpen = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(nav.handoff) {
        if (!nav.handoff) return@LaunchedEffect
        kotlinx.coroutines.delay(5000)
        if (nav.handoff) {
            nav.handoff = false
            nav.resolving = false
            nav.browserOpen = false
        }
    }

    val downloadActions = remember {
        DownloadActions(
            play = { d -> d.fileUri?.let { context.startActivity(PlayerActivity.intent(context, Uri.parse(it), d.title, d.mime, key = d.libraryKey, startMs = d.libraryKey?.let(app.progress::resumePosition) ?: 0)) } },
            pause = { d -> DownloadService.pause(context, d.id) },
            resume = { d -> DownloadService.resume(context, d.id) },
            remove = { d -> DownloadService.remove(context, d.id) },
            share = { d -> share(context, d) },
            openWith = { d -> openWith(context, d) },
            openPage = { d ->
                nav.browserOpen = true
                browser.load(d.pageUrl)
            },
            goBrowse = { nav.browserOpen = true },
            cancelPending = { key -> cancelPending(key) },
            openTitle = { id -> nav.openDetail(id) },
            saveToLibrary = { d ->
                d.fileUri?.let { uri ->
                    val t = app.library.addOfflineMovie(uri, d.title, d.pageUrl, d.thumbnail, d.durationMs)
                    // Ab jetzt gehört die Datei zum Titel: Downloads gruppiert sie dort, Fortschritt zählt mit.
                    app.downloads.update(d.id) { it.copy(libraryKey = Keys.movie(t)) }
                    toast(tr("In der Bibliothek.", "Added to your library."), tr("Ansehen", "View")) {
                        nav.selectTab(MainActivity.TAB_HOME)
                        nav.openDetail(t.id)
                    }
                }
            },
        )
    }

    fun markTitle(t: LibTitle, watched: Boolean) {
        if (t.kind == TitleKind.MOVIE) app.progress.setWatched(Keys.movie(t), watched)
        else t.episodes().forEach { app.progress.setWatched(Keys.episode(t, it), watched) }
    }

    val libActions = remember {
        LibActions(
            open = { nav.previewId = null; nav.openDetail(it.id) },
            show = { t, origin -> nav.previewId = null; nav.openDetail(t.id, origin) },
            collect = { nav.collectFor = it.id },
            newCollection = { nav.naming = true },
            settings = { nav.push(Route.Settings) },
            preview = { nav.previewId = it.id },
            play = { t, e -> if (e != null) resolve(t, e, false) else playTitle(t) },
            playContinue = { c -> resolve(c.title, c.episode, false) },
            download = { t, e ->
                if (t.kind == TitleKind.SERIES && e == null) Shelves.nextFor(t, app.progress.all.value)?.first?.let { resolve(t, it, true) }
                else resolve(t, e, true)
            },
            offlineMenu = { t, e -> nav.offlineFor = t.id to (e?.let { Keys.episode(t, it) } ?: Keys.movie(t)) },
            downloadSeason = { t, season ->
                // Fehlende Folgen vormerken, angehaltene fortsetzen; was läuft oder da ist, bleibt.
                var n = 0
                t.episodes().filter { it.season == season }.forEach { e ->
                    when (val st = offlineNow(t, Keys.episode(t, e))) {
                        de.marne.streamplayer.download.OfflineState.None -> if (e.url != null || t.pageUrl != null) { resolve(t, e, true, announce = false); n++ }
                        is de.marne.streamplayer.download.OfflineState.Stopped -> { DownloadService.resume(context, st.item.id); n++ }
                        else -> Unit
                    }
                }
                toast(
                    when (n) {
                        0 -> tr("Alles schon geladen oder unterwegs.", "Everything is already downloaded or on its way.")
                        1 -> tr("1 Folge vorgemerkt.", "1 episode queued.")
                        else -> tr("$n Folgen vorgemerkt – sie laden nacheinander.", "$n episodes queued – they download one after another.")
                    }
                )
            },
            deleteOffline = { t, keys ->
                keys.forEach { deleteOfflineKey(t, it) }
                toast(if (keys.size == 1) tr("Download gelöscht.", "Download deleted.") else tr("${keys.size} Downloads gelöscht.", "${keys.size} downloads deleted."))
            },
            toggleList = { t ->
                val had = t.id in app.profiles.active.list
                app.profiles.toggleList(t.id)
                toast(if (had) tr("Aus Meiner Liste entfernt.", "Removed from My List.") else tr("Auf Meiner Liste.", "Added to My List."))
            },
            markWatched = { t, w -> markTitle(t, w) },
            setWatched = { key, watched -> app.progress.setWatched(key, watched) },
            grid = { nav.push(Route.Grid(it)) },
            categories = { k -> nav.categoriesKind = k; nav.categories = true },
            search = { nav.focusSearch = true; nav.selectTab(MainActivity.TAB_DISCOVER) },
            profiles = { nav.profileManage = false; nav.profilePicker = true },
            import = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            browser = { nav.browserOpen = true },
            downloads = { nav.push(Route.Downloads) },
            libraries = { nav.librariesOpen = true },
            back = { nav.pop() },
        )
    }

    val detailActions = remember {
        DetailActions(
            openPage = { url ->
                nav.browserOpen = true
                browser.load(url)
            },
            rename = { t, title -> app.library.rename(t.id, title) },
            changeCover = { t ->
                coverFor = t.id
                coverPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            resetCover = { t -> app.library.setCustomPoster(t.id, null) },
            setCover = { t, url -> app.library.setCustomPoster(t.id, url) },
            matchTmdb = { t, r ->
                if (!app.enricher.available) toast(tr("Erst einen TMDB-Schlüssel in den Einstellungen eintragen.", "Add a TMDB key in Settings first."))
                else {
                    app.library.setTmdbMatch(t.id, r.id)
                    app.enricher.enqueue(listOf(t.id), force = true)
                    toast(tr("Zugeordnet: ${r.title}${r.year?.let { " ($it)" }.orEmpty()} – lädt …", "Matched: ${r.title}${r.year?.let { " ($it)" }.orEmpty()} – loading …"))
                }
            },
            moveTo = { t, lib ->
                app.library.moveTitle(t.id, lib)
                val name = app.library.libraries.value.firstOrNull { it.id == lib }?.label.orEmpty()
                toast(tr("Verschoben nach „$name“.", "Moved to “$name”."))
            },
            refreshTmdb = { t ->
                if (app.enricher.available) {
                    app.enricher.enqueue(listOf(t.id), force = true)
                    toast(tr("TMDB lädt neu …", "Reloading from TMDB …"))
                } else toast(tr("Erst einen TMDB-Schlüssel in den Einstellungen eintragen.", "Add a TMDB key in Settings first."))
            },
            remove = { t ->
                app.library.remove(t.id)
                app.progress.removeTitle(t.id)
                nav.pop()
                toast(tr("Aus der Bibliothek entfernt.", "Removed from your library."))
            },
        )
    }

    // Gesehen → Datei löschen bzw. nächste Folge laden (Einstellungen → Downloads). Nur, was gerade
    // eben fertig wurde: Beim Profilwechsel oder Abgleich tauchen alte „gesehen“ auf – die sollen
    // nicht auf einen Schlag Dateien löschen oder Downloads lostreten.
    val finishedSeen = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(progress, activeId) {
        val now = progress.filterValues { it.finished }.keys
        val before = finishedSeen.value
        finishedSeen.value = now
        if (before == null) return@LaunchedEffect
        val autoDelete = app.settings.autoDelete.value
        val autoNext = app.settings.autoNext.value
        if (!autoDelete && !autoNext) return@LaunchedEffect
        val recent = System.currentTimeMillis() - 15 * 60_000L
        for (key in now - before) {
            val p = progress[key] ?: continue
            if (p.updatedAt < recent || p.watchedMs <= 0) continue
            val (id, season, number) = Keys.parse(key) ?: continue
            val t = app.library.get(id) ?: continue
            if (t.offline[key] == null) continue
            if (autoNext && season != null && number != null) {
                t.episode(season, number)?.let(t::next)?.let { n ->
                    if (offlineNow(t, Keys.episode(t, n)) == de.marne.streamplayer.download.OfflineState.None && (n.url != null || t.pageUrl != null)) {
                        resolve(t, n, true, announce = false)
                        toast(tr("Nächste Folge wird geladen: ${n.label}", "Downloading the next episode: ${n.label}"))
                    }
                }
            }
            if (autoDelete) deleteOfflineKey(t, key)
        }
    }

    // Einladung einlösen: beitreten, die Bibliothek zeigen. Ohne Konto geht es nicht – dann dorthin.
    LaunchedEffect(nav.joinCode, nav.profilePicker) {
        val code = nav.joinCode ?: return@LaunchedEffect
        if (nav.profilePicker) return@LaunchedEffect
        nav.joinCode = null
        if (app.cloud.user.value == null) {
            toast(tr("Zum Beitreten erst bei einem Konto anmelden.", "Sign in to an account to join."), tr("Konto", "Account")) {
                nav.selectTab(MainActivity.TAB_MINE)
                nav.push(Route.Settings)
            }
            return@LaunchedEffect
        }
        runCatching { app.sharing.join(code) }
            .onSuccess { l ->
                app.settings.setActiveLibrary(app.profiles.active.id, l.id)
                nav.selectTab(MainActivity.TAB_HOME)
                toast(tr("„${l.label}“ von ${l.owner ?: "deinem Freund"} ist da – nur zum Ansehen.", "“${l.label}” from ${l.owner ?: "your friend"} is here – view only."))
            }
            .onFailure { toast(it.message ?: tr("Beitreten hat nicht geklappt.", "Couldn't join.")) }
    }
    // Ein Freund hat das Teilen beendet oder einen entfernt.
    val revoked by app.sync.revoked.collectAsStateWithLifecycle()
    LaunchedEffect(revoked) {
        revoked?.let { toast(tr("„$it“ wird nicht mehr mit dir geteilt.", "“$it” is no longer shared with you.")) ; app.sync.revokedShown() }
    }

    // Wunsch vom Widget: erst, wenn „Wer schaut?“ beantwortet ist – ein gesperrtes Profil braucht seine PIN.
    LaunchedEffect(nav.playKey, nav.profilePicker) {
        val key = nav.playKey ?: return@LaunchedEffect
        if (nav.profilePicker) return@LaunchedEffect
        nav.playKey = null
        val (id, season, number) = Keys.parse(key) ?: return@LaunchedEffect
        val t = app.library.get(id) ?: return@LaunchedEffect
        val ep = if (season != null && number != null) t.episode(season, number) ?: return@LaunchedEffect else null
        resolve(t, ep, false)
    }

    // Wunsch aus dem Player („Nächste Folge“, Folge aus dem Panel): abholen, sobald wir wieder vorn sind.
    val bridge by de.marne.streamplayer.player.PlayerBridge.pending.collectAsStateWithLifecycle()
    LaunchedEffect(bridge) {
        val r = bridge ?: return@LaunchedEffect
        de.marne.streamplayer.player.PlayerBridge.take()
        val t = app.library.get(r.titleId) ?: return@LaunchedEffect
        t.episode(r.season, r.episode)?.let { resolve(t, it, false) }
    }

    // Zurück: Browser schließen › Detailseite schließen › zur Bibliothek. BrowserScreen hat eigene,
    // spätere Handler (Seitenverlauf, Suche abbrechen) – die gewinnen, solange sie aktiv sind.
    // Sucht der Browser nur verdeckt nach einem Download, gehört Zurück weiter der Bibliothek.
    val browserShown = nav.browserOpen && (nav.quiet == null || nav.challenge)
    BackHandler(enabled = !browserShown && nav.stack.isEmpty() && nav.tab != MainActivity.TAB_HOME) { nav.selectTab(MainActivity.TAB_HOME) }
    BackHandler(enabled = !browserShown && nav.stack.isNotEmpty()) { nav.pop() }
    BackHandler(enabled = browserShown) { if (watch.active) watch.cancel() else nav.browserOpen = false }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        // Beim Auflösen liegt die WebView ganz unten (sie muss angehängt und sichtbar sein, damit
        // Player starten), die Bibliothek bleibt darüber stehen, der Ladebildschirm blendet über ihr ein.
        // So sieht man nie Adressleiste oder Seite, auch nicht während des Einblendens.
        if (nav.browserOpen) {
            BrowserScreen(
                browser = browser,
                hub = hub,
                watch = watch,
                history = history,
                onClearHistory = app.settings::clearHistory,
                onDownload = { c, v, t, thumb -> startDownload(c, v, t, thumb, null) },
                onPreview = preview,
                onClose = { nav.browserOpen = false },
                onWatchShowPage = { nav.resolving = false },
                interactive = nav.quiet == null || nav.challenge,
            )
        }
        if (!nav.browserOpen || nav.resolving && !nav.challenge) {
            Column(Modifier.fillMaxSize().background(Palette.bg)) {
                Box(Modifier.weight(1f)) {
                    LibraryHost(nav, libState, libActions, detailActions, downloadActions) {
                        SettingsScreen(
                            settings = app.settings,
                            onClearBrowsingData = browser::clearBrowsingData,
                            onToast = { toast(it) },
                            onProfiles = { nav.profileManage = true; nav.profilePicker = true },
                            onBack = libActions.back,
                        )
                    }
                }
                FloatingNav(nav.tab, activeCount = downloads.count { it.active }) { t ->
                    // Nochmal auf den aktiven Tab: zurück zu seinem Anfang.
                    if (t == nav.tab) { nav.forward = false; nav.stack.clear() } else nav.selectTab(t)
                }
            }
        }

        nav.linkUrl?.let { url ->
            LinkSheet(
                url = url,
                actions = LinkActions(
                    dismiss = { nav.linkUrl = null },
                    watchNow = { u ->
                        nav.linkUrl = null
                        nav.resolveInfo = ResolveInfo(title = Format.host(u), eyebrow = tr("Geteilter Link", "Shared link"))
                        nav.closeInstant = false
                        nav.resolving = true
                        nav.browserOpen = true
                        watch.start(u, WatchController.Request(fromLibrary = true))
                    },
                    openInBrowser = { u ->
                        nav.linkUrl = null
                        nav.browserOpen = true
                        browser.load(u)
                    },
                ),
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { data ->
            Snackbar(
                data,
                shape = RoundedCornerShape(16.dp),
                containerColor = Palette.ink,
                contentColor = Palette.onInk,
                actionColor = Palette.onInk,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        // Edge-to-edge: Inhalt scrollt nicht sichtbar unter die Statusleiste. Start und Detail
        // ziehen ihr Bild bis unter die Leiste und decken sie selbst ab, sobald gescrollt wird.
        val fullBleed = !browserShown && ((nav.stack.isEmpty() && nav.tab == MainActivity.TAB_HOME) || nav.stack.lastOrNull() is Route.Detail)
        if (!fullBleed) Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Palette.bg)
        )

        QuietSearchBar(
            job = nav.quiet,
            queued = nav.queue.size,
            onCancel = {
                nav.queue.clear()
                watch.cancel()
            },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp),
        )

        nav.previewId?.let { id ->
            libState.anyById(id)?.let { t -> QuickSheet(t, libState, libActions, onDismiss = { nav.previewId = null }) }
        }
        if (nav.librariesOpen) de.marne.streamplayer.library.LibrariesSheet(
            libState,
            de.marne.streamplayer.library.LibrariesActions(
                select = { id ->
                    app.settings.setActiveLibrary(activeProfile.id, id)
                    nav.selectTab(MainActivity.TAB_HOME)
                },
                create = { name, color ->
                    val l = app.library.createLibrary(name, color)
                    app.settings.setActiveLibrary(activeProfile.id, l.id)
                    nav.selectTab(MainActivity.TAB_HOME)
                    toast(tr("„${l.label}“ angelegt. Titel kommen per Katalog oder über „Bearbeiten → Bibliothek“ hinein.", "“${l.label}” created. Add titles by catalog or via “Edit → Library”."))
                },
                update = { id, name, color ->
                    app.library.updateLibrary(id, name, color)
                    app.library.libraries.value.firstOrNull { it.id == id }?.takeIf { it.shared }?.let { l ->
                        scope.launch { runCatching { app.sharing.updateMeta(l) } }
                    }
                },
                delete = { id ->
                    val l = app.library.libraries.value.firstOrNull { it.id == id }
                    scope.launch {
                        when {
                            l == null -> Unit
                            // Freundes-Bibliothek: verlassen (Titel weg). Eigene geteilte: erst das Teilen beenden.
                            l.joined -> app.sharing.leave(l)
                            else -> {
                                if (l.shared) runCatching { app.sharing.stop(l) }
                                app.library.deleteLibrary(id)
                            }
                        }
                    }
                },
                sendInvite = { text ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, tr("Einladung schicken", "Send invite")))
                },
                join = { text -> de.marne.streamplayer.account.Sharing.codeIn(text)?.let { nav.joinCode = it } },
                toast = { toast(it) },
            ),
            onDismiss = { nav.librariesOpen = false },
        )
        nav.importAsk?.let { uri ->
            de.marne.streamplayer.library.LibraryPickSheet(
                tr("Katalog", "Catalog"), tr("In welche Bibliothek?", "Into which library?"),
                libraries.filterNot { it.joined }, if (activeLibrary == de.marne.streamplayer.library.Library.ALL) null else activeLibrary,
                onPick = { doImport(uri, it) }, onDismiss = { nav.importAsk = null },
            )
        }
        nav.offlineFor?.let { (id, key) ->
            val t = libState.anyById(id)
            val parsed = de.marne.streamplayer.library.Keys.parse(key)
            if (t == null || parsed == null) LaunchedEffect(Unit) { nav.offlineFor = null }
            else {
                val ep = if (parsed.second != null && parsed.third != null) t.episode(parsed.second!!, parsed.third!!) else null
                val st = libState.offline(t, key)
                de.marne.streamplayer.library.OfflineSheet(
                    eyebrow = if (ep != null) "${t.title} · ${ep.label}" else tr("Film", "Movie"),
                    title = ep?.title ?: ep?.label ?: t.title,
                    state = st,
                    actions = de.marne.streamplayer.library.OfflineSheetActions(
                        play = { resolve(t, ep, false) },
                        pause = { (st as? de.marne.streamplayer.download.OfflineState.Loading)?.item?.let { DownloadService.pause(context, it.id) } },
                        resume = { (st as? de.marne.streamplayer.download.OfflineState.Stopped)?.item?.let { DownloadService.resume(context, it.id) } },
                        cancel = {
                            when (st) {
                                is de.marne.streamplayer.download.OfflineState.Waiting -> cancelPending(key)
                                is de.marne.streamplayer.download.OfflineState.Loading -> DownloadService.remove(context, st.item.id)
                                is de.marne.streamplayer.download.OfflineState.Stopped -> DownloadService.remove(context, st.item.id)
                                else -> Unit
                            }
                        },
                        delete = {
                            deleteOfflineKey(t, key)
                            toast(tr("Download gelöscht.", "Download deleted."))
                        },
                        share = (st as? de.marne.streamplayer.download.OfflineState.Done)?.item?.let { d -> { share(context, d) } },
                    ),
                    onDismiss = { nav.offlineFor = null },
                )
            }
        }
        nav.collectFor?.let { id ->
            libState.anyById(id)?.let { t -> de.marne.streamplayer.library.CollectionSheet(t, libState, onDismiss = { nav.collectFor = null }) }
        }
        if (nav.naming) de.marne.streamplayer.library.CollectionNameDialog(tr("Neue Sammlung", "New collection"), "", onDismiss = { nav.naming = false }) { name ->
            val id = app.profiles.createCollection(name)
            nav.naming = false
            nav.push(Route.Grid(de.marne.streamplayer.library.GridSpec("collection", id)))
        }
        if (nav.categories) CategorySheet(libState, nav.categoriesKind, libActions, onDismiss = { nav.categories = false })

        ResolveScreen(
            visible = nav.resolving && nav.quiet == null && !nav.challenge,
            instant = nav.closeInstant,
            info = nav.resolveInfo,
            state = watchState,
            onCancel = watch::cancel,
            onShowPage = {
                nav.resolving = false
                watch.showPage()
            },
        )

        de.marne.streamplayer.account.ProfilePicker(
            visible = nav.profilePicker,
            startManaging = nav.profileManage,
            canDismiss = !nav.profileGate,
            onDone = { nav.profilePicker = false; nav.profileGate = false; app.profiles.chosen() },
        )

        de.marne.streamplayer.update.UpdateOffer("Streamplayer")

        // Einmal sagen, dass Zahlen geschickt werden – und wie man es abschaltet. Nicht über „Wer schaut?“.
        var usageNotice by remember { mutableStateOf(!app.settings.usageNoticeShown) }
        if (usageNotice && !nav.profilePicker) androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            containerColor = Palette.surface,
            title = { Text(tr("Nutzungsstatistik", "Usage statistics"), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    tr(
                        "Damit Marne sieht, ob die App läuft, schickt sie Zahlen: wie oft und wie lange geschaut wird, Downloads, Abstürze und auf welchen Domains die Video-Suche scheitert. Keine Titel, keine Seitenadressen, keine Suchbegriffe. Abschalten geht jederzeit unter Einstellungen → Datenschutz – dann wird auch das schon Geschickte gelöscht.",
                        "So Marne can see whether the app works, it sends numbers: how often and how long you watch, downloads, crashes and on which domains finding a video fails. No titles, no page addresses, no search terms. Turn it off anytime in Settings → Privacy – what was already sent gets deleted too.",
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
            },
            confirmButton = { QuietButton(tr("Verstanden", "Got it"), color = Palette.text) { app.settings.usageNoticeShown = true; usageNotice = false } },
            dismissButton = {
                QuietButton(tr("Abschalten", "Turn off"), color = Palette.muted) {
                    app.settings.usageNoticeShown = true
                    usageNotice = false
                    app.settings.setUsageStats(false)
                    de.marne.streamplayer.stats.Usage.forget()
                }
            },
        )

        fullscreen?.let { view -> FullscreenHost(view, onExit = browser::exitFullscreen) }
    }
}

/**
 * Kleine Leiste über der Navigation, solange ein Download sein Video noch sucht: welcher, wie
 * viele warten, Abbrechen. Kommt von unten, geht nach unten – die Bibliothek bleibt bedienbar.
 */
@Composable
private fun QuietSearchBar(job: QuietJob?, queued: Int, onCancel: () -> Unit, modifier: Modifier) {
    var shown by remember { mutableStateOf(job) }
    if (job != null) shown = job
    val spring = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
    val bouncy = androidx.compose.animation.core.CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    androidx.compose.animation.AnimatedVisibility(
        visible = job != null,
        modifier = modifier,
        enter = androidx.compose.animation.slideInVertically(tween(420, easing = bouncy)) { it } + androidx.compose.animation.fadeIn(tween(220)),
        exit = androidx.compose.animation.slideOutVertically(tween(320, easing = spring)) { it } + androidx.compose.animation.fadeOut(tween(220)),
    ) {
        val j = shown ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.ink)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Palette.onInk, strokeWidth = 2.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(tr("Download wird vorbereitet", "Preparing download"), style = MaterialTheme.typography.labelMedium, color = Palette.onInk.copy(alpha = 0.7f))
                Text(
                    j.label + if (queued > 0) tr("  ·  $queued weitere", "  ·  $queued more") else "",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.onInk, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.IconButton(onClick = onCancel) { GIcon(Ic.close, tint = Palette.onInk, size = 18.dp) }
        }
    }
}

/**
 * Inhalt über der Leiste: Tab oder oberste Seite des Stapels. Hinein schiebt sich die neue Seite
 * von rechts herein, zurück gleitet sie nach rechts weg (kürzer) – Tabs untereinander blenden nur.
 * Jede Seite behält ihren Zustand (Scrollen, Reiter), solange sie im Stapel liegt.
 */
@Composable
private fun LibraryHost(
    nav: AppNav,
    state: LibState,
    actions: LibActions,
    detail: DetailActions,
    downloadActions: DownloadActions,
    settings: @Composable () -> Unit,
) {
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val route = nav.stack.lastOrNull()
    val target = (route?.key ?: "tab:${nav.tab}") to route
    val spring = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
    // Ein Rahmen für alle Seiten: darin kann ein Cover von einer Seite in die nächste fliegen.
    androidx.compose.animation.SharedTransitionLayout {
    val sharedScope = this
    androidx.compose.animation.AnimatedContent(
        targetState = target,
        contentKey = { it.first },
        transitionSpec = {
            val tabs = initialState.second == null && targetState.second == null
            // Detail mit Cover-Flug: die Seiten blenden nur, bewegen tut sich allein das Cover –
            // zwei bewegte Dinge (Seite schiebt, Cover fliegt) sähe man als Durcheinander.
            val flight = (targetState.second as? Route.Detail)?.origin != null && nav.forward ||
                (initialState.second as? Route.Detail)?.origin != null && !nav.forward
            when {
                tabs -> androidx.compose.animation.fadeIn(tween(320, easing = spring)) togetherWith androidx.compose.animation.fadeOut(tween(200))
                flight -> androidx.compose.animation.fadeIn(tween(360, delayMillis = 60, easing = spring)) togetherWith androidx.compose.animation.fadeOut(tween(240, easing = spring))
                nav.forward -> (androidx.compose.animation.slideInHorizontally(tween(420, easing = spring)) { it / 3 } + androidx.compose.animation.fadeIn(tween(320))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(420, easing = spring)) { -it / 10 } + androidx.compose.animation.fadeOut(tween(220)))
                else -> (androidx.compose.animation.slideInHorizontally(tween(380, easing = spring)) { -it / 10 } + androidx.compose.animation.fadeIn(tween(280))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(380, easing = spring)) { it / 3 } + androidx.compose.animation.fadeOut(tween(200)))
            }.apply { targetContentZIndex = if (nav.forward) 1f else 0f }
        },
        label = "page",
    ) { (key, r) ->
        androidx.compose.runtime.CompositionLocalProvider(
            de.marne.streamplayer.library.LocalSharedScope provides sharedScope,
            de.marne.streamplayer.library.LocalPageScope provides this,
        ) {
        holder.SaveableStateProvider(key) {
            when (r) {
                is Route.Detail -> {
                    val t = state.anyById(r.id)
                    if (t != null) DetailScreen(t, state, actions, detail, r.origin)
                    else LaunchedEffect(Unit) { nav.pop() } // entfernt, während die Seite offen war
                }
                is Route.Grid -> GridScreen(r.spec, state, actions)
                Route.Downloads -> DownloadsScreen(
                    state,
                    pending = listOfNotNull(nav.quiet?.let { PendingRow(it.key, it.label, true) }) + nav.queue.map { PendingRow(it.key, it.label, false) },
                    actions = downloadActions,
                    onBack = actions.back,
                )
                Route.Settings -> settings()
                null -> when (nav.tab) {
                    MainActivity.TAB_DISCOVER -> DiscoverScreen(state, actions, nav.focusSearch) { nav.focusSearch = false }
                    MainActivity.TAB_MINE -> MineScreen(state, actions)
                    else -> HomeScreen(state, actions)
                }
            }
        }
        }
    }
    }
}

/**
 * Schwebende Leiste: drei Ziele in einer Kapsel, eine Markierung gleitet unter das gewählte –
 * ein Objekt, das wandert, statt dass an zwei Stellen etwas an- und ausgeht. Federt beim Ankommen.
 */
@Composable
private fun FloatingNav(selected: Int, activeCount: Int, onSelect: (Int) -> Unit) {
    val bouncy = androidx.compose.animation.core.CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().background(Palette.bg).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(60.dp)
                .shadow(14.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f))
                .clip(shape).background(Palette.surface).border(1.dp, Palette.line, shape),
        ) {
            val w = maxWidth / tabs.size
            val x by androidx.compose.animation.core.animateDpAsState(w * selected, tween(420, easing = bouncy), label = "navPill")
            Box(Modifier.padding(6.dp).width(w - 12.dp).fillMaxSize().offset(x = x).clip(RoundedCornerShape(24.dp)).background(Palette.surface3))
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, t ->
                    val isSel = i == selected
                    val tint by androidx.compose.animation.animateColorAsState(if (isSel) Palette.text else Palette.muted, tween(220), label = "navTint")
                    Row(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp))
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onSelect(i) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Icon(painterResource(if (isSel) t.iconSelected else t.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            if (i == MainActivity.TAB_MINE && activeCount > 0) {
                                // Messing markiert Fortschritt: die Zahl laufender Downloads.
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).size(16.dp).clip(CircleShape).background(Palette.brass), contentAlignment = Alignment.Center) {
                                    Text("$activeCount", style = MaterialTheme.typography.labelSmall, color = Palette.onInk)
                                }
                            }
                        }
                        // Beschriftung nur beim gewählten Ziel: ruhiger, und die Kapsel bleibt schmal.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSel,
                            enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandHorizontally(tween(320, easing = bouncy)),
                            exit = androidx.compose.animation.fadeOut(tween(160)) + androidx.compose.animation.shrinkHorizontally(tween(220)),
                        ) {
                            Text(t.label(), style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBar(selected: Int, activeCount: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(Palette.bg)) {
        Hairline()
        ShortNavigationBar(containerColor = Palette.bg, contentColor = Palette.text) {
            tabs.forEachIndexed { i, t ->
                val isSel = i == selected
                ShortNavigationBarItem(
                    selected = isSel,
                    onClick = { onSelect(i) },
                    icon = {
                        if (i == MainActivity.TAB_MINE && activeCount > 0) {
                            // Messing markiert Fortschritt: die Zahl laufender Downloads.
                            BadgedBox(badge = { Badge(containerColor = Palette.brass, contentColor = Palette.onInk) { Text("$activeCount") } }) {
                                Icon(painterResource(if (isSel) t.iconSelected else t.icon), contentDescription = null)
                            }
                        } else {
                            Icon(painterResource(if (isSel) t.iconSelected else t.icon), contentDescription = null)
                        }
                    },
                    label = { Text(t.label()) },
                    colors = ShortNavigationBarItemDefaults.colors(
                        selectedIconColor = Palette.text,
                        selectedTextColorTopIconPosition = Palette.text,
                        selectedTextColorStartIconPosition = Palette.text,
                        selectedIndicatorColor = Palette.surface3,
                        unselectedIconColor = Palette.muted,
                        unselectedTextColor = Palette.muted,
                    ),
                )
            }
        }
    }
}

/** Vollbild-Video der Seite: über allem, Systemleisten aus, Zurück beendet es. */
@Composable
private fun FullscreenHost(view: View, onExit: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { onExit() }
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun share(context: android.content.Context, d: DownloadItem) {
    val uri = d.fileUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType(d.mime ?: "video/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, d.title))
}

private fun openWith(context: android.content.Context, d: DownloadItem) {
    val uri = d.fileUri?.let(Uri::parse) ?: return
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, d.mime ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, d.title)) }
}
