package de.marne.streamplayer.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.browser.BrowserController
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace

class AddActions(
    val dismiss: () -> Unit,
    val addMovie: (url: String) -> Unit,
    val addSeries: (title: String, template: String, seasons: List<Int>) -> Unit,
    val watchNow: (url: String) -> Unit,
    val openInBrowser: (url: String) -> Unit,
)

/**
 * Hinzufügen: Film = ein Link. Serie = Link zu einer Folge, daraus schlägt die App das Muster vor;
 * dazu die Folgen je Staffel. Die Vorschau zeigt erste und letzte Adresse, damit ein falsches
 * Muster sofort auffällt statt erst beim Abspielen.
 */
@Composable
fun AddSheet(initialUrl: String, actions: AddActions) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by rememberSaveable { mutableStateOf("movie") }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf("") }
    var templateTouched by rememberSaveable { mutableStateOf(false) }
    var seasonsText by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)

    val validUrl = BrowserController.looksLikeUrl(url)
    val fullUrl = if (validUrl) BrowserController.toUrl(url) else null

    // Muster aus dem Link ableiten, solange der Nutzer es nicht selbst angepasst hat.
    LaunchedEffect(url, kind) {
        if (kind == "series" && !templateTouched) template = fullUrl?.let(Pattern::derive).orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = actions.dismiss,
        sheetState = state,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()
        ) {
            Text("HINZUFÜGEN", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text("Was kommt dazu?", style = MaterialTheme.typography.headlineSmall)
            VSpace(20.dp)
            Segmented(listOf("movie" to "Film", "series" to "Serie"), kind, { kind = it })
            VSpace(20.dp)

            IvoryField(url, { url = it }, if (kind == "series") "Link zu einer Folge" else "Link zum Film", KeyboardType.Uri, label = "Link")
            QuietButton("Aus der Zwischenablage", color = Palette.text) {
                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                Regex("""https?://\S+""").find(clip)?.value?.let { url = it; templateTouched = false }
            }

            if (kind == "movie") {
                VSpace(8.dp)
                Text("Titel, Bild und Beschreibung holt die App von der Seite.", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                VSpace(24.dp)
                PrimaryButton("Hinzufügen", icon = Ic.add, enabled = fullUrl != null) { fullUrl?.let(actions.addMovie) }
            } else {
                VSpace(12.dp)
                IvoryField(name, { name = it }, "Name der Serie", label = "Name")
                VSpace(12.dp)
                IvoryField(template, { template = it; templateTouched = true }, "…/staffel-{s}/episode-{e}", KeyboardType.Uri, label = "Muster", singleLine = false)
                Text("{s} = Staffel, {e} = Folge, zweistellig {ss}, {ee}.", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                VSpace(12.dp)
                IvoryField(seasonsText, { seasonsText = it }, "12, 8, 10", KeyboardType.Text, label = "Folgen je Staffel")

                val seasons = Pattern.parseSeasons(seasonsText)
                val templateOk = template.contains("{e}") || template.contains("{ee}")
                if (templateOk && seasons.isNotEmpty()) {
                    VSpace(14.dp)
                    val total = seasons.sum()
                    Text(
                        "$total Folgen in ${seasons.size} ${if (seasons.size == 1) "Staffel" else "Staffeln"}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("Erste: " + Pattern.fill(template, 1, 1), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Text("Letzte: " + Pattern.fill(template, seasons.size, seasons.last()), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                } else if (validUrl && !templateOk) {
                    VSpace(10.dp)
                    Text("Im Link steckt keine Folgennummer. Trag {e} im Muster selbst ein.", style = MaterialTheme.typography.bodySmall, color = Palette.wine)
                }
                VSpace(24.dp)
                PrimaryButton("Serie anlegen", icon = Ic.add, enabled = name.isNotBlank() && templateOk && seasons.isNotEmpty()) {
                    actions.addSeries(name, template.trim(), seasons)
                }
            }
            if (fullUrl != null) {
                VSpace(4.dp)
                Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                    QuietButton("Nur ansehen, nicht speichern", color = Palette.muted) { actions.watchNow(fullUrl) }
                    QuietButton("Im Browser öffnen", color = Palette.muted) { actions.openInBrowser(fullUrl) }
                }
            }
        }
    }
}
