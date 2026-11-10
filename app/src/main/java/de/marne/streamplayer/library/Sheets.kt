package de.marne.streamplayer.library

import androidx.compose.foundation.layout.Arrangement
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
import de.marne.streamplayer.catalog.Tmdb
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
                Thumb(Tmdb.largeModel(t.cover ?: t.offline.values.firstOrNull()), Modifier.size(width = 92.dp, height = 138.dp), radius = 14.dp)
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
            if (movieP != null && !movieP.finished && movieP.positionMs > ProgressStore.MIN_RESUME_MS) {
                VSpace(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(movieP.fraction, height = 4.dp, modifier = Modifier.weight(1f))
                    Text(remaining(movieP), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp))
                }
            }
            VSpace(12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionIcon(if (state.inList(t)) Ic.check else Ic.add, "Meine Liste", active = state.inList(t)) { actions.toggleList(t) }
                ActionIcon(Ic.checkCircle, if (seen == Recommend.Seen.DONE) "Gesehen" else "Als gesehen", active = seen == Recommend.Seen.DONE) {
                    actions.markWatched(t, seen != Recommend.Seen.DONE)
                }
                if (t.kind == TitleKind.MOVIE && t.offline.isEmpty() && t.pageUrl != null) {
                    ActionIcon(Ic.download, "Laden") { then { actions.download(t, null) } }
                }
            }
            VSpace(8.dp)
            Hairline()
            ListRow(
                title = if (t.kind == TitleKind.SERIES) "Folgen & Details" else "Details & mehr",
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
                Text("KATEGORIEN", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(
                    when (kind) { TitleKind.SERIES -> "Serien nach Genre."; TitleKind.MOVIE -> "Filme nach Genre."; null -> "Wonach ist dir?" },
                    style = MaterialTheme.typography.headlineSmall,
                )
                VSpace(18.dp)
            }
            item {
                ListGroup {
                    val allSpec = when (kind) { TitleKind.SERIES -> GridSpec("series"); TitleKind.MOVIE -> GridSpec("movies"); null -> GridSpec("all") }
                    ListRow(
                        title = when (kind) { TitleKind.SERIES -> "Alle Serien"; TitleKind.MOVIE -> "Alle Filme"; null -> "Alle Titel" },
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
                        "Noch keine Genres. Sie kommen von TMDB – Schlüssel unter Einstellungen → Mediathek.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
            }
        }
    }
}
