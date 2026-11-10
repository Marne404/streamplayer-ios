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
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.detect.Prober
import de.marne.streamplayer.detect.Variant
import de.marne.streamplayer.download.DownloadItem
import de.marne.streamplayer.download.DownloadService
import de.marne.streamplayer.downloads.DownloadActions
import de.marne.streamplayer.downloads.DownloadsScreen
import de.marne.streamplayer.library.AddActions
import de.marne.streamplayer.library.AddSheet
import de.marne.streamplayer.library.DetailActions
import de.marne.streamplayer.library.DetailScreen
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.HomeActions
import de.marne.streamplayer.library.HomeScreen
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Pattern
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
    var tab by mutableIntStateOf(MainActivity.TAB_LIBRARY)
    /** Browser ist ein Nebenraum über den Tabs, kein Tab mehr. */
    var browserOpen by mutableStateOf(false)
    var detailId by mutableStateOf<String?>(null)
    /** Hinzufügen-Sheet: null = zu, sonst vorbelegter Link (leer = ohne). */
    var addUrl by mutableStateOf<String?>(null)
    /** Browser wurde nur zum Auflösen geöffnet: danach wieder schließen. */
    var resolving by mutableStateOf(false)
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

        setContent {
            val theme by app.settings.theme.collectAsStateWithLifecycle()
            val dark = isAppDark(theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            IvoryInkTheme(dark) {
                AppRoot(browser, hub, watch, nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("http") }?.let(::openLink)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("""https?://\S+""").find(it)?.value }
                ?.let(::openLink)
            ACTION_SHOW_DOWNLOADS -> {
                nav.browserOpen = false
                nav.tab = TAB_DOWNLOADS
            }
        }
    }

    /** Geteilter Link: Hinzufügen-Sheet mit dem Link – dort auch „Nur ansehen“ und „Im Browser öffnen“. */
    private fun openLink(url: String) {
        nav.browserOpen = false
        nav.tab = TAB_LIBRARY
        nav.addUrl = url
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
        const val TAB_LIBRARY = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_SETTINGS = 2
    }
}

private data class TabSpec(val label: String, val icon: Int, val iconSelected: Int)

private val tabs = listOf(
    TabSpec("Bibliothek", Ic.library, Ic.libraryFilled),
    TabSpec("Downloads", Ic.downloads, Ic.downloadsFilled),
    TabSpec("Einstellungen", Ic.settings, Ic.settingsFilled),
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
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toast(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
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
        toast("Läuft. Du findest es unter Downloads.", "Ansehen") {
            nav.browserOpen = false
            nav.tab = MainActivity.TAB_DOWNLOADS
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
                    watch.showPage()
                } else {
                    context.startActivity(
                        PlayerActivity.intent(
                            context, Uri.parse(s.candidate.url), r.label ?: s.title,
                            mime = s.candidate.mime.takeIf { s.candidate.kind != MediaKind.FILE },
                            referer = s.candidate.referer ?: s.candidate.pageUrl,
                            userAgent = browser.userAgent,
                            key = r.key,
                            startMs = r.key?.let(app.progress::resumePosition) ?: 0,
                        )
                    )
                    if (r.fromLibrary) watch.showPage() else watch.playerOpened()
                }
            }
            WatchController.State.Idle -> if (nav.resolving) {
                nav.resolving = false
                nav.browserOpen = false
            }
            else -> Unit
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
                    toast("In der Bibliothek.", "Ansehen") {
                        nav.tab = MainActivity.TAB_LIBRARY
                        nav.detailId = t.id
                    }
                }
            },
        )
    }

    val homeActions = remember {
        HomeActions(
            open = { nav.detailId = it.id },
            play = { c -> resolve(c.title, c.episode, false) },
            playTitle = { playTitle(it) },
            add = { nav.addUrl = "" },
            browser = { nav.browserOpen = true },
        )
    }

    val detailActions = remember {
        DetailActions(
            back = { nav.detailId = null },
            play = { t, e -> resolve(t, e, false) },
            download = { t, e -> resolve(t, e, true) },
            openPage = { url ->
                nav.browserOpen = true
                browser.load(url)
            },
            setWatched = { key, watched -> app.progress.setWatched(key, watched) },
            rename = { t, title -> app.library.rename(t.id, title) },
            editSeries = { t, tpl, seasons -> app.library.updateSeries(t.id, tpl, seasons) },
            remove = { t ->
                app.library.remove(t.id)
                app.progress.removeTitle(t.id)
                nav.detailId = null
                toast("Aus der Bibliothek entfernt.")
            },
        )
    }

    // Zurück: Browser schließen › Detailseite schließen › zur Bibliothek. BrowserScreen hat eigene,
    // spätere Handler (Seitenverlauf, Suche abbrechen) – die gewinnen, solange sie aktiv sind.
    BackHandler(enabled = !nav.browserOpen && nav.tab != MainActivity.TAB_LIBRARY) { nav.tab = MainActivity.TAB_LIBRARY }
    BackHandler(enabled = !nav.browserOpen && nav.tab == MainActivity.TAB_LIBRARY && nav.detailId != null) { nav.detailId = null }
    BackHandler(enabled = nav.browserOpen) { if (watch.active) watch.cancel() else nav.browserOpen = false }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
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
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    Crossfade(nav.tab, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tab") { t ->
                        when (t) {
                            MainActivity.TAB_LIBRARY -> {
                                val detail = nav.detailId?.let { id -> titles.firstOrNull { it.id == id } }
                                Crossfade(detail?.id, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "detail") { id ->
                                    val d = id?.let { i -> titles.firstOrNull { it.id == i } }
                                    if (d != null) DetailScreen(d, progress, detailActions)
                                    else HomeScreen(titles, progress, homeActions)
                                }
                            }
                            MainActivity.TAB_DOWNLOADS -> DownloadsScreen(downloads, downloadActions)
                            else -> SettingsScreen(
                                settings = app.settings,
                                onClearBrowsingData = browser::clearBrowsingData,
                                onToast = { toast(it) },
                            )
                        }
                    }
                }
                NavBar(nav.tab, activeCount = downloads.count { it.active }) { nav.tab = it; if (it == MainActivity.TAB_LIBRARY) nav.detailId = null }
            }
        }

        nav.addUrl?.let { initial ->
            AddSheet(
                initialUrl = initial,
                actions = AddActions(
                    dismiss = { nav.addUrl = null },
                    addMovie = { url ->
                        val t = app.library.addMovie(url, null, null)
                        fetchMeta(t.id, url)
                        nav.addUrl = null
                        nav.tab = MainActivity.TAB_LIBRARY
                        nav.detailId = t.id
                    },
                    addSeries = { name, tpl, seasons ->
                        val t = app.library.addSeries(name, tpl, seasons, null, null)
                        fetchMeta(t.id, Pattern.fill(tpl, 1, 1))
                        nav.addUrl = null
                        nav.tab = MainActivity.TAB_LIBRARY
                        nav.detailId = t.id
                    },
                    watchNow = { url ->
                        nav.addUrl = null
                        nav.resolving = true
                        nav.browserOpen = true
                        watch.start(url, WatchController.Request(fromLibrary = true))
                    },
                    openInBrowser = { url ->
                        nav.addUrl = null
                        nav.browserOpen = true
                        browser.load(url)
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

        // Edge-to-edge: Inhalt scrollt nicht sichtbar unter die Statusleiste.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Palette.bg)
        )

        fullscreen?.let { view -> FullscreenHost(view, onExit = browser::exitFullscreen) }
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
                        if (i == MainActivity.TAB_DOWNLOADS && activeCount > 0) {
                            // Messing markiert Fortschritt: die Zahl laufender Downloads.
                            BadgedBox(badge = { Badge(containerColor = Palette.brass, contentColor = Palette.onInk) { Text("$activeCount") } }) {
                                Icon(painterResource(if (isSel) t.iconSelected else t.icon), contentDescription = null)
                            }
                        } else {
                            Icon(painterResource(if (isSel) t.iconSelected else t.icon), contentDescription = null)
                        }
                    },
                    label = { Text(t.label) },
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
