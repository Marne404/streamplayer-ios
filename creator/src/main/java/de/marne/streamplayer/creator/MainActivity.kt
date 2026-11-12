package de.marne.streamplayer.creator

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import de.marne.streamplayer.ui.design.Bar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import de.marne.streamplayer.ui.design.OutlinePill
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.importer.Importer
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.design.isAppDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = CreatorApp.instance.prefs
        if (savedInstanceState == null) handleShare(intent)
        de.marne.streamplayer.update.Updater.check(this)
        setContent {
            val theme by prefs.theme.collectAsState()
            val dark = isAppDark(theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            IvoryInkTheme(dark) { CreatorRoot() }
        }
    }

    // Große Kataloge werden nur alle paar Sekunden gesichert – beim Verlassen der App sofort.
    override fun onStop() {
        super.onStop()
        CreatorApp.instance.store.flushLater()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Geteilter Text mit Adresse(n) oder eine geteilte Datei mit Links → „Aus Link übernehmen“. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val imports = CreatorApp.instance.imports
        val stream = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
        if (stream != null) {
            imports.openFile(this, stream)
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (Importer.normalize(text) != null) imports.request(text)
    }
}

@Composable
private fun CreatorRoot() {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val imports = CreatorApp.instance.imports
    val requests by imports.requests.collectAsState()
    val importState by imports.state.collectAsState()
    val notice by imports.notice.collectAsState()
    LaunchedEffect(requests) {
        if (requests > 0) { editing?.let { CreatorApp.instance.store.finalizeId(it) }; editing = null; settings = false; importing = true }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }

    fun closeEditor() {
        editing?.let { CreatorApp.instance.store.finalizeId(it) }
        editing = null
    }
    LaunchedEffect(notice) {
        notice?.let { toast(it); imports.notice.value = null }
    }
    BackHandler(enabled = editing != null || settings || importing) {
        when {
            // Zurück aus einer laufenden Analyse oder einem Entwurf: eine Stufe zurück zur Eingabe.
            // Massenimport: erst anhalten (Fertiges bleibt), dann zurück zur Eingabe.
            importing && editing == null && importState is ImportSession.State.Batch -> imports.stop()
            importing && editing == null && (importState is ImportSession.State.Running || importState is ImportSession.State.Done) -> imports.cancel()
            importing && editing == null -> { imports.reset(); importing = false }
            else -> { closeEditor(); settings = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        val screen = when {
            editing != null -> "edit:$editing"
            settings -> "settings"
            importing -> "import"
            else -> "list"
        }
        Crossfade(screen.substringBefore(':'), animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "screen") { s ->
            when (s) {
                "edit" -> editing?.let { id -> EditorScreen(id, onBack = ::closeEditor, onIdChange = { editing = it }) }
                "settings" -> SettingsScreen(onBack = { settings = false }, toast = ::toast)
                "import" -> ImportScreen(
                    onBack = { imports.reset(); importing = false },
                    onEdit = { id -> importing = false; editing = id },
                    toast = ::toast,
                )
                else -> CatalogScreen(onEdit = { editing = it }, onSettings = { settings = true }, onImport = { importing = true }, toast = ::toast)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = Palette.ink, contentColor = Palette.onInk, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Palette.bg))
        de.marne.streamplayer.update.UpdateOffer("Streamplayer Creator")
    }
}

@Composable
private fun CatalogScreen(onEdit: (String) -> Unit, onSettings: () -> Unit, onImport: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val app = CreatorApp.instance
    val store = app.store
    val ready by store.ready.collectAsState()
    if (!ready) {
        // Großer Katalog: wird im Hintergrund gelesen. Bis dahin nichts bearbeiten – sonst überschriebe ein halber Stand den ganzen.
        Box(Modifier.fillMaxSize().background(Palette.bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(color = Palette.ink, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                VSpace(16.dp)
                Text("Opening your catalog …", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
        }
        return
    }
    val catalog by store.catalog.collectAsState()
    val bulk by app.imports.bulk.state.collectAsState()
    val spares by store.spares.collectAsState()
    val filling by app.fill.state.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scope = rememberCoroutineScope()
    val playerThere = remember { Share.playerInstalled(context) }

    var kind by rememberSaveable { mutableStateOf(CatalogType.SERIES) }
    var filter by rememberSaveable { mutableStateOf(CatalogView.Filter.ALL) }
    var sort by rememberSaveable { mutableStateOf(CatalogView.Sort.AZ) }
    var query by rememberSaveable { mutableStateOf("") }
    // Filtern, Sortieren und Zählen über Tausende Titel: im Hintergrund, die Liste bleibt dabei stehen.
    val view by androidx.compose.runtime.produceState(CatalogView.EMPTY, catalog, kind, filter, sort, query) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(120)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { CatalogView.of(catalog, kind, filter, sort, query) }
    }
    // Die gewählte Art ist leer, die andere nicht (nur Filme im Katalog): dorthin wechseln.
    LaunchedEffect(view.series, view.movies) {
        if (kind == CatalogType.SERIES && view.series == 0 && view.movies > 0) kind = CatalogType.MOVIE
        if (kind == CatalogType.MOVIE && view.movies == 0 && view.series > 0) kind = CatalogType.SERIES
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val (c, _) = store.forExport()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { CatalogJson.write(c, it) }
                }
            }.onSuccess { toast("Saved.") }.onFailure { toast("Couldn’t save.") }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { CatalogJson.read(it, strict = true, skipBroken = false).first }
                }
                store.merge(c)
            }.onSuccess { toast(if (it == 1) "1 title imported." else "%,d titles imported.".format(it)) }.onFailure { toast(it.message ?: "Can’t read the file.") }
        }
    }
    fun send() = scope.launch {
        val (c, left) = store.forExport()
        if (left > 0) toast(if (left == 1) "1 title without a name stays here." else "$left titles without a name stay here.")
        runCatching { Share.sendToPlayer(context, c, catalog.name ?: "katalog") }.onFailure { toast("Couldn’t prepare the catalog.") }
    }

    fun create(type: CatalogType) {
        val t = newTitle(type)
        store.put(t)
        onEdit(t.id)
    }

    // Index der ersten Titelzeile in der LazyColumn – für die Buchstabenleiste und „nach oben“:
    // Kopf, Knöpfe, ggf. Linkliste und alte Kopien, dann Suche und Filter.
    val firstRow = 3 + (if (bulk != null) 1 else 0) + (if (spares != null) 1 else 0)

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 96.dp),
        ) {
            item(key = "header") {
                ScreenHeader(
                    title = when (catalog.titles.size) { 0 -> "What are you planning?"; 1 -> "One title."; else -> "%,d titles.".format(catalog.titles.size) },
                    eyebrow = "Creator",
                    subtitle = catalog.name,
                    trailing = {
                        Row {
                            if (catalog.titles.isNotEmpty()) ShareMenu(
                                playerThere = playerThere,
                                onSend = { send() },
                                onSave = { save.launch(CatalogJson.slug(catalog.name ?: "katalog") + ".json") },
                                onOpen = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                            )
                            IconButton(onClick = onSettings) { GIcon(Ic.settings, tint = Palette.text) }
                        }
                    },
                )
            }
            item(key = "actions") {
                VSpace(24.dp)
                SecondaryButton("Import from link", icon = Ic.browser, onClick = onImport)
                VSpace(12.dp)
                Row {
                    Box(Modifier.weight(1f)) { SecondaryButton("Show", icon = Ic.tv) { create(CatalogType.SERIES) } }
                    HSpace(12.dp)
                    Box(Modifier.weight(1f)) { SecondaryButton("Movie", icon = Ic.movie) { create(CatalogType.MOVIE) } }
                }
                if (catalog.titles.isNotEmpty()) {
                    VSpace(12.dp)
                    PrimaryButton(if (playerThere) "Send to Streamplayer" else "Share catalog", icon = Ic.share) { send() }
                }
            }
            // Große Liste in Arbeit (oder angehalten): Stand hier sichtbar, ein Tipp öffnet sie.
            bulk?.let { b ->
                item(key = "bulk") {
                    VSpace(16.dp)
                    ListGroup {
                        ListRow(
                            "Big link list",
                            subtitle = "%,d of %,d · %,d added · %,d failed".format(b.done, b.total, b.added, b.failed) + if (b.running) "" else " · paused",
                            icon = Ic.importFile,
                            onClick = onImport,
                        )
                    }
                }
            }
            spares?.let { sp ->
                item(key = "spares") {
                    VSpace(16.dp)
                    SparesCard(sp, toast)
                }
            }
            if (catalog.titles.isEmpty()) {
                item(key = "empty") {
                    VSpace(28.dp)
                    NoticeCard(
                        icon = Ic.importFile,
                        title = "Already have a catalog?",
                        text = "Open a JSON file and keep editing.",
                        action = "Open",
                        onAction = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    )
                }
            } else {
                item(key = "controls") {
                    VSpace(28.dp)
                    Segmented(
                        listOf(CatalogType.SERIES.name to "Shows · %,d".format(view.series), CatalogType.MOVIE.name to "Movies · %,d".format(view.movies)),
                        kind.name,
                        onSelect = { kind = CatalogType.valueOf(it); filter = CatalogView.Filter.ALL },
                    )
                    VSpace(12.dp)
                    IvoryField(query, { query = it }, if (view.total > 0) "Search %,d %s".format(view.total, if (kind == CatalogType.SERIES) "shows" else "movies") else "Search")
                    VSpace(4.dp)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        item { OutlinePill(if (sort == CatalogView.Sort.AZ) "A–Z" else "Newest", icon = Ic.expandMore, onClick = { sort = if (sort == CatalogView.Sort.AZ) CatalogView.Sort.NEW else CatalogView.Sort.AZ }) }
                        item { OutlinePill("All", selected = filter == CatalogView.Filter.ALL, onClick = { filter = CatalogView.Filter.ALL }) }
                        if (view.unlinked > 0 || filter == CatalogView.Filter.NO_TMDB) item {
                            OutlinePill("No TMDB · %,d".format(view.unlinked), selected = filter == CatalogView.Filter.NO_TMDB, onClick = { filter = CatalogView.Filter.NO_TMDB })
                        }
                        if (view.siteInfo > 0 || filter == CatalogView.Filter.SITE) item {
                            OutlinePill("Site cover · %,d".format(view.siteInfo), selected = filter == CatalogView.Filter.SITE, onClick = { filter = CatalogView.Filter.SITE })
                        }
                    }
                    when (filter) {
                        CatalogView.Filter.NO_TMDB -> {
                            VSpace(10.dp)
                            Text("Not linked to TMDB – the Player can't find cover, text and episodes. Tap a title → TMDB → search and pick.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                        }
                        CatalogView.Filter.SITE -> {
                            VSpace(10.dp)
                            Text("Linked to TMDB, but cover and description still come from the website.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                            VSpace(10.dp)
                            val f = filling
                            if (f != null) {
                                Bar(if (f.total == 0) 0f else f.done.toFloat() / f.total, height = 4.dp)
                                VSpace(6.dp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("%,d of %,d updated …".format(f.done, f.total), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                                    QuietButton("Stop", color = Palette.wine) { app.fill.stop() }
                                }
                            } else SecondaryButton("Take cover & text from TMDB (%,d)".format(view.siteInfo), icon = Ic.refresh) {
                                if (app.prefs.tmdbKey.value.isBlank()) toast("Add a TMDB key in Settings first.")
                                else app.fill.start(catalog.titles.filter(::hasSiteInfo).map { it.id })
                            }
                        }
                        else -> {}
                    }
                    VSpace(16.dp)
                    if (view.rows.isEmpty() && view.total > 0) {
                        Text(if (query.isNotBlank()) "Nothing matches “$query”." else "Nothing here.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                // Jede Zeile ein eigenes Element: gezeichnet wird nur, was zu sehen ist. Früher standen alle
                // Titel in einer einzigen Karte – bei 11 000 Serien samt Covern stürzte der Creator ab.
                itemsIndexed(view.rows, key = { _, r -> r.key }, contentType = { _, r -> if (r is CatalogView.Row.Head) 0 else 1 }) { _, r ->
                    when (r) {
                        is CatalogView.Row.Head -> Text(
                            r.label, style = MaterialTheme.typography.labelLarge, color = Palette.muted,
                            modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
                        )
                        is CatalogView.Row.Item -> TitleRow(r, onEdit)
                    }
                }
            }
        }
        if (sort == CatalogView.Sort.AZ && view.letters.size > 3 && view.rows.size > 60) {
            LetterRail(view.letters, list, firstRow, Modifier.align(Alignment.CenterEnd))
        }
        // Weit unten in einer langen Liste: ein Tipp zurück zu Suche und Filtern.
        val far by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > firstRow + 30 } }
        androidx.compose.animation.AnimatedVisibility(
            far,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.9f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.9f),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.ink)
                    .clickable { scope.launch { list.animateScrollToItem((firstRow - 1).coerceAtLeast(0)) } }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GIcon(Ic.search, tint = Palette.onInk, size = 18.dp)
                HSpace(8.dp)
                Text("Search & filter", style = MaterialTheme.typography.labelLarge, color = Palette.onInk)
            }
        }
    }
}

@Composable
private fun ShareMenu(playerThere: Boolean, onSend: () -> Unit, onSave: () -> Unit, onOpen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { GIcon(Ic.more, tint = Palette.text) }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp), containerColor = Palette.surface) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(if (playerThere) "Send to Streamplayer" else "Share catalog") }, leadingIcon = { GIcon(Ic.share, size = 20.dp) }, onClick = { open = false; onSend() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Save as file") }, leadingIcon = { GIcon(Ic.download, size = 20.dp) }, onClick = { open = false; onSave() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Open file") }, leadingIcon = { GIcon(Ic.importFile, size = 20.dp) }, onClick = { open = false; onOpen() })
        }
    }
}

/** Beiseitegelegte Stände (z. B. aus Startversuchen der alten Version): zurückholen oder löschen. */
@Composable
private fun SparesCard(sp: CatalogStore.Spares, toast: (String) -> Unit) {
    val store = CreatorApp.instance.store
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val date = remember(sp.newest) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(sp.newest)) }
    ListGroup {
        ListRow(
            if (sp.files.size == 1) "Older copy of your catalog" else "${sp.files.size} older copies of your catalog",
            subtitle = "%,d titles · %s".format(sp.titles, date),
            icon = Ic.info, showChevron = false,
        )
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            QuietButton("Delete", color = Palette.wine) {
                if (!busy) { busy = true; scope.launch { store.deleteSpares(); busy = false; toast("Copies deleted.") } }
            }
            QuietButton(if (busy) "Working …" else "Add missing titles", color = Palette.text) {
                if (!busy) {
                    busy = true
                    scope.launch {
                        val n = store.restoreSpares()
                        busy = false
                        toast(if (n == 0) "Nothing was missing – copies removed." else "%,d titles added back.".format(n))
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(r: CatalogView.Row.Item, onEdit: (String) -> Unit) {
    val t = r.title
    val big = 22.dp
    val shape = RoundedCornerShape(topStart = if (r.first) big else 0.dp, topEnd = if (r.first) big else 0.dp, bottomStart = if (r.last) big else 0.dp, bottomEnd = if (r.last) big else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.surface)) {
        if (!r.first) RowDivider(inset = 76.dp)
        Row(
            Modifier.fillMaxWidth().clickable { onEdit(t.id) }.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(t.poster, Modifier.size(width = 44.dp, height = 66.dp), radius = 8.dp)
            HSpace(16.dp)
            Column(Modifier.weight(1f)) {
                Text(t.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyLarge, color = Palette.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                r.summary.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            if (r.unlinked) { HSpace(8.dp); de.marne.streamplayer.ui.design.Pill("No TMDB", color = Palette.surface2, textColor = Palette.muted) }
            HSpace(4.dp)
            GIcon(Ic.chevron, tint = Palette.faint, size = 18.dp)
        }
    }
}

/**
 * Buchstabenleiste am rechten Rand: Tippen oder Ziehen springt zum Buchstaben. Bei Tausenden Titeln
 * ist das der Weg durch die Liste; beim Ziehen zeigt eine Blase den Buchstaben unter dem Finger.
 */
@Composable
private fun LetterRail(letters: List<Pair<String, Int>>, list: androidx.compose.foundation.lazy.LazyListState, firstRow: Int, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<Int?>(null) }
    var height by remember { mutableStateOf(1) }
    val scrolling = list.isScrollInProgress || active != null
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (scrolling) 1f else 0.55f, androidx.compose.animation.core.tween(220), label = "rail")
    // Erst sichtbar, wenn die Titel erreicht sind – oben stehen Knöpfe, keine Buchstaben.
    val inList by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex >= firstRow - 1 } }
    val shown by androidx.compose.animation.core.animateFloatAsState(if (inList) 1f else 0f, androidx.compose.animation.core.tween(320), label = "railIn")
    if (shown == 0f) return
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun pick(y: Float) {
        val i = ((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            scope.launch { list.scrollToItem(firstRow + letters[i].second) }
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        active?.let { i ->
            Box(
                Modifier.align(Alignment.Center).size(72.dp).clip(RoundedCornerShape(20.dp)).background(Palette.ink),
                contentAlignment = Alignment.Center,
            ) { Text(letters[i].first, style = MaterialTheme.typography.headlineMedium, color = Palette.onInk) }
        }
        Column(
            Modifier
                .padding(end = 2.dp)
                .fillMaxHeight(0.62f)
                .width(20.dp)
                .alpha(alpha * shown)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ -> pick(change.position.y) }
                }
                .pointerInput(letters) { detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = null }) },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { i, (l, _) ->
                Text(l, style = MaterialTheme.typography.labelSmall, color = if (i == active) Palette.text else Palette.muted)
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val app = CreatorApp.instance
    val key by app.prefs.tmdbKey.collectAsState()
    val lang by app.prefs.language.collectAsState()
    val theme by app.prefs.theme.collectAsState()
    val catalog by app.store.catalog.collectAsState()
    var draft by remember(key) { mutableStateOf(key) }
    var state by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = 48.dp)) {
        item {
            IconButton(onClick = onBack) { GIcon(Ic.back) }
            ScreenHeader(title = "Settings", eyebrow = "Creator")
        }
        item { SectionTitle("Catalog") }
        item { IvoryField(catalog.name.orEmpty(), { app.store.rename(it) }, "My catalog", label = "Name") }
        item { SectionTitle("TMDB") }
        item {
            Text(
                "For finding titles, posters and seasons. Free at themoviedb.org → Settings → API. The key stays on this device and never ends up in the catalog.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
            VSpace(12.dp)
            IvoryField(draft, { draft = it; state = null }, "API key or read access token", label = "Key")
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Save and check", color = Palette.text) {
                    app.prefs.setTmdbKey(draft)
                    state = "Checking …"
                    scope.launch { state = if (draft.isBlank()) "TMDB is off." else if (app.tmdb.check()) "Key works." else "TMDB rejected the key." }
                }
                state?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.contains("rejected")) Palette.wine else Palette.muted) }
            }
            VSpace(8.dp)
            IvoryField(lang, { app.prefs.setLanguage(it) }, java.util.Locale.getDefault().toLanguageTag(), label = "TMDB language")
            Text(
                "Language for titles, descriptions and posters from TMDB, e.g. en-US or de-DE. Defaults to your device language.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
        }
        item { SectionTitle("Appearance") }
        item { Segmented(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), theme, app.prefs::setTheme) }
        item { SectionTitle("About") }
        item { de.marne.streamplayer.ui.design.ListGroup { de.marne.streamplayer.update.UpdateRow() } }
        item {
            SectionTitle("Format")
            Text(
                "Catalogs are JSON in the “streamplayer-catalog” format, version 1. You can also write them by hand; the Player points out errors with their position.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
            QuietButton("Copy example to clipboard", color = Palette.text) {
                val cm = app.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Catalog", EXAMPLE))
                toast("Example copied.")
            }
        }
    }
}

private val EXAMPLE = """
{
  "format": "streamplayer-catalog",
  "version": 1,
  "name": "My catalog",
  "titles": [
    {
      "type": "series",
      "id": "beispiel-serie",
      "title": "Example Show",
      "year": 2020,
      "tmdb": { "id": 1396 },
      "template": "https://example.org/serie/beispiel/staffel-{s}/episode-{e}",
      "seasons": [
        { "number": 1, "episodes": 10 },
        { "number": 2, "episodes": 8, "start": 11 },
        { "number": 0, "episodes": 2, "title": "Specials", "template": "https://example.org/serie/beispiel/special-{e}" }
      ],
      "episodes": [ { "season": 1, "episode": 4, "url": "https://example.org/anders/folge-4" } ]
    },
    {
      "type": "movie",
      "id": "beispiel-film",
      "title": "Example Movie",
      "year": 2010,
      "tmdb": { "query": "Example Movie" },
      "url": "https://example.org/film/beispiel"
    }
  ]
}
""".trimIndent()
