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
import de.marne.streamplayer.library.LibraryActions
import de.marne.streamplayer.library.LibraryScreen
import de.marne.streamplayer.player.PlayerActivity
import de.marne.streamplayer.settings.SettingsScreen
import de.marne.streamplayer.ui.design.Hairline
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.isAppDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var hub: DetectionHub
    private lateinit var browser: BrowserController
    private lateinit var watch: WatchController
    private val tab = mutableIntStateOf(TAB_BROWSER)

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
                AppRoot(browser, hub, watch, tab)
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
            ACTION_SHOW_DOWNLOADS -> tab.intValue = TAB_DOWNLOADS
        }
    }

    /** Geteilter oder geöffneter Link: im Modus „Nur ansehen“ direkt abspielen. */
    private fun openLink(url: String) {
        tab.intValue = TAB_BROWSER
        if (App.instance.settings.watchMode.value) watch.start(url) else browser.load(url)
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
        const val TAB_BROWSER = 0
        const val TAB_LIBRARY = 1
        const val TAB_DOWNLOADS = 2
        const val TAB_SETTINGS = 3
    }
}

private data class TabSpec(val label: String, val icon: Int, val iconSelected: Int)

private val tabs = listOf(
    TabSpec("Browser", Ic.browser, Ic.browserFilled),
    TabSpec("Bibliothek", Ic.library, Ic.libraryFilled),
    TabSpec("Downloads", Ic.downloads, Ic.downloadsFilled),
    TabSpec("Einstellungen", Ic.settings, Ic.settingsFilled),
)

@Composable
private fun AppRoot(browser: BrowserController, hub: DetectionHub, watch: WatchController, tab: MutableIntState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val history by app.settings.history.collectAsStateWithLifecycle()
    val downloads by app.downloads.items.collectAsStateWithLifecycle()
    val fullscreen by browser.fullscreen.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toast(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    val startDownload: (Candidate, Variant?, String, String?) -> Unit = { c, v, title, thumb ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.downloads.enqueue(c, v, title, thumb, browser.userAgent)
        DownloadService.kick(context)
        toast("Läuft. Du findest es unter Downloads.", "Ansehen") { tab.intValue = MainActivity.TAB_DOWNLOADS }
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

    val actions = remember {
        DownloadActions(
            play = { d -> d.fileUri?.let { context.startActivity(PlayerActivity.intent(context, Uri.parse(it), d.title, d.mime)) } },
            pause = { d -> DownloadService.pause(context, d.id) },
            resume = { d -> DownloadService.resume(context, d.id) },
            remove = { d -> DownloadService.remove(context, d.id) },
            share = { d -> share(context, d) },
            openWith = { d -> openWith(context, d) },
            openPage = { d ->
                tab.intValue = MainActivity.TAB_BROWSER
                browser.load(d.pageUrl)
            },
            goBrowse = { tab.intValue = MainActivity.TAB_BROWSER },
            saveToLibrary = { d ->
                d.fileUri?.let { app.library.addFile(it, d.title, d.pageUrl, d.thumbnail, d.durationMs, d.mime) }
                toast("In der Bibliothek.", "Ansehen") { tab.intValue = MainActivity.TAB_LIBRARY }
            },
        )
    }

    val library by app.library.items.collectAsStateWithLifecycle()
    val libraryActions = remember {
        LibraryActions(
            watch = { item ->
                // Gemerkter Link: direkt im Player, über „Nur ansehen“.
                tab.intValue = MainActivity.TAB_BROWSER
                watch.start(item.url)
            },
            play = { item -> context.startActivity(PlayerActivity.intent(context, Uri.parse(item.url), item.title, item.mime)) },
            openPage = { item ->
                tab.intValue = MainActivity.TAB_BROWSER
                browser.load(item.pageUrl ?: item.url)
            },
            addLink = { url ->
                app.library.addLink(url, null, null)
                toast("Gemerkt. Titel und Bild kommen beim ersten Ansehen.")
            },
            rename = { item, title -> app.library.rename(item.id, title) },
            remove = { item ->
                app.library.remove(item.id)
                toast("Aus der Bibliothek entfernt.")
            },
            goBrowse = { tab.intValue = MainActivity.TAB_BROWSER },
        )
    }

    BackHandler(enabled = tab.intValue != MainActivity.TAB_BROWSER) { tab.intValue = MainActivity.TAB_BROWSER }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(tab.intValue, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tab") { t ->
                    when (t) {
                        MainActivity.TAB_BROWSER -> BrowserScreen(
                            browser = browser,
                            hub = hub,
                            watch = watch,
                            history = history,
                            onClearHistory = app.settings::clearHistory,
                            onDownload = startDownload,
                            onPreview = preview,
                        )
                        MainActivity.TAB_LIBRARY -> LibraryScreen(library, libraryActions)
                        MainActivity.TAB_DOWNLOADS -> DownloadsScreen(downloads, actions)
                        else -> SettingsScreen(
                            settings = app.settings,
                            onClearBrowsingData = browser::clearBrowsingData,
                            onToast = { toast(it) },
                        )
                    }
                }
            }
            NavBar(tab.intValue, activeCount = downloads.count { it.active }) { tab.intValue = it }
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
