package de.marne.streamplayer.update

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fertig geladenes Update anbieten – erst wenn alles geprüft auf dem Gerät liegt, nie mitten im
 * Laden. [appName] steht im Titel („Streamplayer 1.7.0 ist da.“).
 */
@Composable
fun UpdateOffer(appName: String) {
    val offer by Updater.offer.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    val ready = state as? UpdateState.Ready ?: return
    if (!offer) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = Updater::later,
        title = { Text("$appName ${ready.version} ist da.") },
        text = {
            Text(
                "Schon geladen. Tippe auf Installieren und bestätige. Beim ersten Mal fragt Android, ob " +
                    "$appName Apps installieren darf – erlauben, zurückkommen, noch einmal tippen. " +
                    "Deine Daten bleiben.",
                color = Palette.muted,
            )
        },
        confirmButton = { QuietButton("Installieren") { Updater.install(context) } },
        dismissButton = { QuietButton("Später", color = Palette.muted, onClick = Updater::later) },
        containerColor = Palette.bg,
        shape = RoundedCornerShape(28.dp),
    )
}

/** Zeile „Nach Updates suchen“ für die Einstellungen beider Apps; zeigt Version und Ladefortschritt. */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val update by Updater.state.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val version = remember { Updater.versionName(context) }
    ListRow(
        title = "Nach Updates suchen",
        subtitle = when (val u = update) {
            is UpdateState.Downloading -> "Lädt ${u.version} … ${(u.progress * 100).toInt().coerceIn(0, 100)} %"
            is UpdateState.Ready -> "${u.version} ist bereit – tippen zum Installieren"
            UpdateState.None -> note ?: if (Updater.enabled) "Version $version · prüft bei jedem Start" else "Version $version · Testversion, ohne Updates"
        },
        icon = Ic.download,
        showChevron = false,
        trailing = if (checking) ({ CircularProgressIndicator(Modifier.size(22.dp), color = Palette.text, strokeWidth = 2.dp) }) else null,
        onClick = {
            if (update is UpdateState.Ready) Updater.install(context)
            else if (!checking) scope.launch {
                checking = true
                // Immer kurz „prüfen“ – auch wenn die letzte Antwort noch gilt und gar keine Anfrage rausgeht.
                val started = System.currentTimeMillis()
                val result = Updater.checkNow(context)
                delay((700 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                note = when (result) {
                    Updater.CheckResult.UpToDate -> "Version $version ist die neueste."
                    Updater.CheckResult.Found -> null
                    Updater.CheckResult.Failed -> "Keine Verbindung zum Update-Server."
                }
                checking = false
            }
        },
    )
}
