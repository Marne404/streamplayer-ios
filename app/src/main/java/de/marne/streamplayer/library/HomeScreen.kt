package de.marne.streamplayer.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Pill
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import java.time.LocalTime

/**
 * Startseite. Oben eine Bühne: bis zu sechs Empfehlungen randlos und groß, zum Durchwischen.
 * Darunter Regale in fester Reihenfolge – was du gerade schaust, was du dir gemerkt hast, deine
 * Sammlungen, dann Empfehlungen (teils als Kino-Karten), Top 10, Genres, Offline.
 * „Serien“/„Filme“ filtern die ganze Seite.
 */
@Composable
fun HomeScreen(state: LibState, actions: LibActions) {
    var kind by rememberSaveable { mutableStateOf<TitleKind?>(null) }
    val p = state.progress
    val titles = remember(state.titles, kind) { if (kind == null) state.titles else state.titles.filter { it.kind == kind } }
    val cont = remember(titles, p) { Shelves.continueWatching(titles, p) }
    val contIds = remember(cont) { cont.mapTo(HashSet()) { it.title.id } }
    val forYou = remember(titles, p, state.myList) { Recommend.forYou(titles, p, state.myList) }
    // Die Bühne: Empfehlungen mit Breitbild, Angefangenes nicht – das steht gleich darunter.
    val stage = remember(forYou, contIds) {
        forYou.filter { it.id !in contIds && it.wide != null }.take(6).ifEmpty { forYou.filter { it.cover != null }.take(6) }
    }
    val stageIds = remember(stage) { stage.mapTo(HashSet()) { it.id } }
    val myList = remember(state.titles, state.myList, kind) { state.myList.mapNotNull(state::byId).filter { kind == null || it.kind == kind } }
    val collections = remember(state.titles, state.collections, kind) {
        state.collections.map { c -> c to c.items.mapNotNull(state::byId).filter { kind == null || it.kind == kind } }.filter { it.second.isNotEmpty() }
    }
    val because = remember(titles, p, contIds) { Recommend.becauseYouWatched(titles, p, exclude = contIds) }
    val genreRows = remember(titles, p, state.myList) { Recommend.genreRows(titles, p, state.myList) }
    val top = remember(titles) { Recommend.topRated(titles) }
    val newest = remember(titles) { titles.sortedByDescending { it.addedAt }.take(15) }
    val offline = remember(titles) { titles.filter { it.offline.isNotEmpty() } }

    val settings = de.marne.streamplayer.App.instance.settings
    val showContinue by settings.homeContinue.collectAsState()
    val showMyList by settings.homeMyList.collectAsState()

    val list = rememberLazyListState()
    // Deckend erst, wenn die Bühne fast weg ist: vorher liegt die Kopfzeile auf dem Bild.
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 600 } }
    var headerPx by remember { mutableStateOf(0) }
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 28.dp)) {
            if (state.titles.isEmpty()) {
                item { Spacer(Modifier.height(headerDp)) }
                item { EmptyLibrary(actions) }
                return@LazyColumn
            }
            item(key = "stage") { HeroStage(stage, state, actions, headerDp) }

            if (state.enrich.running) {
                item(key = "enrich") {
                    Row(Modifier.padding(start = 20.dp, top = 8.dp)) {
                        Pill(tr("TMDB ergänzt · ${state.enrich.done} von ${state.enrich.total}", "Adding TMDB info · ${state.enrich.done} of ${state.enrich.total}"), icon = Ic.sparkle)
                    }
                }
            }
            if (showContinue && cont.isNotEmpty()) {
                shelf(tr("Weiterschauen", "Continue Watching"), key = "continue", onAll = if (cont.size > 3) ({ actions.grid(GridSpec("continue")) }) else null) {
                    items(cont, key = { it.key }) { c ->
                        WideTile(
                            image = c.episode?.still ?: c.title.wide, title = c.title.title, meta = continueMeta(c), width = 236.dp,
                            progress = c.progress?.takeIf { !it.finished }?.fraction,
                            badgeIcon = Ic.play, badge = if (c.isNextUp) tr("Nächste Folge", "Next episode") else null,
                            onClick = { actions.playContinue(c) }, onLongClick = { actions.preview(c.title) },
                        )
                    }
                }
            }
            if (showMyList && myList.isNotEmpty()) {
                shelf(tr("Meine Liste", "My List"), key = "mylist", onAll = { actions.grid(GridSpec("list")) }) {
                    items(myList, key = { "l" + it.id }) { TitlePoster(it, state, actions, origin = "list") }
                }
            }
            collections.forEach { (c, items) ->
                shelf(c.name, key = "c:${c.id}", onAll = { actions.grid(GridSpec("collection", c.id)) }) {
                    items(items, key = { "c${c.id}" + it.id }) { TitlePoster(it, state, actions, origin = "c${c.id}") }
                }
            }
            val fy = forYou.filter { it.id !in stageIds && it.id !in contIds }.take(12)
            if (fy.isNotEmpty()) {
                shelf(tr("Für dich", "For You"), key = "foryou", onAll = { actions.grid(GridSpec("foryou")) }) {
                    items(fy, key = { "f" + it.id }) { CinematicCard(it, "foryou", actions) }
                }
            }
            if (top.size >= 3) {
                shelf(tr("Top 10 in deiner Bibliothek", "Top 10 in your library"), key = "top", onAll = { actions.grid(GridSpec("top")) }, spacing = 4.dp) {
                    itemsIndexed(top, key = { _, t -> "t" + t.id }) { i, t ->
                        val k = coverKey("top", t.id)
                        TopTenTile(i + 1, t, onClick = { actions.show(t, k) }, onLongClick = { actions.preview(t) }, sharedKey = k)
                    }
                }
            }
            because?.let { (anchor, similar) ->
                shelf(tr("Weil du „${anchor.title}“ geschaut hast", "Because you watched “${anchor.title}”"), key = "because", onAll = { actions.grid(GridSpec("because", anchor.id)) }) {
                    items(similar, key = { "b" + it.id }) { TitlePoster(it, state, actions, origin = "because") }
                }
            }
            if (titles.size >= 8) {
                shelf(tr("Neu in der Bibliothek", "New in your library"), key = "new", onAll = { actions.grid(GridSpec("new")) }) {
                    items(newest, key = { "n" + it.id }) { TitlePoster(it, state, actions, origin = "new") }
                }
            }
            genreRows.forEachIndexed { i, (genre, list) ->
                // Das erste Genre als Kino-Reihe: Abwechslung im Rhythmus, nicht nur Poster an Poster.
                shelf(genre, key = "g:$genre", onAll = { actions.grid(GridSpec("genre", genre)) }) {
                    if (i == 0) items(list.take(10), key = { "g$genre" + it.id }) { CinematicCard(it, "g$genre", actions, width = 260.dp) }
                    else items(list.take(15), key = { "g$genre" + it.id }) { TitlePoster(it, state, actions, origin = "g$genre") }
                }
            }
            if (offline.isNotEmpty()) {
                shelf(tr("Offline verfügbar", "Available offline"), key = "offline", onAll = { actions.grid(GridSpec("offline")) }) {
                    items(offline, key = { "o" + it.id }) { TitlePoster(it, state, actions, origin = "offline") }
                }
            }
            if (genreRows.isEmpty() && state.titles.none { it.genres.isNotEmpty() }) {
                item(key = "hint") {
                    Text(
                        tr(
                            "Mit einem TMDB-Schlüssel (Einstellungen → Mediathek) kommen Genres, Bewertungen und Empfehlungen dazu.",
                            "Add a TMDB key (Settings → Library) to get genres, ratings and recommendations.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp),
                    )
                }
            }
        }

        HomeHeader(state, kind, { kind = it }, solid, actions, Modifier.onSizeChanged { headerPx = it.height })
    }
}

/**
 * Kopf über der Bühne: Gruß, Suche, Profil, Filter. Liegt zuerst durchsichtig auf dem Bild,
 * wird beim Scrollen deckend. Bewusst flach – die Bühne ist das Bild, nicht der Kopf.
 */
@Composable
private fun HomeHeader(state: LibState, kind: TitleKind?, onKind: (TitleKind?) -> Unit, solid: Boolean, actions: LibActions, modifier: Modifier) {
    val alpha by animateFloatAsState(if (solid) 1f else 0f, tween(220, easing = SpringEase), label = "header")
    Column(modifier.fillMaxWidth().background(Palette.bg.copy(alpha = alpha)).statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting().uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Text(tr("Für ${state.profile.name}", "For ${state.profile.name}"), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = actions.search) { GIcon(Ic.search) }
            IconButton(onClick = actions.profiles) { de.marne.streamplayer.account.ProfileAvatar(state.profile, 32.dp) }
        }
        // Filter erst beim Weiterscrollen: oben gehört der Platz der Bühne. Ist ein Filter gesetzt,
        // bleiben sie stehen – sonst sähe man nicht, warum die Startseite nur Filme zeigt.
        // Der Kopf liegt über der Liste: Aufklappen schiebt nichts, es kommt nur von oben herein.
        androidx.compose.animation.AnimatedVisibility(
            visible = state.titles.isNotEmpty() && (solid || kind != null),
            enter = androidx.compose.animation.expandVertically(tween(420, easing = BouncyEase), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(tween(220, delayMillis = 80)) +
                androidx.compose.animation.slideInVertically(tween(420, easing = BouncyEase)) { -it / 3 },
            exit = androidx.compose.animation.shrinkVertically(tween(320, easing = SpringEase), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(tween(160)),
        ) {
            KindChips(kind, onKind, Modifier.padding(start = 20.dp, top = 6.dp, bottom = 10.dp)) {
                CategoryPill { actions.categories(kind) }
            }
        }
        VSpace(if (state.titles.isEmpty()) 10.dp else 0.dp)
    }
}

@Composable
private fun CategoryPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).border(1.dp, Palette.line, shape).background(Palette.bg.copy(alpha = 0.6f)).clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Kategorien", "Categories"), style = MaterialTheme.typography.labelMedium)
        GIcon(Ic.expandMore, size = 16.dp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** „Abspielen“, „Fortsetzen“, „Weiter · S2 · F4“ – was ein Tipp gerade bewirkt. */
fun playLabel(t: LibTitle, progress: Map<String, Progress>): String {
    if (t.kind == TitleKind.MOVIE) {
        val p = progress[Keys.movie(t)]
        return if (p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS) tr("Fortsetzen", "Resume") else tr("Abspielen", "Play")
    }
    val next = Shelves.nextFor(t, progress) ?: return tr("Von vorn", "Play from start")
    val (e, p) = next
    return if (p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS) tr("Weiter · ${e.label}", "Resume · ${e.label}")
    else if (progress.keys.any { it.startsWith("e:${t.id}:") }) e.label else tr("Abspielen", "Play")
}

@Composable
private fun EmptyLibrary(actions: LibActions) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        VSpace(24.dp)
        Text(tr("Noch leer.", "Nothing here yet."), style = MaterialTheme.typography.headlineSmall)
        VSpace(6.dp)
        Text(
            tr(
                "Filme und Serien kommen als Katalog-Datei. Bauen lässt sie sich im Streamplayer Creator; von dort mit „Teilen“ direkt hierher.",
                "Movies and shows arrive as a catalog file. Build one in Streamplayer Creator and send it here with “Share”.",
            ),
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
        )
        VSpace(28.dp)
        PrimaryButton(tr("Katalog importieren", "Import catalog"), icon = Ic.importFile, onClick = actions.import)
        VSpace(4.dp)
        QuietButton(tr("Im Browser suchen", "Search in browser"), color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally), onClick = actions.browser)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> tr("Guten Morgen", "Good morning")
    in 11..17 -> tr("Guten Tag", "Good afternoon")
    else -> tr("Guten Abend", "Good evening")
}
