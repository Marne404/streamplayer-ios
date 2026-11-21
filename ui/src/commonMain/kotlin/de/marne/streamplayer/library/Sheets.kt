package de.marne.streamplayer.library

import androidx.compose.foundation.layout.Arrangement
import de.marne.streamplayer.ui.design.QuietButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.TmdbImages
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Hairline
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.launch

/**
 * Kurzansicht wie bei Netflix: Antippen eines Posters öffnet kein ganzes Detail, sondern dieses
 * Blatt – Abspielen, Meine Liste, Gesehen, Laden sind einen Tipp entfernt, „Details & Folgen“ führt weiter.
 */
@Composable
fun QuickSheet(t: LibTitle, state: LibState, actions: LibActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Erst zuklappen, dann handeln – sonst springt das Blatt weg, während schon die nächste Seite kommt.
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    val seen = Recommend.seen(t, state.progress)
    val movieP = if (t.kind == TitleKind.MOVIE) state.progress[Keys.movie(t)] else null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Top) {
                Thumb(TmdbImages.largeModel(t.cover ?: t.offline.values.firstOrNull()), Modifier.size(width = 92.dp, height = 138.dp), radius = 14.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp, top = 2.dp)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    VSpace(6.dp)
                    val facts = factLine(t)
                    if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    val g = genreLine(t)
                    if (g.isNotEmpty()) Text(g, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                IconButton(onClick = { then {} }, modifier = Modifier.size(40.dp)) { GIcon(Ic.close, size = 20.dp) }
            }
            t.text?.let {
                VSpace(14.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            VSpace(18.dp)
            PrimaryButton(playLabel(t, state.progress), icon = Ic.play) { then { actions.play(t, null) } }
            if (movieP != null && !movieP.finished && movieP.positionMs > Progress.MIN_RESUME_MS) {
                VSpace(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(movieP.fraction, height = 4.dp, modifier = Modifier.weight(1f))
                    Text(remaining(movieP), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp))
                }
            }
            VSpace(12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionIcon(if (state.inList(t)) Ic.check else Ic.add, tr("Meine Liste", "My List"), active = state.inList(t)) { actions.toggleList(t) }
                ActionIcon(Ic.bookmark, tr("Sammlung", "Collection"), active = state.collectionsWith(t).isNotEmpty()) { then { actions.collect(t) } }
                ActionIcon(Ic.checkCircle, if (seen == Recommend.Seen.DONE) tr("Gesehen", "Watched") else tr("Als gesehen", "Mark watched"), active = seen == Recommend.Seen.DONE) {
                    actions.markWatched(t, seen != Recommend.Seen.DONE)
                }
                if (t.kind == TitleKind.MOVIE) {
                    val dl = state.offline(t, Keys.movie(t))
                    if (dl != de.marne.streamplayer.download.OfflineState.None || t.pageUrl != null) DownloadAction(
                        dl, tr("Laden", "Download"),
                        onDownload = { actions.download(t, null) }, onMenu = { then { actions.offlineMenu(t, null) } },
                    )
                }
            }
            VSpace(8.dp)
            Hairline()
            ListRow(
                title = if (t.kind == TitleKind.SERIES) tr("Folgen & Details", "Episodes & details") else tr("Details & mehr", "Details & more"),
                icon = if (t.kind == TitleKind.SERIES) Ic.tv else Ic.movie,
                onClick = { then { actions.open(t) } },
            )
        }
    }
}

/** Kategorien: alle Genres mit Anzahl – für Serien, Filme oder alles, je nach Filter der Startseite. */
@Composable
fun CategorySheet(state: LibState, kind: TitleKind?, actions: LibActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    val titles = remember(state.titles, kind) { if (kind == null) state.titles else state.titles.filter { it.kind == kind } }
    val genres = remember(titles) { Recommend.genres(titles) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.heightIn(max = 620.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item {
                Text(tr("KATEGORIEN", "CATEGORIES"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(
                    when (kind) { TitleKind.SERIES -> tr("Serien nach Genre.", "Shows by genre."); TitleKind.MOVIE -> tr("Filme nach Genre.", "Movies by genre."); null -> tr("Wonach ist dir?", "What are you in the mood for?") },
                    style = MaterialTheme.typography.headlineSmall,
                )
                VSpace(18.dp)
            }
            item {
                ListGroup {
                    val allSpec = when (kind) { TitleKind.SERIES -> GridSpec("series"); TitleKind.MOVIE -> GridSpec("movies"); null -> GridSpec("all") }
                    ListRow(
                        title = when (kind) { TitleKind.SERIES -> tr("Alle Serien", "All shows"); TitleKind.MOVIE -> tr("Alle Filme", "All movies"); null -> tr("Alle Titel", "All titles") },
                        icon = Ic.library, value = titles.size.toString(),
                        onClick = { then { actions.grid(allSpec) } },
                    )
                    genres.forEach { (g, n) ->
                        RowDivider()
                        ListRow(
                            title = g, value = n.toString(),
                            onClick = { then { actions.grid(GridSpec("genre", if (kind == null) g else "$g|${kind.name}")) } },
                        )
                    }
                }
                if (genres.isEmpty()) {
                    VSpace(12.dp)
                    Text(
                        tr(
                            "Noch keine Genres. Sie kommen von TMDB – Schlüssel unter Einstellungen → Mediathek.",
                            "No genres yet. They come from TMDB – add a key under Settings → Library.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
            }
        }
    }
}

/**
 * „Zu Sammlung hinzufügen“: alle eigenen Sammlungen mit Haken; ein Tipp nimmt den Titel auf oder
 * heraus. Unten eine neue anlegen – sie bekommt den Titel gleich mit.
 */
@Composable
fun CollectionSheet(t: LibTitle, state: LibState, onDismiss: () -> Unit) {
    val host = LocalLibraryHost.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item {
                Text(tr("SAMMLUNGEN", "COLLECTIONS"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(tr("„${t.title}“ sammeln.", "Add “${t.title}” to…"), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                VSpace(18.dp)
            }
            item {
                ListGroup {
                    state.collections.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        val inside = t.id in c.items
                        ListRow(
                            title = c.name,
                            subtitle = if (c.items.size == 1) tr("1 Titel", "1 title") else tr("${c.items.size} Titel", "${c.items.size} titles"),
                            icon = Ic.bookmark,
                            showChevron = false,
                            onClick = { host.toggleInCollection(c.id, t.id) },
                            trailing = { CheckDot(inside) },
                        )
                    }
                    if (state.collections.isNotEmpty()) RowDivider()
                    ListRow(tr("Neue Sammlung", "New collection"), icon = Ic.add, showChevron = false, onClick = { naming = true })
                }
            }
        }
    }
    if (naming) CollectionNameDialog(tr("Neue Sammlung", "New collection"), "", onDismiss = { naming = false }) { name ->
        host.createCollection(name, t.id)
        naming = false
    }
}

/** Runder Haken, der sich füllt (Federschlag) – statt eines Kontrollkästchens. */
@Composable
fun CheckDot(checked: Boolean) {
    val s by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, androidx.compose.animation.core.tween(320, easing = BouncyEase), label = "check")
    Box(
        Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .border(1.5.dp, if (checked) Palette.ink else Palette.line, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(26.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = s.coerceIn(0f, 1f) }
                .clip(androidx.compose.foundation.shape.CircleShape).background(Palette.ink),
            contentAlignment = Alignment.Center,
        ) { GIcon(Ic.check, tint = Palette.onInk, size = 16.dp) }
    }
}

/** Name für eine Sammlung: neu oder umbenennen. */
@Composable
fun CollectionNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { de.marne.streamplayer.ui.design.IvoryField(name, { name = it.take(40) }, tr("z. B. Filmabend", "e.g. Movie night")) },
        confirmButton = { QuietButton(tr("Speichern", "Save"), color = if (name.isNotBlank()) Palette.text else Palette.faint) { if (name.isNotBlank()) onConfirm(name) } },
        dismissButton = { QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss) },
    )
}
