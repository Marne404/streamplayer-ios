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
    val store = CreatorApp.instance.store
    val catalog by store.catalog.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val series = catalog.titles.filter { it.type == CatalogType.SERIES }.sortedBy { it.title.lowercase() }
    val movies = catalog.titles.filter { it.type == CatalogType.MOVIE }.sortedBy { it.title.lowercase() }
    val playerThere = remember { Share.playerInstalled(context) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().first.toByteArray()) }
        }.onSuccess { toast("Saved.") }.onFailure { toast("Couldn’t save.") }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            store.merge(CatalogJson.parse(text))
        }.onSuccess { toast(if (it == 1) "1 title imported." else "$it titles imported.") }.onFailure { toast(it.message ?: "Can’t read the file.") }
    }

    fun create(type: CatalogType) {
        val t = newTitle(type)
        store.put(t)
        onEdit(t.id)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 48.dp),
    ) {
        item {
            ScreenHeader(
                title = when (catalog.titles.size) { 0 -> "What are you planning?"; 1 -> "One title."; else -> "${catalog.titles.size} titles." },
                eyebrow = "Creator",
                subtitle = catalog.name,
                trailing = { IconButton(onClick = onSettings) { GIcon(Ic.settings, tint = Palette.text) } },
            )
        }
        item {
            VSpace(28.dp)
            SecondaryButton("Import from link", icon = Ic.browser, onClick = onImport)
            VSpace(12.dp)
            Row {
                Box(Modifier.weight(1f)) { SecondaryButton("Show", icon = Ic.tv) { create(CatalogType.SERIES) } }
                HSpace(12.dp)
                Box(Modifier.weight(1f)) { SecondaryButton("Movie", icon = Ic.movie) { create(CatalogType.MOVIE) } }
            }
        }
        if (catalog.titles.isEmpty()) {
            item {
                VSpace(28.dp)
                NoticeCard(
                    icon = Ic.importFile,
                    title = "Already have a catalog?",
                    text = "Open a JSON file and keep editing.",
                    action = "Open",
                    onAction = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                )
            }
        }
        if (series.isNotEmpty()) {
            item { SectionTitle("Shows") }
            item { TitleList(series, onEdit) }
        }
        if (movies.isNotEmpty()) {
            item { SectionTitle("Movies") }
            item { TitleList(movies, onEdit) }
        }
        if (catalog.titles.isNotEmpty()) {
            item {
                SectionTitle("Share")
                PrimaryButton(if (playerThere) "Send to Streamplayer" else "Share catalog", icon = Ic.share) {
                    val (json, left) = store.exportJson()
                    if (left > 0) toast(if (left == 1) "1 title without a name stays here." else "$left titles without a name stay here.")
                    Share.sendToPlayer(context, json, catalog.name ?: "katalog")
                }
                VSpace(8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    QuietButton("Save as file", color = Palette.text) { save.launch(CatalogJson.slug(catalog.name ?: "katalog") + ".json") }
                    QuietButton("Open file", color = Palette.muted) { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                }
            }
        }
    }
}

@Composable
private fun TitleList(list: List<CatalogTitle>, onEdit: (String) -> Unit) {
    ListGroup {
        list.forEachIndexed { i, t ->
            if (i > 0) RowDivider(inset = 84.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(t.poster, Modifier.size(width = 48.dp, height = 72.dp), radius = 8.dp)
                Box(Modifier.weight(1f)) {
                    ListRow(title = t.title.ifBlank { "Untitled" }, subtitle = summary(t).ifBlank { null }, titleMaxLines = 2, onClick = { onEdit(t.id) })
                }
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
