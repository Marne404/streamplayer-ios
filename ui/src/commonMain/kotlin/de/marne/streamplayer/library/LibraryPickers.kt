package de.marne.streamplayer.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.account.ProfileColors
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.launch

// Auswahl-Blätter für Bibliotheken (aus LibrariesSheet.kt) – gemeinsam für Android und iPad.

/** Kleiner Farbpunkt einer Bibliothek – „Alle“ hat einen Ring statt einer Farbe. */
@Composable
fun LibraryDot(color: Int?, size: androidx.compose.ui.unit.Dp = 12.dp) {
    if (color == null) Box(Modifier.size(size).clip(CircleShape).border(1.5.dp, Palette.muted, CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(ProfileColors[color.mod(ProfileColors.size)]))
}

/** Eine Bibliothek wählen: Ziel eines Katalog-Imports oder wohin ein Titel umzieht. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickSheet(eyebrow: String, title: String, libraries: List<Library>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(18.dp)
            ListGroup {
                libraries.forEachIndexed { i, l ->
                    if (i > 0) RowDivider(inset = 50.dp)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); onPick(l.id) }
                        }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryDot(l.color, 14.dp)
                        Text(l.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 18.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.id == selected) GIcon(Ic.check, tint = Palette.text, size = 20.dp)
                    }
                }
            }
        }
    }
}

/**
 * Mehrfachwahl: in welchen Bibliotheken ein Titel steht. Jeder Tipp gilt sofort; die letzte
 * Bibliothek lässt sich nicht abwählen – ein Titel ohne Bibliothek wäre nirgends mehr zu sehen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryToggleSheet(eyebrow: String, title: String, libraries: List<Library>, selected: List<String>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(4.dp)
            Text(
                tr("Derselbe Titel in mehreren Bibliotheken – Fortschritt und Downloads gelten überall.", "The same title in several libraries – progress and downloads count everywhere."),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
            VSpace(18.dp)
            ListGroup {
                libraries.forEachIndexed { i, l ->
                    if (i > 0) RowDivider(inset = 50.dp)
                    val on = l.id in chosen
                    val last = on && chosen.size == 1
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !last) {
                            chosen = if (on) chosen - l.id else chosen + l.id
                            onChange(chosen)
                        }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryDot(l.color, 14.dp)
                        Text(l.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 18.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (on) GIcon(Ic.check, tint = if (last) Palette.muted else Palette.text, size = 20.dp)
                    }
                }
            }
        }
    }
}
