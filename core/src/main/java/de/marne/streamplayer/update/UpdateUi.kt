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
import de.marne.streamplayer.ui.tr
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
        title = { Text(tr("$appName ${ready.version} ist da.", "$appName ${ready.version} is here.")) },
        text = {
            Text(
                tr(
                    "Schon geladen. Tippe auf Installieren und bestätige. Beim ersten Mal fragt Android, ob " +
                        "$appName Apps installieren darf – erlauben, zurückkommen, noch einmal tippen. " +
                        "Deine Daten bleiben.",
                    "Already downloaded. Tap Install and confirm. The first time, Android asks whether " +
                        "$appName may install apps – allow it, come back, and tap again. " +
                        "Your data stays.",
                ),
                color = Palette.muted,
            )
        },
        confirmButton = { QuietButton(tr("Installieren", "Install")) { Updater.install(context) } },
        dismissButton = { QuietButton(tr("Später", "Later"), color = Palette.muted, onClick = Updater::later) },
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
    var note by remember { mutableStateOf<Updater.CheckResult?>(null) }
    val scope = rememberCoroutineScope()
    val version = remember { Updater.versionName(context) }
    ListRow(
        title = tr("Nach Updates suchen", "Check for updates"),
        subtitle = when (val u = update) {
            is UpdateState.Downloading -> {
                val percent = (u.progress * 100).toInt().coerceIn(0, 100)
                tr("Lädt ${u.version} … $percent %", "Downloading ${u.version} … $percent%")
            }
            is UpdateState.Ready -> tr("${u.version} ist bereit – tippen zum Installieren", "${u.version} is ready – tap to install")
            UpdateState.None -> when (note) {
                Updater.CheckResult.UpToDate -> tr("Version $version ist die neueste.", "Version $version is the latest.")
                Updater.CheckResult.Failed -> tr("Keine Verbindung zum Update-Server.", "Can’t reach the update server.")
                else -> if (Updater.enabled) tr("Version $version · prüft bei jedem Start", "Version $version · checks on every launch")
                else tr("Version $version · Testversion, ohne Updates", "Version $version · test build, no updates")
            }
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
                note = result
                checking = false
            }
        },
    )
}
