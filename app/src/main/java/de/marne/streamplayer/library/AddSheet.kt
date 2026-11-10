package de.marne.streamplayer.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace

class LinkActions(
    val dismiss: () -> Unit,
    val watchNow: (url: String) -> Unit,
    val openInBrowser: (url: String) -> Unit,
)

/**
 * Ein geteilter Link. In die Mediathek kommen Titel über Kataloge (Creator-App), hier gibt es
 * deshalb nur: gleich ansehen oder im Browser öffnen.
 */
@Composable
fun LinkSheet(url: String, actions: LinkActions) {
    ModalBottomSheet(
        onDismissRequest = actions.dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text("GETEILTER LINK", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text("Gleich ansehen?", style = MaterialTheme.typography.headlineSmall)
            VSpace(6.dp)
            Text(url, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(24.dp)
            PrimaryButton("Ansehen", icon = Ic.play) { actions.watchNow(url) }
            QuietButton("Im Browser öffnen", color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally)) { actions.openInBrowser(url) }
            VSpace(4.dp)
            Text(
                "Dauerhaft in die Bibliothek kommen Filme und Serien über Kataloge aus dem Streamplayer Creator.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
        }
    }
}
