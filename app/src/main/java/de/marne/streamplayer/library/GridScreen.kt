package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr

/** Überschrift und Unterzeile einer Übersicht. */
private fun headline(spec: GridSpec, state: LibState): Pair<String, String> {
    val genre = spec.arg?.substringBefore('|')
    return when (spec.kind) {
        "genre" -> genre.orEmpty() to "Genre"
        "foryou" -> tr("Für dich", "For You") to tr("Empfehlungen", "Recommendations")
        "because" -> (spec.arg?.let(state::byId)?.title ?: "…").let { tr("Ähnlich wie „$it“", "Similar to “$it”") } to tr("Weil du es geschaut hast", "Because you watched it")
        "continue" -> tr("Weiterschauen", "Continue Watching") to state.profile.name
        "list" -> tr("Meine Liste", "My List") to state.profile.name
        "new" -> tr("Neu dazu", "Recently added") to tr("Bibliothek", "Library")
        "top" -> tr("Am besten bewertet", "Top rated") to tr("Nach TMDB", "On TMDB")
        "series" -> tr("Serien", "Shows") to tr("Bibliothek", "Library")
        "movies" -> tr("Filme", "Movies") to tr("Bibliothek", "Library")
        "offline" -> "Offline" to tr("Ohne Netz", "No connection needed")
        "watched" -> tr("Gesehen", "Watched") to state.profile.name
        "nogenre" -> tr("Ohne Genre", "No genre") to tr("Bibliothek", "Library")
        "collection" -> (state.collections.firstOrNull { it.id == spec.arg }?.name ?: "…") to tr("Sammlung", "Collection")
        "person" -> spec.arg.orEmpty() to tr("Mit dabei", "Featuring")
        "notmdb" -> tr("Ohne TMDB-Treffer", "No TMDB match") to tr("Zum Zuordnen", "To match")
        else -> tr("Alle Titel", "All titles") to tr("Bibliothek", "Library")
    }
}

/** Die Titel einer Übersicht in ihrer natürlichen Reihenfolge (die Sortierung „Empfohlen“ ordnet danach neu). */
fun gridTitles(spec: GridSpec, state: LibState): List<LibTitle> {
    val t = state.titles
    val p = state.progress
    return when (spec.kind) {
        "genre" -> {
            val (g, k) = spec.arg.orEmpty().split('|').let { it[0] to it.getOrNull(1) }
            Recommend.inGenre(t, g, p).filter { k == null || it.kind.name == k }
        }
        "foryou" -> Recommend.forYou(t, p, state.myList)
        "because" -> spec.arg?.let(state::byId)?.let { Recommend.similar(it, t, limit = 60) }.orEmpty()
        "list" -> state.myList.mapNotNull(state::byId)
        "new" -> t.sortedByDescending { it.addedAt }
        "top" -> t.filter { it.rating != null }.sortedByDescending { it.rating }
        "series" -> t.filter { it.kind == TitleKind.SERIES }
        "movies" -> t.filter { it.kind == TitleKind.MOVIE }
        "offline" -> t.filter { it.offline.isNotEmpty() }
        "watched" -> t.filter { Recommend.seen(it, p) == Recommend.Seen.DONE }
        "nogenre" -> t.filter { it.genres.isEmpty() }
        "collection" -> state.collections.firstOrNull { it.id == spec.arg }?.items?.mapNotNull(state::byId).orEmpty()
        "notmdb" -> t.filter { it.tmdb == null && it.tmdbId == null }.sortedBy { it.title.lowercase() }
        "person" -> t.filter { spec.arg in it.tmdb?.cast.orEmpty() || spec.arg in it.tmdb?.creators.orEmpty() }.sortedByDescending { it.shownYear ?: 0 }
        else -> t.sortedBy { it.title.lowercase() }
    }
}

/**
 * Übersicht als Raster: Genre, „Alle“ hinter einem Regal, Meine Liste, Gesehen …
 * Oben Filter (Serien/Filme, wenn beides vorkommt) und Sortierung. Weiterschauen zeigt Breitbilder.
 */
@Composable
fun GridScreen(spec: GridSpec, state: LibState, actions: LibActions) {
    var editing by remember { mutableStateOf(false) }
    if (editing) state.collections.firstOrNull { it.id == spec.arg }?.let { c ->
        CollectionEditDialog(c, onDismiss = { editing = false }, onDeleted = { editing = false; actions.back() })
    }
    var kind by rememberSaveable(spec) { mutableStateOf<TitleKind?>(null) }
    // „Natürlich“ = die Reihenfolge, die das Regal selbst hat; die anderen Sortierungen ordnen um.
    var sort by rememberSaveable(spec) { mutableStateOf<Recommend.Sort?>(null) }
    val (title, eyebrow) = headline(spec, state)

    if (spec.kind == "continue") {
        val cont = remember(state.titles, state.progress) { Shelves.continueWatching(state.titles, state.progress) }
        LazyVerticalGrid(
            GridCells.Adaptive(168.dp),
            Modifier.fillMaxSize().background(Palette.bg),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GridHead(title, eyebrow, tr("${cont.size} angefangen", "${cont.size} in progress"), actions.back) }
            items(cont, key = { it.key }) { c ->
                WideTile(
                    image = c.episode?.still ?: c.title.wide, title = c.title.title, meta = continueMeta(c), width = 400.dp,
                    progress = c.progress?.takeIf { !it.finished }?.fraction,
                    onClick = { actions.playContinue(c) }, onLongClick = { actions.preview(c.title) },
                )
            }
        }
        return
    }

    val base = remember(spec, state.titles, state.progress, state.myList) { gridTitles(spec, state) }
    val mixed = remember(base) { base.any { it.kind == TitleKind.SERIES } && base.any { it.kind == TitleKind.MOVIE } }
    val shown = remember(base, kind, sort, state.progress, state.myList) {
        val filtered = if (kind == null) base else base.filter { it.kind == kind }
        sort?.let { Recommend.sort(filtered, it, state.progress, state.myList, state.titles) } ?: filtered
    }

    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                GridHead(
                    title, eyebrow, if (shown.size == 1) tr("1 Titel", "1 title") else tr("${shown.size} Titel", "${shown.size} titles"), actions.back,
                    onEdit = if (spec.kind == "collection") ({ editing = true }) else null,
                )
                if (mixed) KindChips(kind, { kind = it }, Modifier.padding(bottom = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Recommend.Sort.entries) { s ->
                        val selected = (sort ?: Recommend.Sort.RECOMMENDED.takeIf { spec.kind == "foryou" }) == s
                        OutlinePill(s.label, selected = selected, onClick = { sort = if (sort == s) null else s })
                    }
                }
                VSpace(14.dp)
            }
        }
        items(shown, key = { it.id }) { t -> TitlePoster(t, state, actions, width = 400.dp, origin = "grid-${spec.kind}${spec.arg.orEmpty()}", showNew = spec.kind != "new") }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (spec.kind) {
                        "list" -> tr("Noch nichts vorgemerkt. Bei einem Titel auf „Meine Liste“ tippen.", "Nothing saved yet. Tap “My List” on any title.")
                        "collection" -> tr("Noch leer. Bei einem Titel auf „Sammlung“ tippen.", "Empty so far. Tap “Collection” on any title.")
                        "watched" -> tr("Noch nichts fertig geschaut.", "Nothing finished yet.")
                        else -> tr("Hier ist nichts.", "Nothing here.")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GridHead(title: String, eyebrow: String, count: String, onBack: () -> Unit, onEdit: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { GIcon(Ic.back) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (onEdit != null) IconButton(onClick = onEdit) { GIcon(Ic.edit) }
        }
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f, fill = false))
        }
        Text(count, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
    }
}

/** Sammlung umbenennen oder löschen. Löschen nimmt nur die Sammlung weg, nicht die Titel. */
@Composable
private fun CollectionEditDialog(c: de.marne.streamplayer.account.TitleCollection, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val app = de.marne.streamplayer.App.instance
    var name by remember { mutableStateOf(c.name) }
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        title = { Text(if (confirm) tr("„${c.name}“ löschen?", "Delete “${c.name}”?") else tr("Sammlung bearbeiten", "Edit collection"), style = MaterialTheme.typography.headlineSmall) },
        text = {
            if (confirm) Text(tr("Die Titel bleiben in der Bibliothek.", "The titles stay in your library."), color = Palette.muted)
            else Column {
                de.marne.streamplayer.ui.design.IvoryField(name, { name = it.take(40) }, tr("Name", "Name"))
                de.marne.streamplayer.ui.design.QuietButton(tr("Sammlung löschen", "Delete collection"), color = Palette.wine) { confirm = true }
            }
        },
        confirmButton = {
            if (confirm) de.marne.streamplayer.ui.design.QuietButton(tr("Löschen", "Delete"), color = Palette.wine) { app.profiles.deleteCollection(c.id); onDeleted() }
            else de.marne.streamplayer.ui.design.QuietButton(tr("Speichern", "Save"), color = Palette.text) { app.profiles.renameCollection(c.id, name); onDismiss() }
        },
        dismissButton = { de.marne.streamplayer.ui.design.QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss) },
    )
}
