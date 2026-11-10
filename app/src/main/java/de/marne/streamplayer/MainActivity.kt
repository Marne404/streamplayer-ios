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
    val queue = androidx.compose.runtime.mutableStateListOf<QuietJob>()
    /** „Wer schaut?“: offen, und ob gleich im Verwalten-Modus (aus den Einstellungen). */
    var profilePicker by mutableStateOf(false)
    var profileManage by mutableStateOf(false)
    /** Auswahl muss getroffen werden (gesperrtes Profil beim Start): nicht wegtippbar. */
    var profileGate by mutableStateOf(false)
}

/** Ein Download, dessen Video noch gesucht wird: Titel, Folge (oder Film), Anzeige, Seite. */
data class QuietJob(val titleId: String, val season: Int?, val episode: Int?, val label: String, val url: String)

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
                if (data.scheme == "http" || data.scheme == "https") openLink(data.toString()) else importCatalog(data)
            }
            Intent.ACTION_SEND -> {
                // Katalog-Datei aus dem Creator (oder einem Dateimanager) – sonst ein Link als Text.
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) importCatalog(stream)
                else intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.let { Regex("""https?://\S+""").find(it)?.value }
                    ?.let(::openLink)
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
    val libState = LibState(titles, progress, activeProfile, enrichStatus, downloads)
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

    fun doImport(uri: Uri) {
        scope.launch {
            runCatching { app.importer.import(uri) }
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
        app.downloads.enqueue(c, v, title, thumb, browser.userAgent, libraryKey)
        DownloadService.kick(context)
        toast(tr("Läuft. Du findest es unter Meins → Downloads.", "Downloading. Find it under My Stuff → Downloads."), tr("Ansehen", "View")) {
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
        val key = if (job.season != null && job.episode != null) "e:${job.titleId}:${job.season}:${job.episode}" else "m:${job.titleId}"
        watch.start(job.url, WatchController.Request(key = key, label = job.label, titleId = job.titleId, download = true, fromLibrary = true))
    }

    /**
     * Film oder Folge abspielen bzw. laden. Offline-Datei: direkt. Sonst Seite verdeckt laden,
     * Stream finden (WatchController), danach zurück in die Bibliothek.
     */
    fun resolve(t: LibTitle, ep: Episode?, download: Boolean) {
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
                toast(tr("Vorgemerkt: $label", "Queued: $label"))
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

    fun playTitle(t: LibTitle) {
        if (t.kind == TitleKind.MOVIE) resolve(t, null, false)
        else resolve(t, Shelves.nextFor(t, app.progress.all.value)?.first ?: t.episodes().firstOrNull() ?: return, false)
    }

    // Stream gefunden: abspielen oder laden – und bei Aufträgen aus der Bibliothek dorthin zurück.
    LaunchedEffect(watchState) {
        when (val s = watchState) {
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
            saveToLibrary = { d ->
                d.fileUri?.let { uri ->
                    val t = app.library.addOfflineMovie(uri, d.title, d.pageUrl, d.thumbnail, d.durationMs)
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
    val browserShown = nav.browserOpen && nav.quiet == null
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
                interactive = nav.quiet == null,
            )
        }
        if (!nav.browserOpen || nav.resolving) {
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
            libState.byId(id)?.let { t -> QuickSheet(t, libState, libActions, onDismiss = { nav.previewId = null }) }
        }
        nav.collectFor?.let { id ->
            libState.byId(id)?.let { t -> de.marne.streamplayer.library.CollectionSheet(t, libState, onDismiss = { nav.collectFor = null }) }
        }
        if (nav.naming) de.marne.streamplayer.library.CollectionNameDialog(tr("Neue Sammlung", "New collection"), "", onDismiss = { nav.naming = false }) { name ->
            val id = app.profiles.createCollection(name)
            nav.naming = false
            nav.push(Route.Grid(de.marne.streamplayer.library.GridSpec("collection", id)))
        }
        if (nav.categories) CategorySheet(libState, nav.categoriesKind, libActions, onDismiss = { nav.categories = false })

        ResolveScreen(
            visible = nav.resolving && nav.quiet == null,
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
                    val t = state.byId(r.id)
                    if (t != null) DetailScreen(t, state, actions, detail, r.origin)
                    else LaunchedEffect(Unit) { nav.pop() } // entfernt, während die Seite offen war
                }
                is Route.Grid -> GridScreen(r.spec, state, actions)
                Route.Downloads -> DownloadsScreen(state.downloads, downloadActions, onBack = actions.back)
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
