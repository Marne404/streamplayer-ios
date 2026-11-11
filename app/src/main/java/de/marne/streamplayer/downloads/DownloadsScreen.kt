package de.marne.streamplayer.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.DownloadItem
import de.marne.streamplayer.download.Offline
import de.marne.streamplayer.download.OfflineState
import de.marne.streamplayer.library.CheckDot
import de.marne.streamplayer.library.DownloadMark
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibState
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr

class DownloadActions(
    val play: (DownloadItem) -> Unit,
    val pause: (DownloadItem) -> Unit,
    val resume: (DownloadItem) -> Unit,
    val remove: (DownloadItem) -> Unit,
    val share: (DownloadItem) -> Unit,
    val openWith: (DownloadItem) -> Unit,
    val openPage: (DownloadItem) -> Unit,
    val saveToLibrary: (DownloadItem) -> Unit,
    val goBrowse: () -> Unit,
    /** Suche nach dem Video abbrechen (Schlüssel aus Keys). */
    val cancelPending: (String) -> Unit,
    /** Detailseite des Titels öffnen. */
    val openTitle: (String) -> Unit,
)

/** Ein Download, dessen Video noch gesucht wird. */
data class PendingRow(val key: String, val label: String, val searching: Boolean)

/** Fertige Dateien eines Titels aus der Bibliothek; [files]: Schlüssel → Download, neueste zuerst. */
private class TitleGroup(val title: LibTitle, val files: List<Pair<String, DownloadItem>>) {
    val bytes: Long get() = files.sumOf { Offline.bytes(it.second) }
    val latest: Long get() = files.maxOf { it.second.finishedAt ?: it.second.createdAt }
}

/**
 * Downloads: oben, wie viel Platz sie belegen; dann, was gesucht wird, läuft oder hängt; darunter
 * das Geladene nach Titel – eine Serie ist eine Zeile, die ihre Folgen aufklappt, statt acht
 * lose Zeilen. Videos aus dem Browser ohne Titel stehen für sich. „Auswählen“ löscht mehrere auf einmal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(state: LibState, pending: List<PendingRow>, actions: DownloadActions, onBack: (() -> Unit)? = null) {
    val items = state.downloads
    val active = items.filter { it.active }
    val stopped = items.filter { it.state == DlState.PAUSED || it.state == DlState.FAILED }
    val done = items.filter { it.state == DlState.DONE }.sortedByDescending { it.finishedAt ?: it.createdAt }

    // Zu welchem Titel gehört eine Datei? Über die Datei selbst (sie hängt am Titel), sonst über den Schlüssel.
    val byUri = remember(state.all) { state.all.flatMap { t -> t.offline.map { (k, u) -> u to (t to k) } }.toMap() }
    fun owner(d: DownloadItem): Pair<LibTitle, String>? =
        d.fileUri?.let(byUri::get) ?: d.libraryKey?.let { k -> Keys.parse(k)?.first?.let(state::anyById)?.let { it to k } }
    val groups = done.mapNotNull { d -> owner(d)?.let { (t, k) -> Triple(t, k, d) } }
        .groupBy { it.first.id }
        .map { (_, l) -> TitleGroup(l.first().first, l.map { it.second to it.third }) }
        .sortedByDescending { it.latest }
    val loose = done.filter { owner(it) == null }

    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var open by rememberSaveable { mutableStateOf(emptySet<String>()) }
    if (done.isEmpty() && selecting) { selecting = false; selected = emptySet() }
    fun toggle(ids: List<String>) {
        selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
    }

    val title = when {
        items.isEmpty() && pending.isEmpty() -> tr("Noch nichts geladen.", "Nothing downloaded yet.")
        active.size == 1 -> tr("Ein Download läuft.", "One download in progress.")
        active.size > 1 -> tr("${active.size} Downloads laufen.", "${active.size} downloads in progress.")
        pending.isNotEmpty() -> tr("Video wird gesucht.", "Finding the video.")
        stopped.isNotEmpty() -> tr("Da fehlt noch etwas.", "Something's still missing.")
        else -> tr("Alles geladen.", "All downloaded.")
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + if (onBack != null) 4.dp else 16.dp, bottom = if (selecting) 110.dp else 40.dp),
        ) {
            if (onBack != null) item { IconButton(onClick = onBack) { GIcon(Ic.back) } }
            item {
                ScreenHeader(title = title, eyebrow = "Downloads", trailing = if (done.isEmpty()) null else ({
                    QuietButton(if (selecting) tr("Fertig", "Done") else tr("Auswählen", "Select"), color = Palette.text) {
                        selecting = !selecting
                        selected = emptySet()
                    }
                }))
            }
            if (items.isNotEmpty()) item { VSpace(16.dp); StorageCard(Offline.bytes(items)) }
            if (items.isEmpty() && pending.isEmpty()) {
                item {
                    VSpace(32.dp)
                    NoticeCard(
                        icon = Ic.browser,
                        title = tr("So geht es", "How it works"),
                        text = tr(
                            "Bei einem Film oder einer Folge auf den Pfeil tippen – oder im Browser ein Video starten und auf „gefunden“ tippen.",
                            "Tap the arrow on a movie or episode – or start a video in the browser and tap “found”.",
                        ),
                        action = "Browser",
                        onAction = actions.goBrowse,
                    )
                }
            }

            if (pending.isNotEmpty()) {
                item { SectionTitle(tr("Wird gesucht", "Finding the video")) }
                item {
                    ListGroup {
                        pending.forEachIndexed { i, p ->
                            if (i > 0) RowDivider(inset = 18.dp)
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                DownloadMark(OfflineState.Waiting(p.searching), onDownload = {}, onMenu = {})
                                HSpace(6.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (p.searching) tr("Sucht das Video auf der Seite …", "Finding the video on the page …") else tr("Wartet, bis die Suche davor fertig ist", "Waits for the search before it"),
                                        style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { actions.cancelPending(p.key) }) { GIcon(Ic.close, tint = Palette.muted, size = 20.dp) }
                            }
                        }
                    }
                }
            }
            if (active.isNotEmpty()) {
                item { SectionTitle(tr("Läuft", "In progress")) }
                item {
                    ListGroup {
                        active.forEachIndexed { i, d ->
                            if (i > 0) RowDivider(inset = 96.dp)
                            DownloadRow(d, onClick = null) {
                                IconButton(onClick = { actions.pause(d) }) { GIcon(Ic.pause, tint = Palette.text, size = 20.dp) }
                            }
                        }
                    }
                }
            }
            if (stopped.isNotEmpty()) {
                item { SectionTitle(tr("Angehalten", "Paused")) }
                item {
                    ListGroup {
                        stopped.forEachIndexed { i, d ->
                            if (i > 0) RowDivider(inset = 96.dp)
                            DownloadRow(d, onClick = { actions.resume(d) }) {
                                RowMenu(
                                    listOf(
                                        MenuAction(if (d.state == DlState.FAILED) tr("Erneut versuchen", "Try again") else tr("Fortsetzen", "Resume")) { actions.resume(d) },
                                        MenuAction(tr("Seite öffnen", "Open page")) { actions.openPage(d) },
                                        MenuAction(tr("Abbrechen", "Cancel"), destructive = true) { confirm = listOf(d) },
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (groups.isNotEmpty()) {
                item { SectionTitle(tr("Geladen", "Downloaded")) }
                item {
                    ListGroup {
                        groups.forEachIndexed { i, g ->
                            if (i > 0) RowDivider(inset = 84.dp)
                            val ids = g.files.map { it.second.id }
                            val series = g.title.kind == TitleKind.SERIES
                            val expanded = series && g.title.id in open && !selecting
                            GroupRow(
                                g, series, expanded,
                                selecting = selecting, checked = ids.all { it in selected },
                                onClick = {
                                    when {
                                        selecting -> toggle(ids)
                                        series -> open = if (g.title.id in open) open - g.title.id else open + g.title.id
                                        else -> actions.play(g.files.first().second)
                                    }
                                },
                                onLongClick = { if (!selecting) { selecting = true; selected = ids.toSet() } },
                            ) {
                                val d = g.files.first().second
                                RowMenu(
                                    buildList {
                                        if (!series) add(MenuAction(tr("Abspielen", "Play")) { actions.play(d) })
                                        add(MenuAction(if (series) tr("Zur Serie", "Go to show") else tr("Zum Film", "Go to movie")) { actions.openTitle(g.title.id) })
                                        if (!series) add(MenuAction(tr("Teilen", "Share")) { actions.share(d) })
                                        add(MenuAction(if (series) tr("Alle Folgen löschen", "Delete all episodes") else tr("Löschen", "Delete"), destructive = true) { confirm = g.files.map { it.second } })
                                    }
                                )
                            }
                            // Folgen klappen unter der Serie auf; Platz entsteht mit, statt dass alles darunter springt.
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(tween(320, easing = de.marne.streamplayer.library.SpringEase)) + fadeIn(tween(220, delayMillis = 80)),
                                exit = shrinkVertically(tween(260, easing = de.marne.streamplayer.library.SpringEase)) + fadeOut(tween(140)),
                            ) {
                                Column(Modifier.padding(bottom = 6.dp)) {
                                    g.files.sortedWith(compareBy({ Keys.parse(it.first)?.second ?: 0 }, { Keys.parse(it.first)?.third ?: 0 })).forEach { (key, d) ->
                                        EpisodeFileRow(g.title, key, d, onClick = { actions.play(d) }) {
                                            RowMenu(
                                                listOf(
                                                    MenuAction(tr("Abspielen", "Play")) { actions.play(d) },
                                                    MenuAction(tr("Teilen", "Share")) { actions.share(d) },
                                                    MenuAction(tr("Löschen", "Delete"), destructive = true) { confirm = listOf(d) },
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (loose.isNotEmpty()) {
                item { SectionTitle(tr("Aus dem Browser", "From the browser")) }
                item {
                    ListGroup {
                        loose.forEachIndexed { i, d ->
                            if (i > 0) RowDivider(inset = 96.dp)
                            DownloadRow(
                                d,
                                onClick = { if (selecting) toggle(listOf(d.id)) else actions.play(d) },
                                onLongClick = { if (!selecting) { selecting = true; selected = setOf(d.id) } },
                            ) {
                                if (selecting) Box(Modifier.padding(end = 10.dp)) { CheckDot(d.id in selected) }
                                else RowMenu(
                                    listOf(
                                        MenuAction(tr("Abspielen", "Play")) { actions.play(d) },
                                        MenuAction(tr("Teilen", "Share")) { actions.share(d) },
                                        MenuAction(tr("Öffnen mit …", "Open with …")) { actions.openWith(d) },
                                        MenuAction(tr("In Bibliothek", "Add to library")) { actions.saveToLibrary(d) },
                                        MenuAction(tr("Seite öffnen", "Open page")) { actions.openPage(d) },
                                        MenuAction(tr("Löschen", "Delete"), destructive = true) { confirm = listOf(d) },
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Auswahl: Leiste kommt von unten, solange ausgewählt wird – sagt, wie viel Platz frei wird.
        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically(tween(420, easing = de.marne.streamplayer.library.BouncyEase)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(320, easing = de.marne.streamplayer.library.SpringEase)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val chosen = done.filter { it.id in selected }
            Row(
                // Die Seite endet schon über der Navigation (AppRoot) – nur der eigene Abstand.
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(24.dp))
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (chosen.size) { 0 -> tr("Nichts ausgewählt", "Nothing selected"); 1 -> tr("1 ausgewählt", "1 selected"); else -> tr("${chosen.size} ausgewählt", "${chosen.size} selected") },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (chosen.isNotEmpty()) Text(tr("${Format.bytes(Offline.bytes(chosen))} werden frei", "Frees ${Format.bytes(Offline.bytes(chosen))}"), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                QuietButton(tr("Alle", "All"), color = Palette.muted) { selected = if (selected.size == done.size) emptySet() else done.map { it.id }.toSet() }
                QuietButton(tr("Löschen", "Delete"), color = if (chosen.isEmpty()) Palette.faint else Palette.wine) { if (chosen.isNotEmpty()) confirm = chosen }
            }
        }
    }

    confirm?.let { list ->
        val allDone = list.all { it.state == DlState.DONE }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Palette.surface,
            title = {
                Text(
                    when {
                        !allDone -> tr("Download abbrechen?", "Cancel download?")
                        list.size == 1 -> tr("Video löschen?", "Delete video?")
                        else -> tr("${list.size} Videos löschen?", "Delete ${list.size} videos?")
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    if (allDone) tr(
                        "${Format.bytes(Offline.bytes(list))} werden frei. Titel und Fortschritt in der Bibliothek bleiben.",
                        "Frees ${Format.bytes(Offline.bytes(list))}. Titles and progress in your library stay.",
                    ) else tr("Was schon geladen ist, wird verworfen.", "Anything already downloaded will be discarded."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            },
            confirmButton = {
                QuietButton(if (allDone) tr("Löschen", "Delete") else tr("Abbrechen", "Cancel"), color = Palette.wine) {
                    confirm = null
                    list.forEach(actions.remove)
                    selected = selected - list.map { it.id }.toSet()
                    if (selecting && selected.isEmpty()) selecting = false
                }
            },
            dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirm = null } },
        )
    }
}

/**
 * Wie viel Platz: Balken über den ganzen Speicher – Downloads in Messing, der Rest des Belegten
 * gedämpft, frei bleibt leer. So sieht man auf einen Blick, ob noch eine Staffel passt.
 */
@Composable
private fun StorageCard(used: Long) {
    val storage = remember(used) { Offline.storage() }
    ListGroup {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.bytes(used), style = MaterialTheme.typography.titleMedium)
                Text(tr("  für Downloads", "  for downloads"), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                storage?.let { (free, _) -> Text(tr("${Format.bytes(free)} frei", "${Format.bytes(free)} free"), style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
            }
            if (storage != null && storage.second > 0) {
                val (free, total) = storage
                val app by animateFloatAsState((used.toFloat() / total).coerceIn(0f, 1f), tween(700), label = "app")
                val other by animateFloatAsState(((total - free - used).toFloat() / total).coerceIn(0f, 1f), tween(700), label = "other")
                VSpace(10.dp)
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Palette.surface2)) {
                    // Kleinste sichtbare Breite: auch 200 MB auf 256 GB sollen als Strich erscheinen.
                    if (app > 0f) Box(Modifier.weight(app.coerceAtLeast(0.008f)).fillMaxSize().background(Palette.brass))
                    if (other > 0f) Box(Modifier.weight(other).fillMaxSize().background(Palette.faint.copy(alpha = 0.5f)))
                    val rest = (1f - app.coerceAtLeast(0.008f) - other).coerceAtLeast(0.0001f)
                    Box(Modifier.weight(rest).fillMaxSize())
                }
                VSpace(6.dp)
                Text(tr("Ort: Filme/Streamplayer", "Location: Movies/Streamplayer"), style = MaterialTheme.typography.bodySmall, color = Palette.faint)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    g: TitleGroup, series: Boolean, expanded: Boolean, selecting: Boolean, checked: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable () -> Unit,
) {
    val t = g.title
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(Tmdb.largeModel(t.cover ?: g.files.first().second.thumbnail ?: g.files.first().second.fileUri), Modifier.size(width = 44.dp, height = 66.dp), radius = 10.dp)
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (series) add(if (g.files.size == 1) tr("1 Folge", "1 episode") else tr("${g.files.size} Folgen", "${g.files.size} episodes"))
                else add(tr("Film", "Movie"))
                g.bytes.takeIf { it > 0 }?.let { add(Format.bytes(it)) }
                if (!series) g.files.first().second.durationMs?.let { add(Format.duration(it)) }
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
        }
        when {
            selecting -> Box(Modifier.padding(end = 10.dp)) { CheckDot(checked) }
            series -> {
                // Pfeil dreht sich mit, statt auszutauschen: ein Ding, das seine Lage ändert.
                val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(320, easing = de.marne.streamplayer.library.SpringEase), label = "turn")
                Box(Modifier.size(32.dp).graphicsLayer { rotationZ = turn }, contentAlignment = Alignment.Center) { GIcon(Ic.expandMore, tint = Palette.muted, size = 20.dp) }
                menu()
            }
            else -> menu()
        }
    }
}

@Composable
private fun EpisodeFileRow(t: LibTitle, key: String, d: DownloadItem, onClick: () -> Unit, menu: @Composable () -> Unit) {
    val parsed = Keys.parse(key)
    val e = parsed?.let { (_, s, n) -> if (s != null && n != null) t.episode(s, n) else null }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 76.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(e?.label ?: parsed?.let { "S${it.second} E${it.third}" }, e?.title).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(listOfNotNull(Offline.bytes(d).takeIf { it > 0 }?.let(Format::bytes), d.durationMs?.let(Format::duration)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
        }
        menu()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(d: DownloadItem, onClick: (() -> Unit)?, onLongClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val failed = d.state == DlState.FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick) else it }
            .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(d.thumbnail ?: d.fileUri, Modifier.size(width = 64.dp, height = 40.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) {
                    GIcon(Ic.error, tint = Palette.wine, size = 14.dp)
                    HSpace(4.dp)
                }
                Text(
                    Format.metaLine(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Palette.wine else Palette.muted,
                    maxLines = if (failed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (d.state != DlState.DONE && d.state != DlState.FAILED) {
                VSpace(8.dp)
                Bar(d.progress, height = 4.dp, color = if (d.active) Palette.brass else Palette.muted)
            }
        }
        HSpace(4.dp)
        trailing()
    }
}
