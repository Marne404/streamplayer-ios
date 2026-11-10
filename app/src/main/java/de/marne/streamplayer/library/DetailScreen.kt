package de.marne.streamplayer.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.ExpandableText
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

/** Was nur die Detailseite kann: Titel bearbeiten, Cover, TMDB, Seite öffnen, entfernen. */
class DetailActions(
    val openPage: (String) -> Unit,
    val rename: (LibTitle, String) -> Unit,
    val changeCover: (LibTitle) -> Unit,
    val resetCover: (LibTitle) -> Unit,
    val refreshTmdb: ((LibTitle) -> Unit)?,
    val remove: (LibTitle) -> Unit,
)

/**
 * Detailseite wie bei Netflix: Bild randlos bis unter die Statusleiste, darunter Fakten, Genres,
 * ein großer Knopf, die Aktionen als runde Symbole, Tagline, Inhalt, Besetzung. Serien haben
 * die Reiter „Folgen“ und „Ähnliche“, Filme nur „Ähnliche“.
 */
@Composable
fun DetailScreen(t: LibTitle, state: LibState, actions: LibActions, detail: DetailActions) {
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val progress = state.progress
    val next = remember(t, progress) { Shelves.nextFor(t, progress) }
    val seasonNumbers = remember(t) { t.seasons.map { it.number }.sorted() }
    var season by rememberSaveable(t.id) { mutableIntStateOf(next?.first?.season ?: seasonNumbers.firstOrNull { it > 0 } ?: seasonNumbers.firstOrNull() ?: 1) }
    var tab by rememberSaveable(t.id) { mutableIntStateOf(0) }
    val similar = remember(t, state.titles) { Recommend.similar(t, state.titles) }
    val episodes = remember(t) { t.episodes() }
    val seen = Recommend.seen(t, progress)
    val list = rememberLazyListState()
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "hero") { DetailHero(t) }
            item(key = "info") { Info(t, state, actions, next, seen) }

            if (t.kind == TitleKind.SERIES) {
                item(key = "tabs") {
                    Tabs(listOf("Folgen", "Ähnliche"), tab, { tab = it }, Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp))
                }
                if (tab == 0) {
                    if (seasonNumbers.size > 1) {
                        item(key = "seasons") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                                items(seasonNumbers) { n -> OutlinePill(t.seasonLabel(n), selected = season == n, onClick = { season = n }) }
                            }
                        }
                    }
                    if (episodes.isEmpty()) {
                        item(key = "noeps") {
                            Text(
                                "Keine Folgen. Im Katalog fehlen Staffeln – oder TMDB liefert sie nach, sobald ein Schlüssel eingetragen ist.",
                                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(episodes.filter { it.season == season }, key = { "e${it.season}:${it.number}" }) { e ->
                        EpisodeRow(t, e, progress[Keys.episode(t, e)], t.offline[Keys.episode(t, e)] != null, actions, detail)
                    }
                } else similarGrid(similar, state, actions)
            } else if (similar.isNotEmpty()) {
                item(key = "simtitle") { SectionTitle("Ähnliche Titel", modifier = Modifier.padding(horizontal = 20.dp)) }
                similarGrid(similar, state, actions)
            }
        }

        DetailTopBar(t, solid, actions, detail, onRename = { renaming = true }, onRemove = { confirmRemove = true })
    }

    if (renaming) TextDialog("Umbenennen", t.title, "Titel", onDismiss = { renaming = false }) {
        renaming = false
        detail.rename(t, it)
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        containerColor = Palette.surface,
        title = { Text("Aus der Bibliothek entfernen?", style = MaterialTheme.typography.headlineSmall) },
        text = { Text("Fortschritt geht verloren. Geladene Dateien bleiben unter Downloads.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
        confirmButton = { QuietButton("Entfernen", color = Palette.wine) { confirmRemove = false; detail.remove(t) } },
        dismissButton = { QuietButton("Behalten", color = Palette.muted) { confirmRemove = false } },
    )
}

/** Randloses Bild; oben abgedunkelt für Statusleiste und Knöpfe, unten in den Grund verlaufend. */
@Composable
private fun DetailHero(t: LibTitle) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f)) {
        Thumb(Tmdb.large(t.wide) ?: t.offline.values.firstOrNull(), Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.bg)))
    }
}

@Composable
private fun Info(t: LibTitle, state: LibState, actions: LibActions, next: Pair<Episode, Progress?>?, seen: Recommend.Seen) {
    val p = state.progress
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text((if (t.kind == TitleKind.SERIES) "Serie" else "Film").uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        VSpace(6.dp)
        Text(t.title, style = MaterialTheme.typography.headlineLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        VSpace(8.dp)
        FactsRow(t)
        val g = genreLine(t, max = 4)
        if (g.isNotEmpty()) {
            VSpace(2.dp)
            Text(g, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        VSpace(22.dp)

        // Großer Knopf und – wenn angefangen – wie weit.
        PrimaryButton(playLabel(t, p), icon = Ic.play) { actions.play(t, null) }
        val resume = if (t.kind == TitleKind.MOVIE) p[Keys.movie(t)] else next?.second
        if (resume != null && !resume.finished && resume.positionMs > ProgressStore.MIN_RESUME_MS) {
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bar(resume.fraction, height = 4.dp, modifier = Modifier.weight(1f))
                Text(remaining(resume), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (t.kind == TitleKind.MOVIE) {
            val offline = t.offline[Keys.movie(t)] != null
            if (!offline && t.pageUrl != null) {
                VSpace(10.dp)
                SecondaryButton("Laden", icon = Ic.download) { actions.download(t, null) }
            } else if (offline) {
                VSpace(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    GIcon(Ic.check, tint = Palette.green, size = 16.dp)
                    Text("Offline verfügbar", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        VSpace(14.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionIcon(if (state.inList(t)) Ic.check else Ic.add, "Meine Liste", active = state.inList(t)) { actions.toggleList(t) }
            ActionIcon(Ic.checkCircle, if (seen == Recommend.Seen.DONE) "Gesehen" else "Als gesehen", active = seen == Recommend.Seen.DONE) {
                actions.markWatched(t, seen != Recommend.Seen.DONE)
            }
            if (t.kind == TitleKind.SERIES && next != null) {
                ActionIcon(Ic.download, "Folge laden") { actions.download(t, next.first) }
            }
        }

        t.tmdb?.tagline?.let {
            VSpace(18.dp)
            Text("„$it“", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Serif, fontStyle = FontStyle.Italic), color = Palette.text)
        }
        t.text?.let {
            VSpace(12.dp)
            ExpandableText(it, collapsedLines = 4, style = MaterialTheme.typography.bodyMedium)
        }
        val cast = t.tmdb?.cast.orEmpty()
        val creators = t.tmdb?.creators.orEmpty()
        if (cast.isNotEmpty() || creators.isNotEmpty()) {
            VSpace(12.dp)
            if (cast.isNotEmpty()) Credit("Mit", cast.take(5).joinToString(", "))
            if (creators.isNotEmpty()) Credit(if (t.kind == TitleKind.SERIES) "Idee" else "Regie", creators.joinToString(", "))
        }
    }
}

/** „2008 · ★ 8,9 · 5 Staffeln · 62 Folgen“ – die Bewertung in Messing, der Rest gedämpft. */
@Composable
private fun FactsRow(t: LibTitle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val parts = buildList {
            t.shownYear?.let { add(it.toString()) }
            if (t.kind == TitleKind.SERIES) {
                val real = t.seasons.count { it.number > 0 }
                if (real > 0) add(if (real == 1) "1 Staffel" else "$real Staffeln")
                if (t.episodeCount > 0) add("${t.episodeCount} Folgen")
            } else t.runtimeMs?.let { minutesText(it) }?.let(::add)
            t.host?.let(::add)
        }
        t.rating?.let {
            Text(ratingText(it), style = MaterialTheme.typography.labelLarge, color = Palette.brass)
            if (parts.isNotEmpty()) Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Credit(label: String, names: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = Palette.faint)
        Text(names, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Reiter mit Strich darunter; der Strich wandert mit (Spring), statt zu springen. */
@Composable
private fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            val w by animateDpAsState(if (sel) 28.dp else 0.dp, tween(320, easing = SpringEase), label = "tab")
            Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp)) {
                Text(l, style = MaterialTheme.typography.titleMedium, color = if (sel) Palette.text else Palette.muted)
                VSpace(6.dp)
                Box(Modifier.width(w).height(3.dp).clip(CircleShape).background(Palette.ink))
            }
        }
    }
}

/** Ähnliche als Raster in der Liste: drei Poster je Zeile. */
private fun androidx.compose.foundation.lazy.LazyListScope.similarGrid(similar: List<LibTitle>, state: LibState, actions: LibActions) {
    if (similar.isEmpty()) {
        item(key = "nosim") {
            Text(
                "Noch nichts Ähnliches – dafür braucht es Genres von TMDB und ein paar Titel mehr.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    val rows = similar.chunked(3)
    items(rows.size, key = { "sim$it" }) { i ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows[i].forEach { s -> Box(Modifier.weight(1f)) { TitlePoster(s, state, actions, width = 400.dp) } }
            repeat(3 - rows[i].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Folge wie bei Netflix: Standbild mit Play-Zeichen und Fortschritt, daneben Nummer, Titel, Dauer,
 * rechts Laden; darunter der Inhalt in drei Zeilen.
 */
@Composable
private fun EpisodeRow(t: LibTitle, e: Episode, p: Progress?, offline: Boolean, actions: LibActions, detail: DetailActions) {
    val key = Keys.episode(t, e)
    val watched = p?.finished == true
    Column(Modifier.fillMaxWidth().clickable { actions.play(t, e) }.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f)) {
                Thumb(Tmdb.largeModel(e.still ?: t.wide), Modifier.fillMaxSize(), radius = 12.dp)
                Box(
                    Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { GIcon(Ic.play, tint = Color.White, size = 18.dp) }
                if (watched) Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape).background(Palette.ink).padding(3.dp)
                ) { GIcon(Ic.check, tint = Palette.onInk, size = 12.dp) }
                if (p != null && !watched && p.positionMs > 0) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Bar(p.fraction, height = 3.dp, color = Color.White, track = Color.White.copy(alpha = 0.3f))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    e.title?.let { "${e.number}. $it" } ?: "Folge ${e.number}",
                    style = MaterialTheme.typography.titleSmall, color = if (watched) Palette.muted else Palette.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    e.runtimeMin?.let { add("$it Min.") }
                    when {
                        watched -> add("gesehen")
                        p != null && p.positionMs > 0 -> add(remaining(p))
                    }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
            }
            if (offline) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { GIcon(Ic.check, tint = Palette.green, size = 20.dp) }
            else IconButton(onClick = { actions.download(t, e) }) { GIcon(Ic.download, tint = Palette.muted, size = 20.dp) }
            RowMenu(buildList {
                add(MenuAction(if (watched) "Als ungesehen markieren" else "Als gesehen markieren") { actions.setWatched(key, !watched) })
                add(MenuAction("Seite öffnen") { detail.openPage(e.url) })
            })
        }
        e.overview?.let {
            VSpace(8.dp)
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** Zurück und Menü schweben über dem Bild; beim Scrollen wird daraus eine ruhige Kopfzeile mit Titel. */
@Composable
private fun DetailTopBar(t: LibTitle, solid: Boolean, actions: LibActions, detail: DetailActions, onRename: () -> Unit, onRemove: () -> Unit) {
    val alpha by animateFloatAsState(if (solid) 1f else 0f, tween(220, easing = SpringEase), label = "bar")
    Row(
        Modifier.fillMaxWidth().background(Palette.bg.copy(alpha = alpha)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(solid) { IconButton(onClick = actions.back) { GIcon(Ic.back) } }
        AnimatedContent(solid, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(200)) }, label = "title", modifier = Modifier.weight(1f)) { s ->
            if (s) Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            else Spacer(Modifier.fillMaxWidth())
        }
        CircleButton(solid) {
            RowMenu(buildList {
                add(MenuAction("Cover ändern") { detail.changeCover(t) })
                if (t.customPoster != null) add(MenuAction("Eigenes Cover entfernen") { detail.resetCover(t) })
                detail.refreshTmdb?.let { r -> add(MenuAction("TMDB neu laden") { r(t) }) }
                add(MenuAction("Umbenennen") { onRename() })
                t.pageUrl?.let { url -> add(MenuAction("Seite öffnen") { detail.openPage(url) }) }
                add(MenuAction("Entfernen", destructive = true) { onRemove() })
            })
        }
    }
}

/** Runder, halbdurchsichtiger Grund für Knöpfe über dem Bild; verschwindet, sobald die Leiste deckt. */
@Composable
private fun CircleButton(solid: Boolean, content: @Composable () -> Unit) {
    val a by animateFloatAsState(if (solid) 0f else 0.72f, tween(220), label = "circle")
    Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.bg.copy(alpha = a)), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun TextDialog(title: String, initial: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { IvoryField(text, { text = it }, placeholder) },
        confirmButton = { QuietButton("Speichern", color = Palette.text) { onConfirm(text) } },
        dismissButton = { QuietButton("Abbrechen", color = Palette.muted, onClick = onDismiss) },
    )
}
