package de.marne.streamplayer.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.backup.Backup
import de.marne.streamplayer.backup.Backup.Part
import de.marne.streamplayer.library.CheckDot
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * „Sicherung“ in den Einstellungen: Export (Teile wählen → Datei speichern) und Import (Datei
 * wählen → Teile und Art wählen). Die Datei geht über den Dateiauswahl-Dialog von Android, damit
 * sie landet, wo man will – Downloads, Drive, ein anderes Gerät.
 */
@Composable
fun BackupRows(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Set<Part>?>(null) }
    var importing by remember { mutableStateOf<Backup.Contents?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val parts = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { Backup.write(parts, it) }
                }.isSuccess
            }
            onToast(if (ok) tr("Gesichert.", "Saved.") else tr("Konnte die Datei nicht schreiben.", "Couldn't write the file."))
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()?.let(Backup::read)
            }
            if (c == null) onToast(tr("Das ist keine Sicherung von Streamplayer.", "That isn't a Streamplayer backup."))
            else importing = c
        }
    }

    ListGroup {
        ListRow(
            title = tr("Exportieren", "Export"),
            subtitle = tr("Einrichtung als Datei sichern – du wählst, was hinein soll.", "Save your setup as a file – you choose what goes in."),
            icon = Ic.share,
            onClick = { exporting = true },
        )
        RowDivider()
        ListRow(
            title = tr("Importieren", "Import"),
            subtitle = tr("Aus einer Sicherung übernehmen: zusammenführen oder ersetzen.", "Restore from a backup: merge or replace."),
            icon = Ic.importFile,
            onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        )
    }

    if (exporting) ExportSheet(onDismiss = { exporting = false }) { parts ->
        exporting = false
        pendingExport = parts
        save.launch(Backup.fileName())
    }
    importing?.let { c ->
        ImportSheet(c, onDismiss = { importing = null }) { parts, replace ->
            importing = null
            scope.launch {
                val ok = runCatching { Backup.apply(c, parts, replace) }.isSuccess
                onToast(if (ok) tr("Übernommen.", "Imported.") else tr("Import fehlgeschlagen.", "Import failed."))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(onDismiss: () -> Unit, onExport: (Set<Part>) -> Unit) {
    var parts by remember { mutableStateOf(Part.defaults) }
    BackupSheet(onDismiss) {
        item {
            Text(tr("EXPORT", "EXPORT"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(tr("Was soll in die Datei?", "What goes into the file?"), style = MaterialTheme.typography.headlineSmall)
            VSpace(18.dp)
        }
        item { PartList(Part.entries, parts) { p -> parts = if (p in parts) parts - p else parts + p } }
        item {
            VSpace(20.dp)
            PrimaryButton(tr("Datei speichern", "Save file"), enabled = parts.isNotEmpty(), icon = Ic.share, modifier = Modifier.fillMaxWidth()) { onExport(parts) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(c: Backup.Contents, onDismiss: () -> Unit, onImport: (Set<Part>, Boolean) -> Unit) {
    val available = Part.entries.filter { it in c.parts }
    var parts by remember { mutableStateOf(available.toSet()) }
    var mode by remember { mutableStateOf("merge") }
    BackupSheet(onDismiss) {
        item {
            Text(tr("IMPORT", "IMPORT"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(tr("Was übernehmen?", "What should come in?"), style = MaterialTheme.typography.headlineSmall)
            if (c.created > 0) {
                VSpace(4.dp)
                val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, de.marne.streamplayer.ui.Lang.locale)
                    .format(java.util.Date(c.created))
                Text(tr("Sicherung vom $date", "Backup from $date"), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
            VSpace(18.dp)
        }
        item { PartList(available, parts, counts = c::count) { p -> parts = if (p in parts) parts - p else parts + p } }
        item {
            VSpace(20.dp)
            Segmented(
                options = listOf("merge" to tr("Zusammenführen", "Merge"), "replace" to tr("Ersetzen", "Replace")),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(10.dp)
            Text(
                if (mode == "merge") tr(
                    "Was hier schon ist, bleibt. Neues kommt dazu; beim Verlauf gilt je Folge der neuere Stand.",
                    "What's already here stays. New things are added; for history, the newer state wins per episode.",
                ) else tr(
                    "Die gewählten Teile sind danach genau wie in der Datei. Was hier anders war, ist weg – Downloads bleiben.",
                    "The chosen parts will match the file exactly. Anything different here is gone – downloads stay.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (mode == "merge") Palette.muted else Palette.wine,
            )
            VSpace(20.dp)
            PrimaryButton(
                if (mode == "merge") tr("Zusammenführen", "Merge") else tr("Ersetzen", "Replace"),
                enabled = parts.isNotEmpty(), icon = Ic.importFile, modifier = Modifier.fillMaxWidth(),
            ) { onImport(parts, mode == "replace") }
        }
    }
}

@Composable
private fun PartList(list: List<Part>, selected: Set<Part>, counts: (Part) -> Int? = { null }, onToggle: (Part) -> Unit) {
    ListGroup {
        list.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            val n = counts(p)
            ListRow(
                title = if (n != null) "${p.label} · $n" else p.label,
                subtitle = p.hint,
                showChevron = false,
                onClick = { onToggle(p) },
                trailing = { CheckDot(p in selected) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(onDismiss: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.heightIn(max = 720.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), content = content)
    }
}
