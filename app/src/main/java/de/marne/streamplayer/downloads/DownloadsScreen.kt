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
import de.marne.streamplayer.ui.tr

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
fun DownloadsScreen(items: List<DownloadItem>, actions: DownloadActions, onBack: (() -> Unit)? = null) {
    val active = items.filter { it.active }
    val stopped = items.filter { it.state == DlState.PAUSED || it.state == DlState.FAILED }
    val done = items.filter { it.state == DlState.DONE }.sortedByDescending { it.finishedAt ?: it.createdAt }
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }

    val title = when {
        items.isEmpty() -> tr("Noch nichts geladen.", "Nothing downloaded yet.")
        active.size == 1 -> tr("Ein Download läuft.", "One download in progress.")
        active.size > 1 -> tr("${active.size} Downloads laufen.", "${active.size} downloads in progress.")
        stopped.isNotEmpty() -> tr("Da fehlt noch etwas.", "Something's still missing.")
        else -> tr("Alles geladen.", "All downloaded.")
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + if (onBack != null) 4.dp else 16.dp, bottom = 40.dp),
    ) {
        // Seit 1.7.0 eine Seite unter „Meins“, kein eigener Tab mehr: dann mit Zurück.
        if (onBack != null) item { androidx.compose.material3.IconButton(onClick = onBack) { de.marne.streamplayer.ui.design.GIcon(Ic.back) } }
        item {
            val subtitle = if (done.isNotEmpty()) tr("${done.size} in Filme/Streamplayer", "${done.size} in Movies/Streamplayer") else null
            ScreenHeader(title = title, eyebrow = "Downloads", subtitle = subtitle)
        }
        if (items.isEmpty()) {
            item {
                VSpace(32.dp)
                NoticeCard(
                    icon = Ic.browser,
                    title = tr("So geht es", "How it works"),
                    text = tr("Seite öffnen, Video starten, auf „gefunden“ tippen.", "Open a page, start the video, tap “found”."),
                    action = "Browser",
                    onAction = actions.goBrowse,
                )
            }
        }
        if (active.isNotEmpty()) {
            item { SectionTitle(tr("Läuft", "In progress")) }
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
            item { SectionTitle(tr("Angehalten", "Paused")) }
            item {
                ListGroup {
                    stopped.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(inset = 96.dp)
                        DownloadRow(d, onClick = { actions.resume(d) }) {
                            RowMenu(
                                listOf(
                                    MenuAction(if (d.state == DlState.FAILED) tr("Erneut versuchen", "Try again") else tr("Fortsetzen", "Resume")) { actions.resume(d) },
                                    MenuAction(tr("Seite öffnen", "Open page")) { actions.openPage(d) },
                                    MenuAction(tr("Abbrechen", "Cancel"), destructive = true) { confirm = d },
                                )
                            )
                        }
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item { SectionTitle(tr("Fertig", "Done")) }
            item {
                ListGroup {
                    done.forEachIndexed { i, d ->
                        if (i > 0) RowDivider(inset = 96.dp)
                        DownloadRow(d, onClick = { actions.play(d) }) {
                            RowMenu(
                                listOf(
                                    MenuAction(tr("Abspielen", "Play")) { actions.play(d) },
                                    MenuAction(tr("Teilen", "Share")) { actions.share(d) },
                                    MenuAction(tr("Öffnen mit …", "Open with …")) { actions.openWith(d) },
                                    MenuAction(tr("In Bibliothek", "Add to library")) { actions.saveToLibrary(d) },
                                    MenuAction(tr("Seite öffnen", "Open page")) { actions.openPage(d) },
                                    MenuAction(tr("Löschen", "Delete"), destructive = true) { confirm = d },
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
            title = { Text(if (isDone) tr("Video löschen?", "Delete video?") else tr("Download abbrechen?", "Cancel download?"), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    if (isDone) tr("Die Datei verschwindet auch aus der Mediathek.", "The file will also be removed from your media library.") else tr("Was schon geladen ist, wird verworfen.", "Anything already downloaded will be discarded."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            },
            confirmButton = {
                QuietButton(if (isDone) tr("Löschen", "Delete") else tr("Abbrechen", "Cancel"), color = Palette.wine) {
                    confirm = null
                    actions.remove(d)
                }
            },
            dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirm = null } },
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
