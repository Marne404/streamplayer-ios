package de.marne.streamplayer.creator

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.catalog.CatalogTitle
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.GeneratedEpisode
import de.marne.streamplayer.catalog.Pattern
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.catalog.TmdbRef
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import kotlinx.coroutines.launch

/**
 * Editor für einen Titel. Jede Änderung wird sofort gespeichert – es gibt keinen Speichern-Knopf,
 * also auch nichts, was man vergessen kann.
 */
@Composable
fun EditorScreen(id: String, onBack: () -> Unit, onIdChange: (String) -> Unit) {
    val store = CreatorApp.instance.store
    val catalog by store.catalog.collectAsState()
    // Erst den beobachteten Zustand lesen – nur so zeichnet Compose bei jeder Eingabe neu.
    // Der Store direkt ist der Rückfall für den einen Frame nach einem ID-Wechsel.
    val t = catalog.titles.firstOrNull { it.id == id } ?: store.get(id) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    fun change(n: CatalogTitle) {
        store.put(n, replacingId = t.id)
        if (n.id != t.id) onIdChange(n.id)
    }

    var confirmDelete by remember { mutableStateOf(false) }
    val series = t.type == CatalogType.SERIES

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GIcon(Ic.back) }
                Box(Modifier.weight(1f))
                RowMenu(listOf(MenuAction("Delete", destructive = true) { confirmDelete = true }))
            }
            Text(if (series) "SHOW" else "MOVIE", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(t.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        item { SectionTitle("Title") }
        item {
            IvoryField(t.title, { change(t.copy(title = it)) }, if (series) "Name of the show" else "Name of the movie", label = "Name")
            VSpace(12.dp)
            IvoryField(t.year?.toString().orEmpty(), { v -> change(t.copy(year = v.filter(Char::isDigit).take(4).toIntOrNull())) }, "e.g. 2008", KeyboardType.Number, label = "Year")
        }

        item { SectionTitle("TMDB") }
        item { TmdbSection(t, ::change) }

        item { SectionTitle("Link") }
        item { if (series) LinkSeries(t, ::change) else LinkMovie(t, ::change) }

        if (series) {
            item { SectionTitle("Seasons") }
            item { SeasonsSection(t, ::change) }
            item { SectionTitle("Preview") }
            item { PreviewSection(t, ::change) }
        }

        item { SectionTitle("Images") }
        item { ImagesSection(t, ::change) }

        item { SectionTitle("Description") }
        item {
            IvoryField(t.description.orEmpty(), { change(t.copy(description = it.ifBlank { null })) }, "Leave empty: comes from TMDB or the site.", singleLine = false)
            VSpace(16.dp)
            var idDraft by remember(t.id) { mutableStateOf(t.id) }
            val idTaken = idDraft != t.id && catalog.titles.any { it.id == idDraft }
            IvoryField(idDraft, { v ->
                idDraft = v.lowercase().filter { it.isLetterOrDigit() || it == '-' }
                if (idDraft.isNotBlank() && catalog.titles.none { it.id == idDraft && it.id != t.id }) change(t.copy(id = idDraft))
            }, "id", label = "Catalog ID")
            if (idTaken) StatusLine(Ic.error, "Another title already has this ID.")
            Text(
                "If it stays the same, importing again updates the title in the Player instead of adding it twice.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Palette.surface,
        title = { Text("Delete “${t.title}”?", style = MaterialTheme.typography.headlineSmall) },
        text = { Text("Only from the catalog. The title stays in the Player until you remove it there.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
        confirmButton = { QuietButton("Delete", color = Palette.wine) { confirmDelete = false; store.remove(t.id); onBack() } },
        dismissButton = { QuietButton("Keep", color = Palette.muted) { confirmDelete = false } },
    )
}

// ---------- TMDB ----------

@Composable
private fun TmdbSection(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    val app = CreatorApp.instance
    val key by app.prefs.tmdbKey.collectAsState()
    val scope = rememberCoroutineScope()
    val kind = if (t.type == CatalogType.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE
    var query by remember(t.id) { mutableStateOf(t.tmdb?.query ?: t.title) }
    var results by remember(t.id) { mutableStateOf<List<Tmdb.Result>>(emptyList()) }
    var details by remember(t.tmdb?.id) { mutableStateOf<Tmdb.Details?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(t.tmdb?.id, key) {
        val id = t.tmdb?.id
        details = if (id != null && key.isNotBlank()) runCatching { app.tmdb.details(kind, id) }.getOrNull() else null
    }

    if (key.isBlank()) {
        Text(
            "Without a key: enter the TMDB ID or a search term by hand. The Player then searches on its own. Add a key in Settings.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
        VSpace(10.dp)
        IvoryField(t.tmdb?.id?.toString().orEmpty(), { v ->
            val id = v.filter(Char::isDigit).toIntOrNull()
            change(t.copy(tmdb = if (id == null && t.tmdb?.query == null) null else TmdbRef(id, t.tmdb?.query)))
        }, "e.g. 1396", KeyboardType.Number, label = "TMDB ID")
        VSpace(12.dp)
        IvoryField(t.tmdb?.query.orEmpty(), { v ->
            change(t.copy(tmdb = if (v.isBlank() && t.tmdb?.id == null) null else TmdbRef(t.tmdb?.id, v.ifBlank { null })))
        }, "Otherwise the Player searches by name", label = "Search term")
        return
    }

    val id = t.tmdb?.id
    if (id != null) {
        GCard(padding = 16.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(details?.result?.poster, Modifier.size(width = 60.dp, height = 90.dp), radius = 10.dp)
                    HSpace(14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(details?.result?.title ?: "TMDB $id", style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(details?.result?.year?.toString(), "TMDB $id").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                        )
                        details?.takeIf { kind == Tmdb.Kind.TV }?.let { d ->
                            val real = d.seasons.filter { it.number > 0 }
                            Text(seasonsLabel(real.size) + " · " + episodesLabel(real.sumOf { it.episodeCount }), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                        }
                    }
                }
                VSpace(8.dp)
                Row {
                    if (kind == Tmdb.Kind.TV) QuietButton("Use seasons", color = Palette.text) {
                        val d = details ?: return@QuietButton
                        val keep = t.seasons.associateBy { it.number }
                        val seasons = d.seasons.filter { it.number > 0 && it.episodeCount > 0 }.map { s ->
                            // Eigenes Muster/Start einer vorhandenen Staffel bleibt, nur die Zahl kommt von TMDB.
                            keep[s.number]?.copy(episodes = s.episodeCount) ?: SeasonSpec(s.number, s.episodeCount)
                        }
                        change(t.copy(seasons = seasons))
                        message = if (seasons.size == 1) "1 season added." else "${seasons.size} seasons added."
                    }
                    QuietButton("Unlink", color = Palette.wine) { change(t.copy(tmdb = TmdbRef(query = query.ifBlank { null }))) }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
            }
        }
        return
    }

    IvoryField(query, { query = it }, "Name, ideally with year", label = "Search TMDB")
    QuietButton("Search", color = Palette.text) {
        message = "Searching …"
        scope.launch {
            runCatching { app.tmdb.search(query, kind, t.year) }
                .onSuccess { results = it.take(8); message = if (it.isEmpty()) "Nothing found. Try a different spelling or leave out the year?" else null }
                .onFailure { message = it.message }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
    if (results.isNotEmpty()) {
        VSpace(8.dp)
        ListGroup {
            results.forEachIndexed { i, r ->
                if (i > 0) RowDivider(inset = 92.dp)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // Verknüpft = Bilder und Text von TMDB, nicht die der Seite (Anlass: Banner kam von TMDB,
                        // Cover und Beschreibung blieben die der Seite). Ein eigenes Bild (data:) bleibt.
                        change(fromTmdb(t, r).copy(tmdb = TmdbRef(r.id, query.takeIf { it != t.title }), year = t.year ?: r.year, title = t.title.ifBlank { r.title }))
                        results = emptyList()
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(r.poster, Modifier.size(width = 48.dp, height = 72.dp), radius = 8.dp)
                    HSpace(14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(r.year?.toString(), r.originalTitle?.takeIf { it != r.title }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- Link ----------

@Composable
private fun LinkMovie(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    val context = LocalContext.current
    IvoryField(t.url.orEmpty(), { change(t.copy(url = it.trim().ifBlank { null })) }, "https://…", KeyboardType.Uri, label = "Movie page")
    t.url?.let { QuietButton("Check in browser", color = Palette.muted) { Share.openInBrowser(context, it) } }
}

@Composable
private fun LinkSeries(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    var sample by remember(t.id) { mutableStateOf("") }
    Text(
        "Paste the link of any episode – the pattern is derived from it. Or write the pattern directly.",
        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
    )
    VSpace(10.dp)
    IvoryField(sample, { sample = it }, "https://…/staffel-2/episode-5", KeyboardType.Uri, label = "Episode link")
    QuietButton("Derive pattern", color = if (sample.isNotBlank()) Palette.text else Palette.faint) {
        Pattern.derive(sample.trim())?.let { change(t.copy(template = it)) }
    }
    IvoryField(t.template.orEmpty(), { change(t.copy(template = it.trim().ifBlank { null })) }, "https://…/staffel-{s}/episode-{e}", KeyboardType.Uri, label = "Pattern", singleLine = false)
    Text(
        "{s} season · {e} episode · {a} absolute episode across all seasons. Doubled/tripled = zero-padded: {ss}, {ee}, {eee}, {aaa}.",
        style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
    val tpl = t.template
    if (tpl != null && !Pattern.hasEpisode(tpl) && t.seasons.any { it.template == null }) {
        VSpace(8.dp)
        StatusLine(Ic.error, "The pattern is missing the episode ({e} or {a}).")
    }
}

// ---------- Staffeln ----------

@Composable
private fun SeasonsSection(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    var quick by remember(t.id) { mutableStateOf("") }
    var open by remember(t.id) { mutableStateOf<Int?>(null) }
    val seasons = t.seasons.sortedBy { it.number }
    fun update(old: SeasonSpec, new: SeasonSpec) = change(t.copy(seasons = t.seasons.map { if (it == old) new else it }))

    IvoryField(quick, { quick = it }, "12, 8, 10", label = "Quick: episodes per season")
    QuietButton("Apply", color = if (quick.isNotBlank()) Palette.text else Palette.faint) {
        val counts = Pattern.parseSeasons(quick)
        if (counts.isNotEmpty()) change(t.copy(seasons = counts.mapIndexed { i, n -> SeasonSpec(i + 1, n) }))
        quick = ""
    }
    VSpace(4.dp)
    if (seasons.isEmpty()) {
        Text("No seasons yet. Without seasons, the Player fetches them from TMDB if a key is entered there.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        VSpace(8.dp)
    } else ListGroup {
        seasons.forEachIndexed { i, s ->
            if (i > 0) RowDivider(inset = 18.dp)
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { open = if (open == s.number) null else s.number }) {
                        Text(s.title ?: if (s.number == 0) "Specials" else "Season ${s.number}", style = MaterialTheme.typography.bodyLarge)
                        val extra = listOfNotNull(
                            "from episode ${s.start}".takeIf { s.start != 1 },
                            "own pattern".takeIf { s.template != null },
                        ).joinToString(" · ")
                        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                    IconButton(onClick = { if (s.episodes > 1) update(s, s.copy(episodes = s.episodes - 1)) }) { GIcon(Ic.minus, tint = Palette.text, size = 20.dp) }
                    Text("${s.episodes}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { update(s, s.copy(episodes = s.episodes + 1)) }) { GIcon(Ic.add, tint = Palette.text, size = 20.dp) }
                    IconButton(onClick = { open = if (open == s.number) null else s.number }) {
                        GIcon(if (open == s.number) Ic.expandLess else Ic.expandMore, tint = Palette.faint, size = 20.dp)
                    }
                }
                if (open == s.number) {
                    Column(Modifier.padding(end = 12.dp, bottom = 10.dp)) {
                        Row {
                            Box(Modifier.weight(1f)) {
                                IvoryField(s.number.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { n -> if (seasons.none { it.number == n && it != s }) { update(s, s.copy(number = n)); open = n } } }, "1", KeyboardType.Number, label = "Number")
                            }
                            HSpace(10.dp)
                            Box(Modifier.weight(1f)) {
                                IvoryField(s.episodes.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { update(s, s.copy(episodes = it.coerceIn(0, 9999))) } }, "10", KeyboardType.Number, label = "Episodes")
                            }
                            HSpace(10.dp)
                            Box(Modifier.weight(1f)) {
                                IvoryField(s.start.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { update(s, s.copy(start = it.coerceAtLeast(0))) } }, "1", KeyboardType.Number, label = "First")
                            }
                        }
                        VSpace(10.dp)
                        IvoryField(s.template.orEmpty(), { update(s, s.copy(template = it.trim().ifBlank { null })) }, "empty = the show’s pattern", KeyboardType.Uri, label = "Own pattern", singleLine = false)
                        VSpace(10.dp)
                        IvoryField(s.title.orEmpty(), { update(s, s.copy(title = it.ifBlank { null })) }, if (s.number == 0) "Specials" else "Season ${s.number}", label = "Title")
                        QuietButton("Remove season", color = Palette.wine) {
                            change(t.copy(seasons = t.seasons - s, episodes = t.episodes.filterNot { it.season == s.number }))
                            open = null
                        }
                    }
                }
            }
        }
    }
    Row {
        QuietButton("+ Season", color = Palette.text) {
            val last = seasons.lastOrNull()
            change(t.copy(seasons = t.seasons + SeasonSpec((seasons.maxOfOrNull { it.number } ?: 0) + 1, last?.episodes ?: 10)))
        }
        if (seasons.none { it.number == 0 }) QuietButton("+ Specials", color = Palette.muted) {
            change(t.copy(seasons = t.seasons + SeasonSpec(0, 1, title = "Specials")))
        }
    }
}

// ---------- Vorschau ----------

@Composable
private fun PreviewSection(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    val context = LocalContext.current
    val eps = remember(t.template, t.seasons, t.episodes) { Episodes.generate(t.template, t.seasons, t.episodes) }
    var expanded by remember(t.id) { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<GeneratedEpisode?>(null) }

    if (eps.isEmpty()) {
        Text("The addresses of all episodes appear here once the pattern and seasons are set.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        return
    }
    val missing = eps.count { it.url.isBlank() }
    Text(episodesLabel(eps.size) + " in " + seasonsLabel(t.seasons.size), style = MaterialTheme.typography.titleSmall)
    if (missing > 0) {
        VSpace(6.dp)
        StatusLine(Ic.error, (if (missing == 1) "1 episode" else "$missing episodes") + " without an address – pattern missing.")
    }
    Text("Tap to open the address in the browser. In the full list, you can change a single episode.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    VSpace(10.dp)
    ListGroup {
        eps.groupBy { it.season }.entries.forEachIndexed { i, (season, list) ->
            if (i > 0) RowDivider(inset = 18.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (season == 0) "Specials" else "Season $season", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(episodesLabel(list.size), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                val shown = if (expanded == season) list else listOfNotNull(list.firstOrNull(), list.lastOrNull().takeIf { list.size > 1 })
                shown.forEach { e ->
                    val overridden = t.episodes.any { it.season == e.season && it.episode == e.number && it.url != null }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "E${e.number}" + (if (e.absolute != e.number) " (${e.absolute})" else ""),
                            style = MaterialTheme.typography.labelMedium, color = if (overridden) Palette.brass else Palette.muted,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            e.url.ifBlank { "– no address –" },
                            style = MaterialTheme.typography.bodySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable(enabled = e.url.isNotBlank()) { Share.openInBrowser(context, e.url) },
                        )
                        if (expanded == season) IconButton(onClick = { editing = e }) { GIcon(Ic.edit, tint = Palette.muted, size = 18.dp) }
                    }
                }
                QuietButton(if (expanded == season) "Less" else "All episodes", color = Palette.muted) {
                    expanded = if (expanded == season) null else season
                }
            }
        }
    }

    editing?.let { e ->
        var url by remember(e) { mutableStateOf(e.url) }
        val existing = t.episodes.firstOrNull { it.season == e.season && it.episode == e.number }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Palette.surface,
            title = { Text("S${e.season} · E${e.number}", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column {
                    Text("Own address just for this episode – if the site differs from the pattern here.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    VSpace(10.dp)
                    IvoryField(url, { url = it }, "https://…", KeyboardType.Uri, label = "Address", singleLine = false)
                }
            },
            confirmButton = {
                QuietButton("Save", color = Palette.text) {
                    val rest = t.episodes.filterNot { it.season == e.season && it.episode == e.number }
                    change(t.copy(episodes = rest + EpisodeOverride(e.season, e.number, url.trim().ifBlank { null }, existing?.title)))
                    editing = null
                }
            },
            dismissButton = {
                Row {
                    if (existing != null) QuietButton("Reset", color = Palette.wine) {
                        change(t.copy(episodes = t.episodes - existing))
                        editing = null
                    }
                    QuietButton("Cancel", color = Palette.muted) { editing = null }
                }
            },
        )
    }
}

// ---------- Bilder ----------

@Composable
private fun ImagesSection(t: CatalogTitle, change: (CatalogTitle) -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf("poster") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            if (target == "poster") Share.embedImage(context, uri)?.let { change(t.copy(poster = it)) }
            else Share.embedImage(context, uri, maxW = 1920, maxH = 1080)?.let { change(t.copy(backdrop = it)) }
        }
    }
    Text(
        "Leave empty and the Player uses the images from TMDB. Your own images from the gallery are embedded in the catalog.",
        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
    )
    VSpace(12.dp)
    Row(verticalAlignment = Alignment.Top) {
        ImageSlot("Cover", t.poster, Modifier.width(110.dp).aspectRatio(2f / 3f),
            onPick = { target = "poster"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClear = { change(t.copy(poster = null)) })
        HSpace(16.dp)
        Box(Modifier.weight(1f)) {
            ImageSlot("Background", t.backdrop, Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                onPick = { target = "backdrop"; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClear = { change(t.copy(backdrop = null)) })
        }
    }
    VSpace(12.dp)
    IvoryField(t.poster?.takeIf { !it.startsWith("data:") }.orEmpty(), { change(t.copy(poster = it.trim().ifBlank { null })) }, "https://…/poster.jpg", KeyboardType.Uri, label = "Cover as address")
}

@Composable
private fun ImageSlot(label: String, image: String?, modifier: Modifier, onPick: () -> Unit, onClear: () -> Unit) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        VSpace(6.dp)
        Box(modifier.clickable(onClick = onPick)) {
            Thumb(image, Modifier.fillMaxSize(), radius = 14.dp)
            if (image == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GIcon(Ic.image, tint = Palette.faint) }
        }
        if (image != null) QuietButton("Remove", color = Palette.wine, onClick = onClear)
        else QuietButton("From gallery", color = Palette.text, onClick = onPick)
    }
}

/** Bilder und Beschreibung aus einem TMDB-Treffer; ein eigenes Cover (data:) bleibt. */
fun fromTmdb(t: CatalogTitle, r: Tmdb.Result): CatalogTitle = t.copy(
    poster = t.poster?.takeIf { it.startsWith("data:") } ?: r.poster ?: t.poster,
    backdrop = r.backdrop ?: t.backdrop,
    description = r.overview?.take(1200) ?: t.description,
)

/** Mit TMDB verknüpft, aber Cover noch von der Seite: Bilder und Text sollten von TMDB kommen. */
fun hasSiteInfo(t: CatalogTitle): Boolean =
    t.tmdb?.id != null && t.poster?.let { it.startsWith("http") && "image.tmdb.org" !in it } == true

/** Ohne TMDB-Verknüpfung: von Hand zuordnen. */
fun needsTmdb(t: CatalogTitle): Boolean = t.tmdb?.id == null

/** Neuer Titel mit eindeutiger ID. */
fun newTitle(type: CatalogType): CatalogTitle {
    val store = CreatorApp.instance.store
    val base = if (type == CatalogType.SERIES) "neue-serie" else "neuer-film"
    return CatalogTitle(id = store.uniqueId(base), type = type, title = "")
}

/** Kurzbeschreibung für Listen: „3 Staffeln · 30 Folgen · TMDB 1396“ */
fun summary(t: CatalogTitle): String = buildList {
    t.year?.let { add(it.toString()) }
    if (t.type == CatalogType.SERIES) {
        val real = t.seasons.count { it.number > 0 }
        if (real > 0) add(seasonsLabel(real))
        // Nur reguläre Folgen, wie die Staffelzahl davor – Specials zählen nicht mit.
        val n = t.seasons.filter { it.number > 0 }.sumOf { it.episodes }
        if (n > 0) add(episodesLabel(n))
        if (t.template == null && t.seasons.any { it.template == null }) add("Pattern missing")
    } else if (t.url == null) add("Link missing")
    t.tmdb?.id?.let { add("TMDB $it") }
}.joinToString(" · ")

/** Staffelzahl für die Anzeige: „1 season“, „3 seasons“. */
fun seasonsLabel(n: Int): String = if (n == 1) "1 season" else "$n seasons"

/** Folgenzahl für die Anzeige: „1 episode“, „30 episodes“. */
fun episodesLabel(n: Int): String = if (n == 1) "1 episode" else "$n episodes"

