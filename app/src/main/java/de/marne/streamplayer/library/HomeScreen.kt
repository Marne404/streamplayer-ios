package de.marne.streamplayer.library

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Pill
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import java.time.LocalDate
import java.time.LocalTime

private val Ivory = Color(0xFFF4F1EA)

/**
 * Startseite wie Netflix, im Ivory-&-Ink-Kleid: oben eine große Empfehlung auf einem unscharfen
 * Abglanz ihres eigenen Bildes, darunter Regale – Weiterschauen, Meine Liste, Für dich, Top 10,
 * „Weil du … geschaut hast“, Neu, Genres, Offline. „Serien“/„Filme“ filtern die ganze Seite.
 */
@Composable
fun HomeScreen(state: LibState, actions: LibActions) {
    var kind by rememberSaveable { mutableStateOf<TitleKind?>(null) }
    val p = state.progress
    val titles = remember(state.titles, kind) { if (kind == null) state.titles else state.titles.filter { it.kind == kind } }
    val cont = remember(titles, p) { Shelves.continueWatching(titles, p) }
    val contIds = remember(cont) { cont.mapTo(HashSet()) { it.title.id } }
    val forYou = remember(titles, p, state.myList) { Recommend.forYou(titles, p, state.myList) }
    val hero = remember(forYou, contIds, titles) {
        Recommend.hero(forYou.filter { it.id !in contIds }.ifEmpty { forYou }, LocalDate.now().toEpochDay())
            ?: titles.maxByOrNull { it.addedAt }
    }
    val myList = remember(state.titles, state.myList, kind) { state.myList.mapNotNull(state::byId).filter { kind == null || it.kind == kind } }
    val because = remember(titles, p, contIds) { Recommend.becauseYouWatched(titles, p, exclude = contIds) }
    val genreRows = remember(titles, p, state.myList) { Recommend.genreRows(titles, p, state.myList) }
    val top = remember(titles) { Recommend.topRated(titles) }
    val newest = remember(titles) { titles.sortedByDescending { it.addedAt }.take(15) }
    val offline = remember(titles) { titles.filter { it.offline.isNotEmpty() } }

    val list = rememberLazyListState()
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 24 } }
    var headerPx by remember { mutableStateOf(0) }
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
            if (state.titles.isEmpty()) {
                item { Spacer(Modifier.height(headerDp)) }
                item { EmptyLibrary(actions) }
                return@LazyColumn
            }
            item(key = "hero") { HomeHero(hero, state, actions, headerDp) }

            if (state.enrich.running) {
                item(key = "enrich") {
                    Row(Modifier.padding(start = 20.dp, top = 20.dp)) {
                        Pill("TMDB ergänzt · ${state.enrich.done} von ${state.enrich.total}", icon = Ic.sparkle)
                    }
                }
            }
            if (cont.isNotEmpty()) {
                shelf("Weiterschauen, ${state.profile.name}", key = "continue", onAll = if (cont.size > 3) ({ actions.grid(GridSpec("continue")) }) else null) {
                    items(cont, key = { it.key }) { c ->
                        WideTile(
                            image = c.episode?.still ?: c.title.wide, title = c.title.title, meta = continueMeta(c), width = 232.dp,
                            progress = c.progress?.takeIf { !it.finished }?.fraction,
                            badgeIcon = Ic.play, badge = if (c.isNextUp) "Nächste Folge" else null,
                            onClick = { actions.playContinue(c) }, onLongClick = { actions.preview(c.title) },
                        )
                    }
                }
            }
            if (myList.isNotEmpty()) {
                shelf("Meine Liste", key = "mylist", onAll = { actions.grid(GridSpec("list")) }) {
                    items(myList, key = { "l" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            val fy = forYou.filter { it.id != hero?.id && it.id !in contIds }.take(15)
            if (fy.isNotEmpty()) {
                shelf("Für dich", key = "foryou", onAll = { actions.grid(GridSpec("foryou")) }) {
                    items(fy, key = { "f" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            if (top.size >= 3) {
                shelf("Top 10 in deiner Bibliothek", key = "top", onAll = { actions.grid(GridSpec("top")) }, spacing = 4.dp) {
                    itemsIndexed(top, key = { _, t -> "t" + t.id }) { i, t ->
                        TopTenTile(i + 1, t, onClick = { actions.preview(t) }, onLongClick = { actions.open(t) })
                    }
                }
            }
            because?.let { (anchor, similar) ->
                shelf("Weil du „${anchor.title}“ geschaut hast", key = "because", onAll = { actions.grid(GridSpec("because", anchor.id)) }) {
                    items(similar, key = { "b" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            if (titles.size >= 8) {
                shelf("Neu in der Bibliothek", key = "new", onAll = { actions.grid(GridSpec("new")) }) {
                    items(newest, key = { "n" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            genreRows.forEach { (genre, list) ->
                shelf(genre, key = "g:$genre", onAll = { actions.grid(GridSpec("genre", genre)) }) {
                    items(list.take(15), key = { "g$genre" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            if (offline.isNotEmpty()) {
                shelf("Offline verfügbar", key = "offline", onAll = { actions.grid(GridSpec("offline")) }) {
                    items(offline, key = { "o" + it.id }) { TitlePoster(it, state, actions) }
                }
            }
            if (genreRows.isEmpty() && state.titles.none { it.genres.isNotEmpty() }) {
                item(key = "hint") {
                    Text(
                        "Mit einem TMDB-Schlüssel (Einstellungen → Mediathek) kommen Genres, Bewertungen und Empfehlungen dazu.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp),
                    )
                }
            }
        }

        HomeHeader(state, kind, { kind = it }, solid, actions, Modifier.onSizeChanged { headerPx = it.height })
    }
}

/** Kopf über der Seite: Gruß, Profil, Suche und Filter. Durchsichtig über dem Hero, deckend beim Scrollen. */
@Composable
private fun HomeHeader(
    state: LibState,
    kind: TitleKind?,
    onKind: (TitleKind?) -> Unit,
    solid: Boolean,
    actions: LibActions,
    modifier: Modifier,
) {
    val alpha by animateFloatAsState(if (solid) 1f else 0f, tween(220, easing = SpringEase), label = "header")
    Column(modifier.fillMaxWidth().background(Palette.bg.copy(alpha = alpha)).statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting().uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Text("Für ${state.profile.name}", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = actions.search) { GIcon(Ic.search) }
            IconButton(onClick = actions.profiles) { de.marne.streamplayer.account.ProfileAvatar(state.profile, 32.dp) }
        }
        if (state.titles.isNotEmpty()) {
            KindChips(kind, onKind, Modifier.padding(start = 20.dp, top = 10.dp, bottom = 12.dp)) {
                CategoryPill { actions.categories(kind) }
            }
        } else VSpace(12.dp)
    }
}

@Composable
private fun CategoryPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).border(1.dp, Palette.line, shape).clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Kategorien", style = MaterialTheme.typography.labelMedium)
        GIcon(Ic.expandMore, size = 16.dp, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * Die eine große Karte: Poster (oder Breitbild mit Titel), Genres, „Abspielen“ und „Meine Liste“.
 * Dahinter derselbe Titel unscharf und blass – die Seite nimmt die Farbe des Titels an, ohne dass
 * eine Farbanalyse nötig ist. Unter Android 12 gibt es kein Weichzeichnen; dann bleibt es beim Blass.
 */
@Composable
private fun HomeHero(t: LibTitle?, state: LibState, actions: LibActions, topInset: Dp) {
    if (t == null) { Spacer(Modifier.height(topInset)); return }
    val poster = t.customPoster ?: t.poster ?: t.tmdb?.poster
    val image = poster ?: t.wide ?: t.offline.values.firstOrNull()
    val ambient = t.wide ?: image
    val inList = state.inList(t)
    val label = playLabel(t, state.progress)
    Box(Modifier.fillMaxWidth()) {
        Thumb(
            Tmdb.largeModel(ambient),
            Modifier.matchParentSize().graphicsLayer { alpha = if (Build.VERSION.SDK_INT >= 31) 0.6f else 0.3f }.blur(48.dp),
            radius = 0.dp,
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Palette.bg.copy(alpha = 0.25f), 0.6f to Palette.bg.copy(alpha = 0.55f), 1f to Palette.bg)))
        val press = remember { MutableInteractionSource() }
        // Quer ist der Schirm flach: die Karte darf höchstens gut die halbe Höhe einnehmen, sonst
        // sähe man vom Rest der Seite nichts.
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val ratio = if (poster != null) 0.74f else 1.3f
        val maxW = minOf(420.dp, screenH * 0.62f * ratio).coerceAtLeast(200.dp)
        Box(
            Modifier.padding(top = topInset + 6.dp, start = 28.dp, end = 28.dp, bottom = 4.dp)
                .widthIn(max = maxW).align(Alignment.TopCenter)
                .fillMaxWidth().aspectRatio(ratio)
                .pressScale(press, 0.97f)
                .clip(RoundedCornerShape(28.dp)).background(Palette.ink)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .clickable(interactionSource = press, indication = null) { actions.preview(t) },
        ) {
            Thumb(Tmdb.largeModel(image), Modifier.fillMaxSize(), radius = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))))
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Poster tragen ihren Titel schon im Bild; ein Breitbild nicht – dann schreiben wir ihn hin.
                if (poster == null) {
                    Text(t.title, style = MaterialTheme.typography.headlineMedium, color = Ivory, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(6.dp)
                }
                val line = genreLine(t).ifEmpty { factLine(t) }
                if (line.isNotEmpty()) {
                    Text(line.replace(" · ", "  •  "), style = MaterialTheme.typography.labelMedium, color = Ivory.copy(alpha = 0.9f), maxLines = 1)
                    VSpace(14.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImageButton(label, Ic.play, primary = true, modifier = Modifier.weight(1f)) { actions.play(t, null) }
                    ImageButton("Meine Liste", if (inList) Ic.check else Ic.add, primary = false, modifier = Modifier.weight(1f)) { actions.toggleList(t) }
                }
            }
        }
    }
}

/** „Abspielen“, „Fortsetzen“, „S2 · F4 abspielen“ – was ein Tipp gerade bewirkt. */
fun playLabel(t: LibTitle, progress: Map<String, Progress>): String {
    if (t.kind == TitleKind.MOVIE) {
        val p = progress[Keys.movie(t)]
        return if (p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS) "Fortsetzen" else "Abspielen"
    }
    val next = Shelves.nextFor(t, progress) ?: return "Von vorn"
    val (e, p) = next
    return if (p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS) "Weiter · ${e.label}"
    else if (progress.keys.any { it.startsWith("e:${t.id}:") }) e.label else "Abspielen"
}

@Composable
private fun EmptyLibrary(actions: LibActions) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        VSpace(24.dp)
        Text("Noch leer.", style = MaterialTheme.typography.headlineSmall)
        VSpace(6.dp)
        Text(
            "Filme und Serien kommen als Katalog-Datei. Bauen lässt sie sich im Streamplayer Creator; von dort mit „Teilen“ direkt hierher.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
        )
        VSpace(28.dp)
        PrimaryButton("Katalog importieren", icon = Ic.importFile, onClick = actions.import)
        VSpace(4.dp)
        QuietButton("Im Browser suchen", color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally), onClick = actions.browser)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Guten Tag"
    else -> "Guten Abend"
}
