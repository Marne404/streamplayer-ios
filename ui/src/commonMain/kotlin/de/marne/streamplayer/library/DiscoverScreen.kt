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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import de.marne.streamplayer.ui.design.OutlinePill
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * „Entdecken“: Suche über Titel, Besetzung, Genres und Jahr; ohne Suche Kategorien als Karten
 * und Wege durch die ganze Bibliothek. [focusSearch]: von der Lupe der Startseite gekommen.
 */
@Composable
fun DiscoverScreen(state: LibState, actions: LibActions, focusSearch: Boolean, onFocused: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // Suchen kostet bei großen Bibliotheken spürbar Zeit – nie auf dem UI-Thread, sonst stockt
    // die Tastatur. Der Index wird einmal je Titelliste gebaut (unveränderte Titel übernommen).
    val index by produceState(SearchIndex.cached(state.titles), state.titles) {
        if (value?.source === state.titles) return@produceState
        // Während der Anreicherung ändert sich die Liste laufend: kurz warten, ob noch mehr kommt.
        if (value != null) delay(400)
        value = withContext(Dispatchers.Default) { SearchIndex.of(state.titles, check = { coroutineContext.ensureActive() }) }
    }
    // Ergebnis mit der Anfrage, zu der es gehört: das alte bleibt stehen, bis das neue da ist.
    var result by remember { mutableStateOf<Pair<String, Recommend.Found>?>(null) }
    LaunchedEffect(query, index) {
        val idx = index ?: return@LaunchedEffect
        if (query.isBlank()) { result = null; return@LaunchedEffect }
        if (result != null) delay(80) // beim schnellen Tippen nicht jeden Buchstaben einzeln suchen
        result = query to withContext(Dispatchers.Default) { idx.searchAll(query) }
    }
    val found = result?.second ?: Recommend.Found.EMPTY
    val settled = result?.first == query
    val genres = remember(state.titles) { Recommend.genres(state.titles) }
    // Bild je Genre: der am besten bewertete Titel mit Breitbild – und möglichst nicht zweimal derselbe.
    // Einmal sortieren statt je Genre die ganze Bibliothek.
    val genreImages = remember(state.titles, genres) {
        val ranked = state.titles.filter { it.wide != null }
            .sortedWith(compareByDescending<LibTitle> { it.rating ?: 0.0 }.thenByDescending { it.addedAt })
        val used = HashSet<String>()
        genres.associate { (g, _) ->
            val inG = ranked.asSequence().filter { g in it.genres }
            val pick = inG.firstOrNull { it.id !in used } ?: inG.firstOrNull()
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
            if (found.isEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // Erst „nichts“ sagen, wenn die Suche zu genau dieser Eingabe fertig ist.
                    Text(
                        if (settled) tr("Nichts zu „$query“ in deiner Bibliothek.", "Nothing for “$query” in your library.") else tr("Sucht …", "Searching …"),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                }
            }
            // Personen zuerst: Wer einen Namen tippt, will meist alles mit ihm – ein Tipp zeigt es.
            if (found.people.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "people") {
                    Column {
                        SearchHeading(tr("Personen", "People"))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(found.people.size) { i ->
                                val (name, list) = found.people[i]
                                OutlinePill("$name · ${list.size}", onClick = { actions.grid(GridSpec("person", name)) })
                            }
                        }
                    }
                }
            }
            if (found.titles.isNotEmpty()) {
                if (found.people.isNotEmpty() || found.episodes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "th") { SearchHeading(tr("Titel", "Titles")) }
                items(found.titles, key = { "s" + it.id }) { TitlePoster(it, state, actions, width = 400.dp) }
            }
            if (found.episodes.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "eh") { SearchHeading(tr("Folgen", "Episodes")) }
                items(found.episodes, key = { (t, e) -> "se${t.id}:${e.season}:${e.number}" }, span = { GridItemSpan(maxLineSpan) }) { (t, e) ->
                    EpisodeHit(t, e, state.progress[Keys.episode(t, e)]?.finished == true, onClick = { actions.play(t, e) }, onOpen = { actions.open(t) })
                }
            }
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
                    // Ohne TMDB-Treffer: hier gesammelt, um sie auf der Detailseite zuzuordnen.
                    val unmatched = t.count { it.tmdb == null && it.tmdbId == null }
                    if (unmatched > 0 && LocalLibraryHost.current.tmdbAvailable && !state.enrich.running) {
                        RowDivider()
                        ListRow(tr("Ohne TMDB-Treffer", "No TMDB match"), icon = Ic.search, value = unmatched.toString(), onClick = { actions.grid(GridSpec("notmdb")) })
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
private fun SearchHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
}

/** Gefundene Folge: Standbild, Serie und Nummer, Titel. Antippen spielt, das Bild öffnet die Serie. */
@Composable
private fun EpisodeHit(t: LibTitle, e: Episode, watched: Boolean, onClick: () -> Unit, onOpen: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        de.marne.streamplayer.ui.design.Thumb(
            de.marne.streamplayer.catalog.TmdbImages.largeModel(e.still ?: t.wide),
            Modifier.width(116.dp).aspectRatio(16f / 9f).clickable(onClick = onOpen), radius = 12.dp,
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("${t.title} · ${e.label}", style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(e.title.orEmpty(), style = MaterialTheme.typography.titleSmall, color = if (watched) Palette.muted else Palette.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        GIcon(Ic.play, tint = Palette.muted, size = 18.dp, modifier = Modifier.padding(horizontal = 8.dp))
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
