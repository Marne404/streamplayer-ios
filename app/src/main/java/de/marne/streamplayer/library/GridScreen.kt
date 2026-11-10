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

/** Überschrift und Unterzeile einer Übersicht. */
private fun headline(spec: GridSpec, state: LibState): Pair<String, String> {
    val genre = spec.arg?.substringBefore('|')
    return when (spec.kind) {
        "genre" -> genre.orEmpty() to "Genre"
        "foryou" -> "Für dich" to "Empfehlungen"
        "because" -> "Ähnlich wie „${spec.arg?.let(state::byId)?.title ?: "…"}“" to "Weil du es geschaut hast"
        "continue" -> "Weiterschauen" to state.profile.name
        "list" -> "Meine Liste" to state.profile.name
        "new" -> "Neu dazu" to "Bibliothek"
        "top" -> "Am besten bewertet" to "Nach TMDB"
        "series" -> "Serien" to "Bibliothek"
        "movies" -> "Filme" to "Bibliothek"
        "offline" -> "Offline" to "Ohne Netz"
        "watched" -> "Gesehen" to state.profile.name
        "nogenre" -> "Ohne Genre" to "Bibliothek"
        else -> "Alle Titel" to "Bibliothek"
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
        else -> t.sortedBy { it.title.lowercase() }
    }
}

/**
 * Übersicht als Raster: Genre, „Alle“ hinter einem Regal, Meine Liste, Gesehen …
 * Oben Filter (Serien/Filme, wenn beides vorkommt) und Sortierung. Weiterschauen zeigt Breitbilder.
 */
@Composable
fun GridScreen(spec: GridSpec, state: LibState, actions: LibActions) {
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
            item(span = { GridItemSpan(maxLineSpan) }) { GridHead(title, eyebrow, "${cont.size} angefangen", actions.back) }
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
                GridHead(title, eyebrow, if (shown.size == 1) "1 Titel" else "${shown.size} Titel", actions.back)
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
        items(shown, key = { it.id }) { t -> TitlePoster(t, state, actions, width = 400.dp) }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (spec.kind) {
                        "list" -> "Noch nichts vorgemerkt. Bei einem Titel auf „Meine Liste“ tippen."
                        "watched" -> "Noch nichts fertig geschaut."
                        else -> "Hier ist nichts."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GridHead(title: String, eyebrow: String, count: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { GIcon(Ic.back) }
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp))
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f, fill = false))
        }
        Text(count, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
    }
}
