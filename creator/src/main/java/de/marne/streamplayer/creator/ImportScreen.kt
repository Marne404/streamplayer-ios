package de.marne.streamplayer.creator

import android.content.ClipboardManager
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import de.marne.streamplayer.importer.LinkList
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.catalog.CatalogType
import de.marne.streamplayer.catalog.Episodes
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.importer.Draft
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

/**
 * „Aus Link übernehmen“: Link rein, Seite wird gelesen, ein fertiger Entwurf kommt heraus.
 * Ein Tipp nimmt ihn in den Katalog. Unsicheres steht als Hinweis darunter, statt geraten zu werden.
 */
@Composable
fun ImportScreen(onBack: () -> Unit, onEdit: (String) -> Unit, toast: (String) -> Unit) {
    val session = CreatorApp.instance.imports
    val state by session.state.collectAsState()
    val waiting by session.browser.waiting.collectAsState()
    val webView by session.browser.view.collectAsState()

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        // Die WebView liegt während der Analyse unter der Warte-Ansicht: Viele Seiten bauen ihre
        // Listen erst auf, wenn sie wirklich ausgelegt sind. Sichtbar wird sie nur zur Bestätigung.
        val wv = webView
        val busy = state is ImportSession.State.Running || (state as? ImportSession.State.Batch)?.running == true
        if (busy && wv != null) {
            Column(Modifier.fillMaxSize()) {
                if (waiting) ChallengeHeader()
                key(wv) {
                    AndroidView(
                        factory = { wv.also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                if (waiting) ChallengeFooter(onContinue = session.browser::proceed, onCancel = session::stop)
            }
        }

        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = state,
            // Bestätigung im Browser: dann zeigt die Ebene darunter die Seite, hier bleibt es leer.
            contentKey = { if (waiting) "waiting" else if (it is ImportSession.State.Failed) ImportSession.State.Idle::class else it::class },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "import",
        ) { s ->
            when (s) {
                is ImportSession.State.Running -> if (!waiting) Working(s, onCancel = session::cancel) else Box(Modifier)
                is ImportSession.State.Done -> Result(s, onBack, onEdit, toast)
                is ImportSession.State.Batch -> if (!waiting) BatchView(s, onBack, toast) else Box(Modifier)
                else -> Input((s as? ImportSession.State.Failed)?.message, onBack)
            }
        }
    }
}

@Composable
private fun Input(error: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val session = CreatorApp.instance.imports
    val input by session.input.collectAsState()
    val overview by session.overview.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val count = remember(input) { LinkList.parse(input).size }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.openFile(context, uri)
    }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = 48.dp)) {
        item {
            IconButton(onClick = onBack) { GIcon(Ic.back) }
            ScreenHeader(title = "Which page?", eyebrow = "From link")
        }
        item {
            VSpace(28.dp)
            Segmented(
                listOf("titles" to "Single titles", "overview" to "Whole overview"),
                if (overview) "overview" else "titles",
                { session.overview.value = it == "overview" },
            )
            VSpace(20.dp)
            IvoryField(
                input, { session.input.value = it }, "https://…", KeyboardType.Uri,
                label = when {
                    overview -> "Page with many shows or movies"
                    count >= 2 -> "$count links, one per title"
                    else -> "Show, season, episode or movie"
                },
                singleLine = overview, maxLines = if (overview) 1 else 6,
            )
            if (error != null) {
                VSpace(8.dp)
                StatusLine(Ic.error, error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Paste", color = Palette.text) {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) session.input.value = text.trim()
                }
                if (!overview) QuietButton("From file", color = Palette.text) { file.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
            }
            Text(
                if (overview) {
                    "A page that lists many titles: catalog, genre, new releases. The Creator finds the titles on it, " +
                        "including on the following pages, and then reads each one with its seasons and episodes."
                } else {
                    "The Creator reads the page and finds seasons, episodes and the pattern on its own. Several links work too, one per title: " +
                        "pasted, shared from the browser or as a text or JSON file."
                },
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
        }
        if (overview) {
            item { SectionTitle("Limits") }
            item { CrawlLimits() }
        }
        item {
            VSpace(28.dp)
            when {
                overview -> PrimaryButton("Read overview", enabled = input.isNotBlank(), icon = Ic.search) { session.startOverview(input) }
                else -> PrimaryButton(if (count >= 2) "Analyze $count links" else "Analyze page", enabled = input.isNotBlank(), icon = Ic.search) { session.start(input) }
            }
        }
    }
}

/** Pause zwischen Abrufen und Abrufe pro Durchgang – gelten für alles zusammen: Übersicht, Titel, Staffeln. */
@Composable
private fun CrawlLimits() {
    val prefs = CreatorApp.instance.prefs
    val pause by prefs.crawlPause.collectAsState()
    val budget by prefs.crawlBudget.collectAsState()
    Text("Pause between two requests", style = MaterialTheme.typography.bodyMedium)
    VSpace(10.dp)
    Segmented(
        listOf("500" to "0.5 s", "1000" to "1 s", "2000" to "2 s", "5000" to "5 s"),
        pause.toString(), { prefs.setCrawlPause(it.toLong()) },
    )
    VSpace(20.dp)
    Text("Requests per run", style = MaterialTheme.typography.bodyMedium)
    VSpace(10.dp)
    Segmented(
        listOf("100" to "100", "300" to "300", "1000" to "1000"),
        budget.toString(), { prefs.setCrawlBudget(it.toInt()) },
    )
    VSpace(12.dp)
    Text(
        "A title usually takes 1 to 5 requests, depending on the number of seasons. When the limit is reached, the Creator pauses, " +
            "and “Continue reading” starts the next run. It respects the site’s robots.txt. If the site reports " +
            "“too many requests”, it slows down, and the second time it stops.",
        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
    )
}

@Composable
private fun Working(s: ImportSession.State.Running, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.bg)
            // Fängt alle Tipps ab: darunter liegt die verdeckte WebView.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(color = Palette.ink, modifier = Modifier.size(56.dp))
        VSpace(28.dp)
        Text(s.host.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        VSpace(6.dp)
        Text("Reading the page …", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        VSpace(6.dp)
        Crossfade(s.step, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "step") { step ->
            Text(step, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        VSpace(32.dp)
        QuietButton("Cancel", color = Palette.muted, onClick = onCancel)
    }
}

@Composable
private fun ChallengeHeader() {
    Column(Modifier.fillMaxWidth().background(Palette.bg).windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
        Text("VERIFICATION", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        VSpace(6.dp)
        Text("The site wants to know whether you’re human. Confirm it below, then tap Continue.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChallengeFooter(onContinue: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.bg).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrimaryButton("Continue", onClick = onContinue)
        QuietButton("Cancel", color = Palette.muted, onClick = onCancel)
    }
}

@Composable
private fun Result(s: ImportSession.State.Done, onBack: () -> Unit, onEdit: (String) -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val session = CreatorApp.instance.imports
    val override by session.nameOverride.collectAsState()
    val catalog by CreatorApp.instance.store.catalog.collectAsState()
    val d = s.draft
    val t = d.title
    val name = override ?: t.title
    val series = t.type == CatalogType.SERIES
    val existing = remember(d, catalog) { session.existing(d) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun commit(edit: Boolean) {
        val saved = session.commit() ?: return
        toast(if (existing != null) "“${saved.title}” updated." else "“${saved.title}” is in the catalog.")
        if (edit) onEdit(saved.id) else onBack()
    }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = 48.dp)) {
        item {
            IconButton(onClick = session::cancel) { GIcon(Ic.back) }
            ScreenHeader(title = name.ifBlank { "Untitled" }, eyebrow = if (series) "Show detected" else "Movie detected")
        }
        item {
            VSpace(32.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(d.linked?.poster ?: t.poster, Modifier.size(width = 96.dp, height = 144.dp), radius = 14.dp)
                HSpace(18.dp)
                Column(Modifier.weight(1f)) {
                    Text(headline(d), style = MaterialTheme.typography.bodyLarge)
                    VSpace(4.dp)
                    val host = d.source.substringAfter("://").substringBefore('/').removePrefix("www.")
                    Text(listOfNotNull(t.year?.toString(), host).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Text(
                        (if (d.pages == 1) "1 page read" else "${d.pages} pages read") + if (d.usedBrowser) " · in the browser" else "",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
            }
        }
        item {
            VSpace(28.dp)
            Segmented(listOf("series" to "Show", "movie" to "Movie"), if (series) "series" else "movie", { v ->
                val type = if (v == "series") CatalogType.SERIES else CatalogType.MOVIE
                if (type != t.type) session.retype(type)
            })
            VSpace(14.dp)
            IvoryField(name, { session.nameOverride.value = it }, if (series) "Name of the show" else "Name of the movie", label = "Name")
        }

        item { SectionTitle("TMDB") }
        item { TmdbPart(s) }

        if (series) {
            item { SectionTitle("Seasons") }
            item { SeasonsPart(d) { Share.openInBrowser(context, it) } }
        } else {
            item { SectionTitle("Link") }
            item {
                Text(
                    t.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { t.url?.let { Share.openInBrowser(context, it) } },
                )
            }
        }

        if (d.notes.isNotEmpty()) {
            item { SectionTitle("Notes") }
            item {
                Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.notes.forEach { n ->
                        if (n.warning) StatusLine(Ic.error, n.text) else StatusLine(Ic.info, n.text, Palette.muted)
                    }
                }
            }
        }

        item {
            VSpace(36.dp)
            if (existing != null) {
                Text(
                    "Already in the catalog. Updating takes over seasons and new episodes; your own details stay.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
                VSpace(12.dp)
            }
            PrimaryButton(if (existing != null) "Update in catalog" else "Add to catalog", enabled = name.isNotBlank() && !s.busy, icon = if (existing != null) Ic.refresh else Ic.add) { commit(edit = false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                QuietButton("Add and edit", color = Palette.muted) { commit(edit = true) }
            }
        }
    }
}

/**
 * Massenimport: oben der Fortschritt, darunter jeder Link als Zeile. Fertige Einträge sind
 * gewählt (Häkchen); ein Tipp wählt ab. Ein Knopf nimmt alle gewählten in den Katalog.
 * Beim Übersichts-Import zählen die Abrufe mit, Wartendes lässt sich überspringen, und an der
 * Abrufgrenze geht es mit „Weiterlesen“ in den nächsten Durchgang.
 */
@Composable
private fun BatchView(s: ImportSession.State.Batch, onBack: () -> Unit, toast: (String) -> Unit) {
    val session = CreatorApp.instance.imports
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val crawl = s.crawl
    val n = s.planned
    val failed = s.items.count { it.status is ImportSession.ItemStatus.Failed }
    val chosen = s.chosen.size
    val current = s.items.firstOrNull { it.status is ImportSession.ItemStatus.Running }
    val scanning = crawl?.scanning

    val title = when {
        scanning != null -> "Reading the overview …"
        s.running -> "Reading ${minOf(s.finished + 1, n)} of $n …"
        crawl != null && s.items.isEmpty() -> "Stopped."
        crawl != null && s.waiting > 0 -> "${s.finished} of $n read."
        crawl != null -> if (n == 1) "One title read." else "$n titles read."
        n == 1 -> "One link read."
        else -> "$n links read."
    }
    val subtitle = listOfNotNull(
        (if (s.items.size == 1) "1 title found" else "${s.items.size} titles found").takeIf { crawl != null && scanning == null },
        "${s.finished - failed} recognized".takeIf { s.finished > 0 },
        "$failed without result".takeIf { failed > 0 },
        "only the first ${LinkList.MAX} links".takeIf { s.skipped > 0 },
    ).joinToString(" · ").ifEmpty { null }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 4.dp, bottom = 48.dp)) {
        item {
            IconButton(onClick = session::stop) { GIcon(Ic.back) }
            ScreenHeader(
                title = title,
                eyebrow = if (crawl != null) "Overview · ${crawl.host}" else "From links",
                subtitle = subtitle,
            )
        }
        item {
            VSpace(24.dp)
            Bar(if (scanning != null || n == 0) 0f else s.finished.toFloat() / n)
            val step = scanning ?: (current?.status as? ImportSession.ItemStatus.Running)?.step
            if (s.running && step != null) {
                VSpace(10.dp)
                Crossfade(step, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "step") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
            }
            if (crawl != null) {
                VSpace(10.dp)
                Text(
                    listOfNotNull(
                        "${crawl.used} of ${crawl.budget} requests",
                        (if (crawl.listPages == 1) "1 overview page" else "${crawl.listPages} overview pages").takeIf { crawl.listPages > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
                if (crawl.more && scanning == null) {
                    VSpace(8.dp)
                    StatusLine(
                        Ic.info,
                        "The overview continues. At most ${ImportSession.MAX_LIST_PAGES} pages and ${LinkList.MAX} titles are read.",
                        Palette.muted,
                    )
                }
                if (crawl.halt != null && !s.running) {
                    VSpace(8.dp)
                    StatusLine(Ic.pause, crawl.halt, Palette.text)
                }
            }
        }
        if (s.items.isNotEmpty()) item {
            VSpace(28.dp)
            ListGroup(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                s.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(inset = 56.dp)
                    BatchRow(item, crawl = crawl != null, running = s.running) { session.toggle(i) }
                }
            }
        }
        item {
            VSpace(36.dp)
            PrimaryButton(
                when (chosen) { 0 -> "Nothing selected"; 1 -> "Add 1 title"; else -> "Add $chosen titles" },
                enabled = !s.running && chosen > 0, icon = Ic.add,
            ) {
                val (added, updated) = session.commitBatch()
                toast(listOfNotNull("$added added".takeIf { added > 0 }, "$updated updated".takeIf { updated > 0 }).joinToString(", ") + " in the catalog.")
                onBack()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (crawl != null && !s.running && s.waiting > 0) {
                    val budget by CreatorApp.instance.prefs.crawlBudget.collectAsState()
                    QuietButton("Continue reading ($budget requests)", color = Palette.text, onClick = session::more)
                }
                QuietButton(if (s.running) "Stop" else "Discard", color = Palette.muted, onClick = session::stop)
            }
        }
    }
}

@Composable
private fun BatchRow(item: ImportSession.BatchItem, crawl: Boolean, running: Boolean, onToggle: () -> Unit) {
    val st = item.status
    val host = item.link.url.substringAfter("://").substringBefore('/').removePrefix("www.")
    val path = item.link.url.substringAfter("://").let { r -> r.indexOf('/').takeIf { it >= 0 }?.let { r.substring(it) } ?: "/" }
    val (icon, tint) = when (st) {
        is ImportSession.ItemStatus.Ready -> if (st.include) Ic.checkCircle to Palette.text else Ic.circle to Palette.faint
        is ImportSession.ItemStatus.Failed -> Ic.error to Palette.wine
        is ImportSession.ItemStatus.Running -> Ic.schedule to Palette.text
        ImportSession.ItemStatus.Waiting -> Ic.schedule to Palette.faint
        ImportSession.ItemStatus.Skipped -> Ic.circle to Palette.faint
    }
    val title = item.name.ifBlank { path }
    val subtitle = when (st) {
        is ImportSession.ItemStatus.Ready -> if (st.include) readySummary(st.draft) else "Won’t be added"
        is ImportSession.ItemStatus.Failed -> st.message
        is ImportSession.ItemStatus.Running -> "Reading …"
        ImportSession.ItemStatus.Waiting -> if (crawl) (if (running) path else "Not read yet · $path") else host
        ImportSession.ItemStatus.Skipped -> "Will be skipped"
    }
    val tappable = st is ImportSession.ItemStatus.Ready ||
        (crawl && (st is ImportSession.ItemStatus.Waiting || st is ImportSession.ItemStatus.Skipped))
    Row(
        Modifier.fillMaxWidth()
            .let { if (tappable) it.clickable(onClick = onToggle) else it }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon(icon, tint = tint, size = 20.dp)
        HSpace(18.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st is ImportSession.ItemStatus.Skipped) Palette.muted else Palette.text,
            )
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st is ImportSession.ItemStatus.Failed) Palette.wine else Palette.muted,
            )
        }
    }
}

/** „Serie · 3 Staffeln · 37 Folgen · TMDB“, bei Problemen der erste Hinweis statt TMDB. */
private fun readySummary(d: Draft): String {
    val parts = mutableListOf(if (d.title.type == CatalogType.SERIES) "Show" else "Movie")
    if (d.title.type == CatalogType.SERIES) {
        val regular = d.title.seasons.filter { it.number > 0 }
        if (regular.isEmpty()) parts += "no pattern"
        else {
            parts += seasonsLabel(regular.size)
            parts += episodesLabel(regular.sumOf { it.episodes })
        }
    }
    when {
        d.linked != null -> parts += "TMDB"
        d.candidates.isNotEmpty() -> parts += "TMDB unclear"
    }
    return parts.joinToString(" · ")
}

private fun headline(d: Draft): String {
    val t = d.title
    if (t.type == CatalogType.MOVIE) return "Movie"
    val regular = t.seasons.filter { it.number > 0 }
    if (regular.isEmpty()) return "No seasons detected"
    val parts = mutableListOf(seasonsLabel(regular.size))
    parts += episodesLabel(regular.sumOf { it.episodes })
    if (t.seasons.any { it.number == 0 }) parts += "Specials"
    return parts.joinToString(" · ")
}

@Composable
private fun TmdbPart(s: ImportSession.State.Done) {
    val app = CreatorApp.instance
    val session = app.imports
    val key by app.prefs.tmdbKey.collectAsState()
    val d = s.draft
    val linked = d.linked

    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        when {
            s.busy -> Text("Matching with TMDB …", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            linked != null -> ListGroup {
                TmdbRow(linked, trailing = { QuietButton("Unlink", color = Palette.wine) { session.link(null) } })
            }
            d.candidates.isNotEmpty() -> {
                Text("Not clear. Which one is it?", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                VSpace(10.dp)
                ListGroup {
                    d.candidates.forEachIndexed { i, r ->
                        if (i > 0) RowDivider(inset = 82.dp)
                        TmdbRow(r, onClick = { session.link(r) })
                    }
                    RowDivider(inset = 18.dp)
                    ListRow("None of these", titleColor = Palette.muted, showChevron = false, onClick = { session.link(null) })
                }
            }
            key.isBlank() -> Text(
                "Without a TMDB key, the Player searches by name on its own later.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
            else -> Text(
                "No clear match found on TMDB. The Player searches on its own later.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
        }
    }
}

@Composable
private fun TmdbRow(r: Tmdb.Result, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r.poster, Modifier.size(width = 48.dp, height = 72.dp), radius = 8.dp)
        HSpace(18.dp)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(r.year?.toString(), r.originalTitle?.takeIf { it != r.title }, "TMDB ${r.id}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun SeasonsPart(d: Draft, open: (String) -> Unit) {
    val t = d.title
    val seasons = t.seasons.sortedBy { it.number }
    if (seasons.isEmpty()) {
        Text("No seasons detected. You can add them in the editor by hand or via TMDB.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        return
    }
    ListGroup {
        seasons.forEachIndexed { i, s ->
            if (i > 0) RowDivider(inset = 18.dp)
            ListRow(
                title = s.title ?: seasonName(s),
                subtitle = "from episode ${s.start}".takeIf { s.start != 1 },
                value = episodesLabel(s.episodes),
                showChevron = false,
            )
        }
    }
    val eps = remember(t) { Episodes.generate(t.template, t.seasons, t.episodes).filter { it.season > 0 } }
    if (eps.isNotEmpty()) {
        VSpace(12.dp)
        listOfNotNull(eps.first(), eps.last().takeIf { eps.size > 1 }).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("S${e.season} E${e.number}", style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(end = 10.dp))
                // Ohne Schema und Host (steht schon oben): sonst schneidet die Zeile genau den Teil ab, der sich ändert.
                val path = e.url.substringAfter("://").let { r -> r.indexOf('/').takeIf { it >= 0 }?.let { r.substring(it) } ?: r }
                Text(
                    path, style = MaterialTheme.typography.bodySmall, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { open(e.url) },
                )
            }
        }
    }
}

private fun seasonName(s: SeasonSpec) = if (s.number == 0) "Specials" else "Season ${s.number}"
