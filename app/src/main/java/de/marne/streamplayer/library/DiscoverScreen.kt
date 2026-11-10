package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr

/**
 * „Entdecken“: Suche über Titel, Besetzung, Genres und Jahr; ohne Suche Kategorien als Karten
 * und Wege durch die ganze Bibliothek. [focusSearch]: von der Lupe der Startseite gekommen.
 */
@Composable
fun DiscoverScreen(state: LibState, actions: LibActions, focusSearch: Boolean, onFocused: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, state.titles) { Recommend.search(state.titles, query) }
    val genres = remember(state.titles) { Recommend.genres(state.titles) }
    // Bild je Genre: der am besten bewertete Titel mit Breitbild – und möglichst nicht zweimal derselbe.
    val genreImages = remember(state.titles, genres) {
        val used = HashSet<String>()
        genres.associate { (g, _) ->
            val pick = Recommend.inGenre(state.titles, g, emptyMap()).filter { it.wide != null }.let { l -> l.firstOrNull { it.id !in used } ?: l.firstOrNull() }
            pick?.let { used += it.id }
            g to pick?.wide
        }
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusSearch) { if (focusSearch) { focus.requestFocus(); onFocused() } }

    LazyVerticalGrid(
        GridCells.Adaptive(if (query.isBlank()) 156.dp else 104.dp),
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 12.dp else 18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
                Text(tr("ENTDECKEN", "DISCOVER"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(tr("Was läuft heute?", "What’s on tonight?"), style = MaterialTheme.typography.headlineLarge)
                VSpace(18.dp)
                SearchField(query, { query = it }, focus, onDone = { focusManager.clearFocus() })
                VSpace(if (query.isBlank()) 6.dp else 18.dp)
            }
        }
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(tr("Nichts zu „$query“ in deiner Bibliothek.", "Nothing for “$query” in your library."), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
            }
            items(results, key = { "s" + it.id }) { TitlePoster(it, state, actions, width = 400.dp) }
            return@LazyVerticalGrid
        }

        if (genres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading(tr("Kategorien", "Categories")) }
            items(genres, key = { "g" + it.first }) { (g, n) ->
                GenreCard(g, n, genreImages[g], Modifier.fillMaxWidth()) { actions.grid(GridSpec("genre", g)) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeading(tr("Stöbern", "Browse"))
                val t = state.titles
                ListGroup {
                    ListRow(tr("Alle Titel", "All titles"), icon = Ic.library, value = t.size.toString(), onClick = { actions.grid(GridSpec("all")) })
                    RowDivider()
                    ListRow(tr("Serien", "Shows"), icon = Ic.tv, value = t.count { it.kind == TitleKind.SERIES }.toString(), onClick = { actions.grid(GridSpec("series")) })
                    RowDivider()
                    ListRow(tr("Filme", "Movies"), icon = Ic.movie, value = t.count { it.kind == TitleKind.MOVIE }.toString(), onClick = { actions.grid(GridSpec("movies")) })
                    RowDivider()
                    ListRow(tr("Neu dazu", "Recently added"), icon = Ic.sparkle, onClick = { actions.grid(GridSpec("new")) })
                    val rated = t.count { it.rating != null }
                    if (rated > 0) {
                        RowDivider()
                        ListRow(tr("Am besten bewertet", "Top rated"), icon = Ic.bookmark, value = rated.toString(), onClick = { actions.grid(GridSpec("top")) })
                    }
                    val without = t.count { it.genres.isEmpty() }
                    if (without > 0 && genres.isNotEmpty()) {
                        RowDivider()
                        ListRow(tr("Ohne Genre", "No genre"), icon = Ic.info, value = without.toString(), onClick = { actions.grid(GridSpec("nogenre")) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Palette.surface).border(1.dp, Palette.line, shape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon(Ic.search, tint = Palette.muted, size = 20.dp)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (value.isEmpty()) Text(tr("Titel, Person, Genre, Jahr", "Titles, people, genres, years"), style = MaterialTheme.typography.bodyLarge, color = Palette.faint)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { GIcon(Ic.close, size = 18.dp) }
    }
}
