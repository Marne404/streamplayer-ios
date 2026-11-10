package de.marne.streamplayer.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.DownloadItem
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

class DownloadActions(
    val play: (DownloadItem) -> Unit,
    val pause: (DownloadItem) -> Unit,
    val resume: (DownloadItem) -> Unit,
    val remove: (DownloadItem) -> Unit,
    val share: (DownloadItem) -> Unit,
    val openWith: (DownloadItem) -> Unit,
    val openPage: (DownloadItem) -> Unit,
    val saveToLibrary: (DownloadItem) -> Unit,
    val goBrowse: () -> Unit,
)

@Composable
fun DownloadsScreen(items: List<DownloadItem>, actions: DownloadActions) {
    val active = items.filter { it.active }
    val stopped = items.filter { it.state == DlState.PAUSED || it.state == DlState.FAILED }
    val done = items.filter { it.state == DlState.DONE }.sortedByDescending { it.finishedAt ?: it.createdAt }
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }

    val title = when {
        items.isEmpty() -> "Noch nichts geladen."
        active.size == 1 -> "Ein Download läuft."
        active.size > 1 -> "${active.size} Downloads laufen."
        stopped.isNotEmpty() -> "Da fehlt noch etwas."
        else -> "Alles geladen."
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 40.dp),
    ) {
        item {
            val subtitle = if (done.isNotEmpty()) "${done.size} in Filme/Streamplayer" else null
            ScreenHeader(title = title, eyebrow = "Downloads", subtitle = subtitle)
        }
        if (items.isEmpty()) {
            item {
                VSpace(32.dp)
                NoticeCard(
                    icon = Ic.browser,
                    title = "So geht es",
                    text = "Seite öffnen, Video starten, auf „gefunden“ tippen.",
                    action = "Browser",
                    onAction = actions.goBrowse,
                )
            }
        }
        if (active.isNotEmpty()) {
            item { SectionTitle("Läuft") }
            item {
                ListGroup {
                    active.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(inset = 96.dp)
                        DownloadRow(d, onClick = null) {
                            IconButton(onClick = { actions.pause(d) }) { GIcon(Ic.pause, tint = Palette.text, size = 20.dp) }
                        }
                    }
                }
            }
        }
        if (stopped.isNotEmpty()) {
            item { SectionTitle("Angehalten") }
            item {
                ListGroup {
                    stopped.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(inset = 96.dp)
                        DownloadRow(d, onClick = { actions.resume(d) }) {
                            RowMenu(
                                listOf(
                                    MenuAction(if (d.state == DlState.FAILED) "Erneut versuchen" else "Fortsetzen") { actions.resume(d) },
                                    MenuAction("Seite öffnen") { actions.openPage(d) },
                                    MenuAction("Abbrechen", destructive = true) { confirm = d },
                                )
                            )
                        }
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item { SectionTitle("Fertig") }
            item {
                ListGroup {
                    done.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(inset = 96.dp)
                        DownloadRow(d, onClick = { actions.play(d) }) {
                            RowMenu(
                                listOf(
                                    MenuAction("Abspielen") { actions.play(d) },
                                    MenuAction("Teilen") { actions.share(d) },
                                    MenuAction("Öffnen mit …") { actions.openWith(d) },
                                    MenuAction("In Bibliothek") { actions.saveToLibrary(d) },
                                    MenuAction("Seite öffnen") { actions.openPage(d) },
                                    MenuAction("Löschen", destructive = true) { confirm = d },
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { d ->
        val isDone = d.state == DlState.DONE
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Palette.surface,
            title = { Text(if (isDone) "Video löschen?" else "Download abbrechen?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    if (isDone) "Die Datei verschwindet auch aus der Mediathek." else "Was schon geladen ist, wird verworfen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            },
            confirmButton = {
                QuietButton(if (isDone) "Löschen" else "Abbrechen", color = Palette.wine) {
                    confirm = null
                    actions.remove(d)
                }
            },
            dismissButton = { QuietButton("Behalten", color = Palette.muted) { confirm = null } },
        )
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val failed = d.state == DlState.FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(d.thumbnail ?: d.fileUri, Modifier.size(width = 64.dp, height = 40.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) {
                    GIcon(Ic.error, tint = Palette.wine, size = 14.dp)
                    HSpace(4.dp)
                }
                Text(
                    Format.metaLine(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Palette.wine else Palette.muted,
                    maxLines = if (failed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (d.state != DlState.DONE && d.state != DlState.FAILED) {
                VSpace(8.dp)
                Bar(d.progress, height = 4.dp)
            }
        }
        HSpace(4.dp)
        trailing()
    }
}
