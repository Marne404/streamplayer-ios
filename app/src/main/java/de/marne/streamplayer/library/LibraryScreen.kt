package de.marne.streamplayer.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.browser.BrowserController
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IconBadge
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

class LibraryActions(
    val watch: (LibraryItem) -> Unit,
    val play: (LibraryItem) -> Unit,
    val openPage: (LibraryItem) -> Unit,
    val addLink: (url: String) -> Unit,
    val rename: (LibraryItem, String) -> Unit,
    val remove: (LibraryItem) -> Unit,
    val goBrowse: () -> Unit,
)

@Composable
fun LibraryScreen(items: List<LibraryItem>, actions: LibraryActions) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val links = items.filter { it.kind == LibraryKind.LINK }
    val files = items.filter { it.kind == LibraryKind.FILE }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                title = when (items.size) {
                    0 -> "Noch leer."
                    1 -> "Ein Video gemerkt."
                    else -> "${items.size} Videos gemerkt."
                },
                eyebrow = "Bibliothek",
                subtitle = if (items.isEmpty()) null else "Tippen spielt ab, lange drücken für mehr.",
                trailing = {
                    IconButton(onClick = { adding = true }) { IconBadge(Ic.add) }
                },
            )
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    VSpace(12.dp)
                    NoticeCard(
                        icon = Ic.bookmark,
                        title = "So kommt etwas hierher",
                        text = "Lesezeichen in der Adressleiste, „Merken“ im Fund oder Link mit + einfügen.",
                        action = "Browser",
                        onAction = actions.goBrowse,
                    )
                }
            }
        }
        if (links.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Links", modifier = Modifier.padding(bottom = 0.dp)) }
            items(links, key = { it.id }) { item ->
                Tile(item, onOpen = { actions.watch(item) }, menu = listOf(
                    "Ansehen" to { actions.watch(item) },
                    "Seite öffnen" to { actions.openPage(item) },
                    "Umbenennen" to { renaming = item },
                    "Entfernen" to { actions.remove(item) },
                ))
            }
        }
        if (files.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Geladen") }
            items(files, key = { it.id }) { item ->
                Tile(item, onOpen = { actions.play(item) }, menu = buildList {
                    add("Abspielen" to { actions.play(item) })
                    if (item.pageUrl != null) add("Seite öffnen" to { actions.openPage(item) })
                    add("Umbenennen" to { renaming = item })
                    add("Entfernen" to { actions.remove(item) })
                })
            }
        }
    }

    if (adding) AddLinkDialog(onDismiss = { adding = false }, onAdd = { adding = false; actions.addLink(it) })
    renaming?.let { item ->
        RenameDialog(item.title, onDismiss = { renaming = null }, onRename = { renaming = null; actions.rename(item, it) })
    }
}

/** Kachel: Bild 16:9 in radius-group, darunter Titel und Metazeile – ohne eigene Fläche, ruhig. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: LibraryItem, onOpen: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { open = true })
        ) {
            Box {
                Thumb(item.thumbnail ?: item.url.takeIf { item.kind == LibraryKind.FILE }, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 22.dp)
                // Was ein Tipp tut, steht auf dem Bild: abspielen (Datei) oder ansehen (Link).
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .clip(RoundedCornerShape(50)).background(Palette.bg.copy(alpha = 0.88f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(if (item.kind == LibraryKind.FILE) Ic.play else Ic.browser, tint = Palette.text, size = 13.dp)
                        HSpace(4.dp)
                        Text(
                            if (item.kind == LibraryKind.FILE) item.durationMs?.let(Format::duration) ?: "Video" else "Link",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            VSpace(8.dp)
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
            Text(
                Format.host(item.pageUrl ?: item.url),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Palette.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, Palette.line),
        ) {
            menu.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = if (label == "Entfernen") Palette.wine else Palette.text) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    val valid = BrowserController.looksLikeUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text("Link merken", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                IvoryField(text, { text = it }, "https://…", KeyboardType.Uri)
                QuietButton("Aus der Zwischenablage", color = Palette.text) {
                    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    Regex("""https?://\S+""").find(clip)?.value?.let { text = it }
                }
            }
        },
        confirmButton = { QuietButton("Merken", color = if (valid) Palette.text else Palette.faint) { if (valid) onAdd(BrowserController.toUrl(text)!!) } },
        dismissButton = { QuietButton("Abbrechen", color = Palette.muted, onClick = onDismiss) },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text("Umbenennen", style = MaterialTheme.typography.headlineSmall) },
        text = { IvoryField(text, { text = it }, "Titel", KeyboardType.Text) },
        confirmButton = { QuietButton("Speichern", color = Palette.text) { onRename(text) } },
        dismissButton = { QuietButton("Abbrechen", color = Palette.muted, onClick = onDismiss) },
    )
}

/** Textfeld in Ivory & Ink: radius-field, Haarlinie, Fokus in text. */
@Composable
private fun IvoryField(value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = Palette.muted) },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.text,
            unfocusedBorderColor = Palette.line,
            cursorColor = Palette.text,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text,
            focusedContainerColor = Palette.bg,
            unfocusedContainerColor = Palette.bg,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
