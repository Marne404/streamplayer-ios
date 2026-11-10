package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.ExpandableText
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

class DetailActions(
    val back: () -> Unit,
    val play: (LibTitle, Episode?) -> Unit,
    val download: (LibTitle, Episode?) -> Unit,
    val openPage: (String) -> Unit,
    val setWatched: (String, Boolean) -> Unit,
    val rename: (LibTitle, String) -> Unit,
    val editSeries: (LibTitle, String, List<Int>) -> Unit,
    val remove: (LibTitle) -> Unit,
)

@Composable
fun DetailScreen(t: LibTitle, progress: Map<String, Progress>, actions: DetailActions) {
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val next = remember(t, progress) { Shelves.nextFor(t, progress) }
    var season by rememberSaveable(t.id) { mutableIntStateOf(next?.first?.season ?: 1) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { GIcon(Ic.back) }
                Box(Modifier.weight(1f))
                RowMenu(buildList {
                    add(MenuAction("Umbenennen") { renaming = true })
                    if (t.kind == TitleKind.SERIES) add(MenuAction("Folgen bearbeiten") { editing = true })
                    t.pageUrl?.let { url -> add(MenuAction("Seite öffnen") { actions.openPage(url) }) }
                    add(MenuAction("Entfernen", destructive = true) { confirmRemove = true })
                })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Thumb(t.image ?: t.offline.values.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 24.dp)
                VSpace(24.dp)
                Text((if (t.kind == TitleKind.SERIES) "Serie" else "Film").uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(t.title, style = MaterialTheme.typography.headlineLarge)
                VSpace(6.dp)
                Text(titleMeta(t), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                t.description?.let {
                    VSpace(14.dp)
                    ExpandableText(it, collapsedLines = 3, style = MaterialTheme.typography.bodyMedium)
                }
                VSpace(28.dp)
                if (t.kind == TitleKind.MOVIE) MovieButtons(t, progress[Keys.movie(t)], actions)
                else SeriesButton(t, next, actions)
            }
        }

        if (t.kind == TitleKind.SERIES) {
            val eps = t.episodes()
            item { SectionTitle("Folgen", modifier = Modifier.padding(horizontal = 20.dp)) }
            if (t.seasons.size > 1) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(t.seasons.size) { i ->
                            OutlinePill("Staffel ${i + 1}", selected = season == i + 1, onClick = { season = i + 1 })
                        }
                    }
                    VSpace(14.dp)
                }
            }
            item {
                ListGroup(Modifier.padding(horizontal = 20.dp)) {
                    eps.filter { it.season == season }.forEachIndexed { i, e ->
                        if (i > 0) RowDivider(inset = 64.dp)
                        EpisodeRow(t, e, progress[Keys.episode(t, e)], t.offline[Keys.episode(t, e)] != null, actions)
                    }
                }
            }
        }
    }

    if (renaming) TextDialog("Umbenennen", t.title, "Titel", onDismiss = { renaming = false }) {
        renaming = false
        actions.rename(t, it)
    }
    if (editing) EditSeriesDialog(t, onDismiss = { editing = false }) { tpl, seasons ->
        editing = false
        actions.editSeries(t, tpl, seasons)
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        containerColor = Palette.surface,
        title = { Text("Aus der Bibliothek entfernen?", style = MaterialTheme.typography.headlineSmall) },
        text = { Text("Fortschritt geht verloren. Geladene Dateien bleiben unter Downloads.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
        confirmButton = { QuietButton("Entfernen", color = Palette.wine) { confirmRemove = false; actions.remove(t) } },
        dismissButton = { QuietButton("Behalten", color = Palette.muted) { confirmRemove = false } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MovieButtons(t: LibTitle, p: Progress?, actions: DetailActions) {
    val resume = p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS
    val offline = t.offline[Keys.movie(t)] != null
    val label = when {
        resume -> "Fortsetzen · ${remaining(p!!)}"
        p?.finished == true -> "Nochmal ansehen"
        else -> "Abspielen"
    }
    PrimaryButton(label, icon = Ic.play) { actions.play(t, null) }
    if (resume) {
        VSpace(12.dp)
        Bar(p!!.fraction, height = 4.dp)
    }
    if (!offline && t.pageUrl != null) {
        VSpace(12.dp)
        SecondaryButton("Laden", icon = Ic.download) { actions.download(t, null) }
    } else if (offline) {
        VSpace(12.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            GIcon(Ic.check, tint = Palette.green, size = 16.dp)
            HSpace(6.dp)
            Text("Offline verfügbar", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SeriesButton(t: LibTitle, next: Pair<Episode, Progress?>?, actions: DetailActions) {
    if (next == null) {
        PrimaryButton("Von vorn", icon = Ic.play) { actions.play(t, t.episodes().firstOrNull()) }
        VSpace(8.dp)
        Text("Alles gesehen.", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        return
    }
    val (e, p) = next
    val resume = p != null && !p.finished && p.positionMs > ProgressStore.MIN_RESUME_MS
    PrimaryButton(if (resume) "Weiterschauen · ${e.label}" else "Abspielen · ${e.label}", icon = Ic.play) { actions.play(t, e) }
    if (resume) {
        VSpace(8.dp)
        Text(remaining(p!!), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun EpisodeRow(t: LibTitle, e: Episode, p: Progress?, offline: Boolean, actions: DetailActions) {
    val key = Keys.episode(t, e)
    val watched = p?.finished == true
    Row(
        Modifier.fillMaxWidth().clickable { actions.play(t, e) }.padding(start = 18.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Nummer im Kreis; gesehen = gefülltes ink-Häkchen (wie „erledigt“ im Design-System).
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (watched) Palette.ink else Palette.surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (watched) GIcon(Ic.check, tint = Palette.onInk, size = 16.dp)
            else Text("${e.number}", style = MaterialTheme.typography.labelLarge)
        }
        HSpace(16.dp)
        Column(Modifier.weight(1f)) {
            Text("Folge ${e.number}", style = MaterialTheme.typography.bodyLarge, color = if (watched) Palette.muted else Palette.text)
            val meta = buildList {
                if (offline) add("offline")
                when {
                    watched -> add("gesehen")
                    p != null && p.positionMs > 0 -> add(remaining(p))
                }
            }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null && !watched && p.positionMs > 0) {
                VSpace(6.dp)
                Bar(p.fraction, height = 3.dp)
            }
        }
        RowMenu(buildList {
            add(MenuAction("Abspielen") { actions.play(t, e) })
            if (!offline) add(MenuAction("Laden") { actions.download(t, e) })
            add(MenuAction(if (watched) "Als ungesehen markieren" else "Als gesehen markieren") { actions.setWatched(key, !watched) })
            add(MenuAction("Seite öffnen") { actions.openPage(e.url) })
        })
    }
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

@Composable
private fun EditSeriesDialog(t: LibTitle, onDismiss: () -> Unit, onSave: (String, List<Int>) -> Unit) {
    var tpl by remember { mutableStateOf(t.template.orEmpty()) }
    var seasons by remember { mutableStateOf(t.seasons.joinToString(", ")) }
    val parsed = Pattern.parseSeasons(seasons)
    val valid = (tpl.contains("{e}") || tpl.contains("{ee}")) && parsed.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text("Folgen bearbeiten", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                IvoryField(tpl, { tpl = it }, "https://…/staffel-{s}/episode-{e}", KeyboardType.Uri, label = "Muster")
                VSpace(12.dp)
                IvoryField(seasons, { seasons = it }, "12, 8, 10", KeyboardType.Text, label = "Folgen je Staffel")
                VSpace(8.dp)
                Text("Neue Staffel: Zahl anhängen. Fortschritt bleibt erhalten.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
        },
        confirmButton = { QuietButton("Speichern", color = if (valid) Palette.text else Palette.faint) { if (valid) onSave(tpl.trim(), parsed) } },
        dismissButton = { QuietButton("Abbrechen", color = Palette.muted, onClick = onDismiss) },
    )
}
