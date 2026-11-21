package de.marne.streamplayer.library

import de.marne.streamplayer.ui.screenHeightDp
import de.marne.streamplayer.ui.screenWidthDp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import de.marne.streamplayer.catalog.TmdbImages
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
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import de.marne.streamplayer.ui.tr

/** Was nur die Detailseite kann: Titel bearbeiten, Cover, TMDB, Seite öffnen, entfernen. */
class DetailActions(
    val openPage: (String) -> Unit,
    val rename: (LibTitle, String) -> Unit,
    val changeCover: (LibTitle) -> Unit,
    val resetCover: (LibTitle) -> Unit,
    /**
     * Blatt „TMDB-Eintrag von Hand zuordnen“ – die Suche bei TMDB macht die Plattform (Android:
     * TmdbMatchSheet). null: Zuordnen gibt es hier nicht.
     */
    val matchSheet: (@Composable (LibTitle, onDismiss: () -> Unit) -> Unit)?,
    /**
     * Blatt „Cover wählen“ mit den Postern von TMDB (Android: CoverSheet). Liefert null für Titel
     * ohne TMDB-Treffer – dann geht „Cover ändern“ gleich in die Galerie ([changeCover]).
     */
    val coverSheet: (LibTitle) -> (@Composable (onDismiss: () -> Unit) -> Unit)?,
    /** In welchen Bibliotheken der Titel steht (mindestens einer). */
    val setLibraries: (LibTitle, List<String>) -> Unit,
    /** Nur aus dieser einen Bibliothek nehmen – in den anderen bleibt er. */
    val removeFrom: (LibTitle, String) -> Unit,
    /** Poster von TMDB als Cover (Adresse) – null: wieder das Standard-Poster. */
    val setCover: (LibTitle, String?) -> Unit,
    val refreshTmdb: ((LibTitle) -> Unit)?,
    val remove: (LibTitle) -> Unit,
)

/**
 * Detailseite wie bei Netflix: Bild randlos bis unter die Statusleiste, darunter Fakten, Genres,
 * ein großer Knopf, die Aktionen als runde Symbole, Tagline, Inhalt, Besetzung. Serien haben
 * die Reiter „Folgen“ und „Ähnliche“, Filme nur „Ähnliche“.
 */
@Composable
fun DetailScreen(t: LibTitle, state: LibState, actions: LibActions, detail: DetailActions, origin: String? = null) {
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var covering by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
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
    // Noch ohne TMDB-Daten oder ohne Folgen: jetzt holen, an der Warteschlange vorbei (bei großen
    // Bibliotheken wartet sie sonst lange, und Folgen holt sie dort gar nicht).
    val host = LocalLibraryHost.current
    val simColumns = maxOf(3, (screenWidthDp() - 40 + 12) / (140 + 12))
    androidx.compose.runtime.LaunchedEffect(t.id) { host.prioritizeEnrich(t.id) }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "hero") { DetailHero(t, origin) }
            // Ohne TMDB-Treffer (und nichts mehr unterwegs): darauf hinweisen, Zuordnen anbieten. Steht die
            // TMDB-ID schon fest (aus dem Katalog), ist der Titel gefunden – die Daten kommen gleich.
            val unmatched = detail.matchSheet != null && host.tmdbAvailable && t.tmdb == null && t.tmdbId == null && !state.enrich.running &&
                state.libraries.firstOrNull { it.id == t.library }?.readOnly != true
            item(key = "info") { Info(t, state, actions, next, seen, onMatch = if (unmatched) ({ matching = true }) else null) }

            if (t.kind == TitleKind.SERIES) {
                item(key = "tabs") {
                    Tabs(listOf(tr("Folgen", "Episodes"), tr("Ähnliche", "More like this")), tab, { tab = it }, Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp))
                }
                if (tab == 0) {
                    if (seasonNumbers.size > 1) {
                        item(key = "seasons") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                                items(seasonNumbers) { n -> OutlinePill(t.seasonLabel(n), selected = season == n, onClick = { season = n }) }
                            }
                        }
                    }
                    if (episodes.isNotEmpty()) item(key = "offline") { OfflineSummary(t, season, state, actions) }
                    if (episodes.isEmpty()) {
                        item(key = "noeps") {
                            Text(
                                tr(
                                    "Keine Folgen. Im Katalog fehlen Staffeln – oder TMDB liefert sie nach, sobald ein Schlüssel eingetragen ist.",
                                    "No episodes. The catalog has no seasons – or TMDB fills them in once a key is set.",
                                ),
                                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(episodes.filter { it.season == season }, key = { "e${it.season}:${it.number}" }) { e ->
                        EpisodeRow(t, e, progress[Keys.episode(t, e)], state.offline(t, Keys.episode(t, e)), actions, onMenu = { episodeMenu = e })
                    }
                } else similarGrid(similar, state, actions, simColumns)
            } else if (similar.isNotEmpty()) {
                item(key = "simtitle") { ShelfTitle(tr("Ähnliche Titel", "More like this"), null) }
                similarGrid(similar, state, actions, simColumns)
            }
        }

        DetailTopBar(t, solid, actions, onEdit = { editing = true })
    }

    if (editing) EditSheet(
        t, detail, onDismiss = { editing = false }, onRename = { renaming = true }, onRemove = { confirmRemove = true },
        // Mit TMDB erst die Auswahl der Poster (eigenes Bild ist dort die erste Kachel), sonst gleich die Galerie.
        onCover = { if (detail.coverSheet(t) != null) covering = true else detail.changeCover(t) },
        libraryLabel = state.libraries.filterNot { it.joined }.takeIf { it.size > 1 }
            ?.let { libs -> libs.filter { t.isIn(it.id) }.joinToString(", ") { it.label }.ifEmpty { libs.first().label } },
        sharedBy = state.libraries.firstOrNull { it.id == t.library }?.takeIf { it.readOnly }?.let { it.owner ?: tr("einem Freund", "a friend") },
        onMove = { moving = true },
        onMatch = detail.matchSheet?.takeIf { host.tmdbAvailable }?.let { { matching = true } },
    )
    if (matching) detail.matchSheet?.invoke(t) { matching = false }
    if (moving) LibraryToggleSheet(
        tr("Bibliotheken", "Libraries"), tr("Wo soll „${t.title}“ stehen?", "Where should “${t.title}” be?"),
        state.libraries.filterNot { it.joined }, t.libraries, onChange = { detail.setLibraries(t, it) }, onDismiss = { moving = false },
    )
    if (covering) detail.coverSheet(t)?.invoke { covering = false }
    episodeMenu?.let { e ->
        EpisodeSheet(t, e, progress[Keys.episode(t, e)], state.offline(t, Keys.episode(t, e)), actions, detail, onDismiss = { episodeMenu = null })
    }

    if (renaming) TextDialog(tr("Umbenennen", "Rename"), t.title, tr("Titel", "Title"), onDismiss = { renaming = false }) {
        renaming = false
        detail.rename(t, it)
    }
    // Steht der Titel in mehreren Bibliotheken und ist gerade eine davon offen: nur dort herausnehmen.
    val onlyHere = state.activeLibrary.takeIf { it != Library.ALL && t.libraries.size > 1 && t.isIn(it) }
    if (confirmRemove && onlyHere != null) {
        val here = state.libraries.firstOrNull { it.id == onlyHere }?.label.orEmpty()
        val others = state.libraries.filter { it.id != onlyHere && t.isIn(it.id) }.joinToString(", ") { it.label }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Palette.surface,
            title = { Text(tr("Aus „$here“ entfernen?", "Remove from “$here”?"), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(tr("In $others bleibt der Titel – mit Fortschritt und Downloads.", "It stays in $others – with progress and downloads."), style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
            confirmButton = { QuietButton(tr("Entfernen", "Remove"), color = Palette.wine) { confirmRemove = false; detail.removeFrom(t, onlyHere) } },
            dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirmRemove = false } },
        )
    } else if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        containerColor = Palette.surface,
        title = { Text(tr("Aus der Bibliothek entfernen?", "Remove from library?"), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                if (t.libraries.size > 1) tr("Der Titel verschwindet aus allen Bibliotheken, der Fortschritt geht verloren. Geladene Dateien bleiben unter Downloads.", "It disappears from all libraries and your progress is lost. Downloaded files stay in Downloads.")
                else tr("Fortschritt geht verloren. Geladene Dateien bleiben unter Downloads.", "Your progress will be lost. Downloaded files stay in Downloads."),
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
        },
        confirmButton = { QuietButton(tr("Entfernen", "Remove"), color = Palette.wine) { confirmRemove = false; detail.remove(t) } },
        dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirmRemove = false } },
    )
}

/** Randloses Bild; oben abgedunkelt für Statusleiste und Knöpfe, unten in den Grund verlaufend. */
@Composable
private fun DetailHero(t: LibTitle, origin: String?) {
    // 16:11 über die volle Breite – auf breiten Bildschirmen (iPad quer) höchstens 62 % der Höhe,
    // sonst füllt das Bild den ganzen Schirm und der Titel liegt unter dem Rand. Handy: unverändert.
    val w = screenWidthDp()
    val h = minOf(w * 11f / 16f, screenHeightDp() * 0.62f)
    Box(Modifier.fillMaxWidth().then(if (h < w * 11f / 16f) Modifier.height(h.dp) else Modifier.aspectRatio(16f / 11f))) {
        // Das angetippte Cover landet hier: dieselbe Ebene, die wandert und wächst (sharedCover).
        // Die Verläufe gehören zum Bild und fliegen mit: daneben lägen sie unter dem fliegenden
        // Cover und erschienen erst bei der Landung – als harter Schatten, der aufploppt.
        Box(Modifier.fillMaxSize().sharedCover(origin, 0.dp)) {
            Thumb(TmdbImages.large(t.wide) ?: t.offline.values.firstOrNull(), Modifier.fillMaxSize(), radius = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.bg)))
        }
    }
}

@Composable
private fun Info(t: LibTitle, state: LibState, actions: LibActions, next: Pair<Episode, Progress?>?, seen: Recommend.Seen, onMatch: (() -> Unit)? = null) {
    val p = state.progress
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text((if (t.kind == TitleKind.SERIES) tr("Serie", "Show") else tr("Film", "Movie")).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
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
        // Liegt, was der Knopf spielt, auf dem Gerät, sagt er es: kein Netz nötig.
        val playKey = if (t.kind == TitleKind.MOVIE) Keys.movie(t) else next?.first?.let { Keys.episode(t, it) }
        val playsOffline = playKey != null && t.offline[playKey] != null
        PrimaryButton(playLabel(t, p) + if (playsOffline) tr(" · offline", " · offline") else "", icon = Ic.play) { actions.play(t, null) }
        val resume = if (t.kind == TitleKind.MOVIE) p[Keys.movie(t)] else next?.second
        if (resume != null && !resume.finished && resume.positionMs > Progress.MIN_RESUME_MS) {
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bar(resume.fraction, height = 4.dp, modifier = Modifier.weight(1f))
                Text(remaining(resume), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp))
            }
        }
        VSpace(14.dp)
        // Immer dieselben vier Plätze in derselben Reihenfolge – man findet sie blind.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionIcon(if (state.inList(t)) Ic.check else Ic.add, tr("Meine Liste", "My List"), active = state.inList(t)) { actions.toggleList(t) }
            ActionIcon(Ic.bookmark, tr("Sammlung", "Collection"), active = state.collectionsWith(t).isNotEmpty()) { actions.collect(t) }
            ActionIcon(Ic.checkCircle, if (seen == Recommend.Seen.DONE) tr("Gesehen", "Watched") else tr("Als gesehen", "Mark watched"), active = seen == Recommend.Seen.DONE) {
                actions.markWatched(t, seen != Recommend.Seen.DONE)
            }
            // Der vierte Platz zeigt den Stand des Films bzw. der nächsten Folge – derselbe, der unten
            // in der Folgenliste steht. Antippen lädt; läuft schon etwas, öffnet es das Blatt dazu.
            val ep = if (t.kind == TitleKind.MOVIE) null else next?.first
            val dl = playKey?.let { state.offline(t, it) }
            val canLoad = dl != null && (dl != de.marne.streamplayer.download.OfflineState.None || (if (t.kind == TitleKind.MOVIE) t.pageUrl != null else ep?.url != null || t.pageUrl != null))
            if (canLoad && dl != null) DownloadAction(
                dl, if (t.kind == TitleKind.MOVIE) tr("Laden", "Download") else tr("Folge laden", "Episode"),
                onDownload = { actions.download(t, ep) }, onMenu = { actions.offlineMenu(t, ep) },
            )
        }

        t.tmdb?.tagline?.let {
            VSpace(18.dp)
            Text(tr("„$it“", "“$it”"), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Serif, fontStyle = FontStyle.Italic), color = Palette.text)
        }
        About(t)
        onMatch?.let { m ->
            VSpace(16.dp)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).clickable(onClick = m).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GIcon(Ic.search, tint = Palette.muted, size = 18.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tr("Nicht bei TMDB gefunden", "Not found on TMDB"), style = MaterialTheme.typography.labelLarge)
                    Text(tr("Ohne Treffer fehlen Cover, Beschreibung und Folgennamen.", "Without a match, cover, description and episode names are missing."), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                Text(tr("Zuordnen", "Match"), style = MaterialTheme.typography.labelLarge, color = Palette.text, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Beschreibung, gekürzt; „Mehr“ zeigt den ganzen Text und erst dann Besetzung und Regie – die
 * Seite bleibt ruhig, wer es wissen will, tippt. „Mehr“ steht auch da, wenn nur Namen fehlen.
 */
@Composable
private fun About(t: LibTitle) {
    val cast = t.tmdb?.cast.orEmpty()
    val creators = t.tmdb?.creators.orEmpty()
    val hasCredits = cast.isNotEmpty() || creators.isNotEmpty()
    var open by remember(t.id) { mutableStateOf(false) }
    var overflow by remember(t.id) { mutableStateOf(false) }
    if (t.text == null && !hasCredits) return
    Column(Modifier.animateContentSize(animationSpec = tween(320, easing = SpringEase))) {
        t.text?.let {
            VSpace(12.dp)
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                onTextLayout = { r -> if (!open) overflow = r.hasVisualOverflow },
                modifier = Modifier.clickable(enabled = overflow || hasCredits || open) { open = !open },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open && hasCredits,
            enter = androidx.compose.animation.fadeIn(tween(220, delayMillis = 120)),
            exit = androidx.compose.animation.fadeOut(tween(140)),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                if (cast.isNotEmpty()) Credit(tr("Mit", "Starring"), cast.take(8).joinToString(", "))
                if (creators.isNotEmpty()) Credit(if (t.kind == TitleKind.SERIES) tr("Idee", "Created by") else tr("Regie", "Director"), creators.joinToString(", "))
            }
        }
        if (overflow || hasCredits || open) {
            VSpace(4.dp)
            Text(
                if (open) tr("Weniger", "Less") else tr("Mehr", "More"),
                style = MaterialTheme.typography.labelLarge, color = Palette.text,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            )
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
                if (real > 0) add(if (real == 1) tr("1 Staffel", "1 season") else tr("$real Staffeln", "$real seasons"))
                if (t.episodeCount > 0) add(if (t.episodeCount == 1) tr("1 Folge", "1 episode") else tr("${t.episodeCount} Folgen", "${t.episodeCount} episodes"))
            } else t.runtimeMs?.let { minutesText(it) }?.let(::add)
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
private fun androidx.compose.foundation.lazy.LazyListScope.similarGrid(similar: List<LibTitle>, state: LibState, actions: LibActions, columns: Int) {
    if (similar.isEmpty()) {
        item(key = "nosim") {
            Text(
                tr(
                    "Noch nichts Ähnliches – dafür braucht es Genres von TMDB und ein paar Titel mehr.",
                    "Nothing similar yet – that takes genres from TMDB and a few more titles.",
                ),
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    // Handy: drei Spalten. Breiter Bildschirm (iPad): so viele, wie mit mindestens 140 dp passen.
    val rows = similar.chunked(columns)
    items(rows.size, key = { "sim$it" }) { i ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows[i].forEach { s -> Box(Modifier.weight(1f)) { TitlePoster(s, state, actions, width = 400.dp, origin = "sim") } }
            repeat(columns - rows[i].size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Folge wie bei Netflix: Standbild mit Play-Zeichen und Fortschritt, daneben Nummer, Titel, Dauer,
 * rechts Laden; darunter der Inhalt in drei Zeilen.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun EpisodeRow(t: LibTitle, e: Episode, p: Progress?, offline: de.marne.streamplayer.download.OfflineState, actions: LibActions, onMenu: () -> Unit) {
    val watched = p?.finished == true
    // Antippen spielt, langes Drücken öffnet die Optionen – kein Drei-Punkte-Menü je Zeile.
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = { actions.play(t, e) }, onLongClick = onMenu)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f)) {
                Thumb(TmdbImages.largeModel(e.still ?: t.wide), Modifier.fillMaxSize(), radius = 12.dp)
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
                    e.title?.let { "${e.number}. $it" } ?: tr("Folge ${e.number}", "Episode ${e.number}"),
                    style = MaterialTheme.typography.titleSmall, color = if (watched) Palette.muted else Palette.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    e.runtimeMin?.let { add(tr("$it Min.", "$it min")) }
                    when {
                        watched -> add(tr("gesehen", "watched"))
                        p != null && p.positionMs > 0 -> add(remaining(p))
                    }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
            }
            DownloadMark(offline, onDownload = { actions.download(t, e) }, onMenu = { actions.offlineMenu(t, e) })
        }
        e.overview?.let {
            VSpace(8.dp)
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** Zurück und Menü schweben über dem Bild; beim Scrollen wird daraus eine ruhige Kopfzeile mit Titel. */
@Composable
private fun DetailTopBar(t: LibTitle, solid: Boolean, actions: LibActions, onEdit: () -> Unit) {
    val alpha by animateFloatAsState(if (solid) 1f else 0f, tween(220, easing = SpringEase), label = "bar")
    Row(
        Modifier.overFlight().fillMaxWidth().background(Palette.bg.copy(alpha = alpha)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(solid) { IconButton(onClick = actions.back) { GIcon(Ic.back) } }
        AnimatedContent(solid, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(200)) }, label = "title", modifier = Modifier.weight(1f)) { s ->
            if (s) Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            else Spacer(Modifier.fillMaxWidth())
        }
        CircleButton(solid) {
            IconButton(onClick = onEdit) { GIcon(Ic.edit) }
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
        confirmButton = { QuietButton(tr("Speichern", "Save"), color = Palette.text) { onConfirm(text) } },
        dismissButton = { QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss) },
    )
}

/** Bearbeiten an einem Ort: Cover, Name, TMDB, Seite, Entfernen – statt eines Drei-Punkte-Menüs. */
@Composable
private fun EditSheet(
    t: LibTitle, detail: DetailActions, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit, onCover: () -> Unit,
    /** Bibliotheken des Titels als Text – null: nur eine Bibliothek vorhanden, keine Zeile. */
    libraryLabel: String? = null, onMove: () -> Unit = {},
    /** Titel aus der Bibliothek eines Freundes: nur ansehen – keine Änderungen, nur die Seite. */
    sharedBy: String? = null,
    onMatch: (() -> Unit)? = null,
) {
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp,
        dragHandle = { de.marne.streamplayer.ui.design.SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(tr("BEARBEITEN", "EDIT"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(18.dp)
            if (sharedBy != null) {
                Text(
                    tr("Geteilt von $sharedBy – nur zum Ansehen. Ändern kann nur $sharedBy.", "Shared by $sharedBy – view only. Only $sharedBy can change it."),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
                t.pageUrl?.let { url ->
                    VSpace(14.dp)
                    de.marne.streamplayer.ui.design.ListGroup {
                        de.marne.streamplayer.ui.design.ListRow(tr("Seite öffnen", "Open page"), icon = Ic.openNew, onClick = { then { detail.openPage(url) } })
                    }
                }
                return@Column
            }
            de.marne.streamplayer.ui.design.ListGroup {
                de.marne.streamplayer.ui.design.ListRow(tr("Cover ändern", "Change cover"), icon = Ic.image, onClick = { then(onCover) })
                if (t.customPoster != null) {
                    de.marne.streamplayer.ui.design.RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("Eigenes Cover entfernen", "Remove custom cover"), icon = Ic.minus, showChevron = false, onClick = { then { detail.resetCover(t) } })
                }
                de.marne.streamplayer.ui.design.RowDivider()
                de.marne.streamplayer.ui.design.ListRow(tr("Umbenennen", "Rename"), icon = Ic.editNote, onClick = { then(onRename) })
                // Nur mit mehreren Bibliotheken: wohin der Titel gehört.
                libraryLabel?.let { label ->
                    de.marne.streamplayer.ui.design.RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("Bibliotheken", "Libraries"), icon = Ic.library, value = label, onClick = { then(onMove) })
                }
                onMatch?.let { m ->
                    de.marne.streamplayer.ui.design.RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(
                        if (t.tmdb == null) tr("Bei TMDB zuordnen", "Match on TMDB") else tr("Anderen TMDB-Eintrag wählen", "Pick another TMDB entry"),
                        icon = Ic.search, value = t.tmdb?.title, onClick = { then(m) },
                    )
                }
                detail.refreshTmdb?.let { r ->
                    de.marne.streamplayer.ui.design.RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("TMDB neu laden", "Reload from TMDB"), icon = Ic.refresh, showChevron = false, onClick = { then { r(t) } })
                }
                t.pageUrl?.let { url ->
                    de.marne.streamplayer.ui.design.RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("Seite öffnen", "Open page"), icon = Ic.openNew, onClick = { then { detail.openPage(url) } })
                }
            }
            VSpace(12.dp)
            de.marne.streamplayer.ui.design.ListGroup {
                de.marne.streamplayer.ui.design.ListRow(tr("Aus der Bibliothek entfernen", "Remove from library"), icon = Ic.delete, titleColor = Palette.wine, showChevron = false, onClick = { then(onRemove) })
            }
        }
    }
}

/** Optionen einer Folge (langes Drücken): abspielen, laden, gesehen, Seite. */
@Composable
private fun EpisodeSheet(t: LibTitle, e: Episode, p: Progress?, offline: de.marne.streamplayer.download.OfflineState, actions: LibActions, detail: DetailActions, onDismiss: () -> Unit) {
    val watched = p?.finished == true
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp,
        dragHandle = { de.marne.streamplayer.ui.design.SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(e.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(e.title ?: tr("Folge ${e.number}", "Episode ${e.number}"), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(18.dp)
            de.marne.streamplayer.ui.design.ListGroup {
                de.marne.streamplayer.ui.design.ListRow(tr("Abspielen", "Play"), icon = Ic.play, showChevron = false, onClick = { then { actions.play(t, e) } })
                de.marne.streamplayer.ui.design.RowDivider()
                if (offline == de.marne.streamplayer.download.OfflineState.None) {
                    de.marne.streamplayer.ui.design.ListRow(tr("Laden", "Download"), icon = Ic.download, showChevron = false, onClick = { then { actions.download(t, e) } })
                } else {
                    // Mit Stand in der zweiten Zeile; das Blatt dahinter hat Anhalten, Löschen usw.
                    de.marne.streamplayer.ui.design.ListRow(
                        "Download", icon = Ic.download, subtitle = statusLine(offline),
                        onClick = { then { actions.offlineMenu(t, e) } },
                    )
                }
                de.marne.streamplayer.ui.design.RowDivider()
                de.marne.streamplayer.ui.design.ListRow(
                    if (watched) tr("Als ungesehen markieren", "Mark as unwatched") else tr("Als gesehen markieren", "Mark as watched"),
                    icon = Ic.checkCircle, showChevron = false, onClick = { then { actions.setWatched(Keys.episode(t, e), !watched) } },
                )
                de.marne.streamplayer.ui.design.RowDivider()
                de.marne.streamplayer.ui.design.ListRow(tr("Seite öffnen", "Open page"), icon = Ic.openNew, onClick = { then { detail.openPage(e.url) } })
            }
        }
    }
}
