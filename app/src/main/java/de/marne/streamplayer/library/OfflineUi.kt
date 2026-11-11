package de.marne.streamplayer.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.download.DlState
import de.marne.streamplayer.download.Offline
import de.marne.streamplayer.download.OfflineState
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.ProgressRing
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.launch

// ---------- Das eine Zeichen ----------

/** Art des Zustands, ohne Fortschritt: Nur ein Wechsel der Art wird animiert, nicht jedes Prozent. */
private fun OfflineState.kind(): Int = when (this) {
    OfflineState.None -> 0
    is OfflineState.Waiting -> if (searching) 1 else 6
    is OfflineState.Loading -> 2
    is OfflineState.Stopped -> if (failed) 4 else 3
    is OfflineState.Done -> 5
}

/**
 * Pfeil → Kreisel (sucht) → Ring (lädt) → Haken. Die Teile gehen ineinander über (Bouncy beim
 * Ankommen, kürzer beim Gehen), der Platz bleibt gleich groß – nichts in der Zeile springt.
 */
@Composable
private fun MarkGlyph(state: OfflineState, ring: Dp) {
    AnimatedContent(
        targetState = state.kind(),
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(320, easing = BouncyEase), initialScale = 0.6f)) togetherWith
                (fadeOut(tween(140)) + scaleOut(tween(200, easing = SpringEase), targetScale = 0.6f))
        },
        contentAlignment = Alignment.Center,
        label = "offlineMark",
    ) { kind ->
        Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
            when (kind) {
                0 -> GIcon(Ic.download, tint = Palette.muted, size = 20.dp)
                1 -> {
                    // Kreist, solange gesucht wird. Gleichmäßige Drehung: Eine Federkurve würde bei jeder Runde abbremsen.
                    val spin = rememberInfiniteTransition(label = "spin")
                    val deg by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "deg")
                    Box(Modifier.graphicsLayer { rotationZ = deg }) { ProgressRing(0.28f, ring, stroke = 2.5.dp, color = Palette.muted) }
                }
                2 -> {
                    val item = (state as? OfflineState.Loading)?.item
                    ProgressRing(item?.progress ?: 0f, ring, stroke = 2.5.dp, color = Palette.brass) {
                        // Das Quadrat in der Mitte sagt: antippen hält an.
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(Palette.text))
                    }
                }
                3 -> {
                    val item = (state as? OfflineState.Stopped)?.item
                    ProgressRing(item?.progress ?: 0f, ring, stroke = 2.5.dp, color = Palette.muted) { GIcon(Ic.pause, tint = Palette.muted, size = 12.dp) }
                }
                4 -> GIcon(Ic.error, tint = Palette.wine, size = 20.dp)
                // Vorgemerkt: steht still (Uhr) – kreisen darf nur, was gerade wirklich sucht.
                6 -> GIcon(Ic.schedule, tint = Palette.muted, size = 20.dp)
                else -> GIcon(Ic.check, tint = Palette.green, size = 20.dp)
            }
        }
    }
}

/** Das Zeichen als Knopf in einer Zeile: ohne Download lädt Antippen, sonst öffnet es das Blatt. */
@Composable
fun DownloadMark(state: OfflineState, onDownload: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.size(44.dp).pressScale(press, 0.88f).clip(CircleShape)
            .clickable(interactionSource = press, indication = null) { if (state == OfflineState.None) onDownload() else onMenu() },
        contentAlignment = Alignment.Center,
    ) { MarkGlyph(state, 24.dp) }
}

/** Wie ActionIcon (runder Knopf, Beschriftung darunter), aber mit dem lebenden Zeichen. */
@Composable
fun DownloadAction(state: OfflineState, idleLabel: String, onDownload: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(press, 0.92f).clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = press, indication = null) { if (state == OfflineState.None) onDownload() else onMenu() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.surface2), contentAlignment = Alignment.Center) { MarkGlyph(state, 24.dp) }
        Text(shortLabel(state, idleLabel), style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** Unter dem Knopf: ein, zwei Wörter. */
fun shortLabel(state: OfflineState, idle: String): String = when (state) {
    OfflineState.None -> idle
    is OfflineState.Waiting -> if (state.searching) tr("Sucht …", "Finding …") else tr("Vorgemerkt", "Queued")
    is OfflineState.Loading -> when (state.item.state) {
        DlState.RUNNING, DlState.MERGING -> Format.percent(state.item.progress)
        DlState.WAITING_WIFI -> tr("Wartet (WLAN)", "Waiting (Wi-Fi)")
        else -> tr("Wartet", "Waiting")
    }
    is OfflineState.Stopped -> if (state.failed) tr("Fehler", "Failed") else tr("Angehalten", "Paused")
    is OfflineState.Done -> tr("Geladen", "Downloaded")
}

/** Ganzer Satz, für das Blatt und die Folgen-Optionen. */
fun statusLine(state: OfflineState): String = when (state) {
    OfflineState.None -> tr("Nicht geladen.", "Not downloaded.")
    is OfflineState.Waiting -> if (state.searching) tr("Sucht das Video auf der Seite …", "Finding the video on the page …")
    else tr("Vorgemerkt – kommt dran, sobald die Suche davor fertig ist.", "Queued – starts once the search before it is done.")
    is OfflineState.Loading -> Format.metaLine(state.item)
    is OfflineState.Stopped -> Format.metaLine(state.item)
    is OfflineState.Done -> buildList {
        add(tr("Auf diesem Gerät", "On this device"))
        state.item?.let { d ->
            d.quality?.let(::add)
            Offline.bytes(d).takeIf { it > 0 }?.let { add(Format.bytes(it)) }
        }
    }.joinToString(" · ")
}

// ---------- Das Blatt ----------

/** Was das Blatt auslösen kann; umgesetzt in AppRoot. */
class OfflineSheetActions(
    val play: () -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    /** Laufenden Download oder die Suche abbrechen. */
    val cancel: () -> Unit,
    /** Fertige Datei löschen. */
    val delete: () -> Unit,
    val share: (() -> Unit)?,
)

/**
 * Ein Blatt für jeden Stand: oben, was ist („Lädt · 42 % · 1,2 GB · 3 MB/s“), darunter nur, was
 * jetzt Sinn ergibt. Löschen fragt im Blatt nach (die Zeile wird zur Rückfrage), kein Dialog
 * darüber – zwei Ebenen übereinander verdecken, worum es geht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSheet(eyebrow: String, title: String, state: OfflineState, actions: OfflineSheetActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    // Ist nichts mehr da (abgebrochen, anderswo gelöscht), hat das Blatt keinen Gegenstand mehr.
    LaunchedEffect(state == OfflineState.None) { if (state == OfflineState.None) then {} }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    VSpace(6.dp)
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.surface2), contentAlignment = Alignment.Center) { MarkGlyph(state, 28.dp) }
            }
            VSpace(8.dp)
            val failed = (state as? OfflineState.Stopped)?.failed == true
            Text(statusLine(state), style = MaterialTheme.typography.bodyMedium, color = if (failed) Palette.wine else Palette.muted, maxLines = 3)
            if (state is OfflineState.Loading || state is OfflineState.Stopped) {
                val item = (state as? OfflineState.Loading)?.item ?: (state as OfflineState.Stopped).item
                VSpace(12.dp)
                de.marne.streamplayer.ui.design.Bar(item.progress, height = 4.dp, color = if (state is OfflineState.Loading) Palette.brass else Palette.muted)
            }
            VSpace(18.dp)
            AnimatedContent(
                targetState = confirm,
                transitionSpec = { (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(320, easing = BouncyEase), initialScale = 0.96f)) togetherWith fadeOut(tween(140)) },
                label = "confirm",
            ) { asking ->
                if (asking) {
                    Column {
                        Text(tr("Datei löschen?", "Delete the file?"), style = MaterialTheme.typography.titleMedium)
                        VSpace(4.dp)
                        Text(
                            tr("Nur das Video verschwindet vom Gerät. Titel, Fortschritt und Listen bleiben – laden geht jederzeit wieder.",
                                "Only the video leaves this device. Title, progress and lists stay – you can download it again anytime."),
                            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                        )
                        VSpace(12.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietButton(tr("Löschen", "Delete"), color = Palette.wine) { then(actions.delete) }
                            QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirm = false }
                        }
                    }
                } else ListGroup {
                    when (state) {
                        is OfflineState.Done -> {
                            ListRow(tr("Abspielen", "Play"), icon = Ic.play, subtitle = tr("Ohne Internet", "No internet needed"), showChevron = false, onClick = { then(actions.play) })
                            actions.share?.let { share ->
                                RowDivider()
                                ListRow(tr("Teilen", "Share"), icon = Ic.share, showChevron = false, onClick = { then(share) })
                            }
                            RowDivider()
                            ListRow(tr("Download löschen", "Delete download"), icon = Ic.delete, titleColor = Palette.wine, showChevron = false, onClick = { confirm = true })
                        }
                        is OfflineState.Loading -> {
                            ListRow(tr("Anhalten", "Pause"), icon = Ic.pause, showChevron = false, onClick = { then(actions.pause) })
                            RowDivider()
                            ListRow(tr("Abbrechen", "Cancel"), icon = Ic.close, titleColor = Palette.wine, subtitle = tr("Schon Geladenes wird verworfen.", "Anything downloaded so far is discarded."), showChevron = false, onClick = { then(actions.cancel) })
                        }
                        is OfflineState.Stopped -> {
                            ListRow(if (state.failed) tr("Erneut versuchen", "Try again") else tr("Fortsetzen", "Resume"), icon = if (state.failed) Ic.refresh else Ic.play, showChevron = false, onClick = { then(actions.resume) })
                            RowDivider()
                            ListRow(tr("Abbrechen", "Cancel"), icon = Ic.close, titleColor = Palette.wine, showChevron = false, onClick = { then(actions.cancel) })
                        }
                        is OfflineState.Waiting -> {
                            ListRow(tr("Nicht laden", "Don't download"), icon = Ic.close, titleColor = Palette.wine, showChevron = false, onClick = { then(actions.cancel) })
                        }
                        OfflineState.None -> Unit
                    }
                }
            }
        }
    }
}

// ---------- Serien: Überblick über der Folgenliste ----------

/**
 * „3 Folgen geladen · 2,1 GB“ mit „Staffel laden“ und „Löschen“. Steht über den Folgen, weil man
 * genau dort entscheidet, was mit soll.
 */
@Composable
fun OfflineSummary(t: LibTitle, season: Int, state: LibState, actions: LibActions) {
    val episodes = t.episodes()
    val states = episodes.associateWith { state.offline(t, Keys.episode(t, it)) }
    val done = states.filterValues { it is OfflineState.Done }
    val busy = states.count { it.value is OfflineState.Loading || it.value is OfflineState.Waiting }
    val missing = states.filter { (e, s) -> e.season == season && (s == OfflineState.None || s is OfflineState.Stopped) }
    val bytes = done.values.sumOf { (it as OfflineState.Done).item?.let(Offline::bytes) ?: 0L }
    var confirm by remember { mutableStateOf(false) }
    if (done.isEmpty() && busy == 0 && missing.isEmpty()) return

    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val head = when (done.size) {
                0 -> tr("Nichts geladen", "Nothing downloaded")
                1 -> tr("1 Folge geladen", "1 episode downloaded")
                else -> tr("${done.size} Folgen geladen", "${done.size} episodes downloaded")
            }
            Text(listOfNotNull(head, bytes.takeIf { it > 0 }?.let(Format::bytes)).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = if (done.isEmpty()) Palette.muted else Palette.text)
            if (busy > 0) Text(if (busy == 1) tr("1 lädt gerade", "1 in progress") else tr("$busy laden gerade", "$busy in progress"), style = MaterialTheme.typography.bodySmall, color = Palette.brass)
        }
        if (done.isNotEmpty()) QuietButton(tr("Löschen", "Delete"), color = Palette.muted) { confirm = true }
        if (missing.isNotEmpty()) OutlinePill(tr("${t.seasonLabel(season)} laden", "Download ${t.seasonLabel(season)}"), icon = Ic.download, onClick = { actions.downloadSeason(t, season) })
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = Palette.surface,
        title = { Text(if (done.size == 1) tr("Geladene Folge löschen?", "Delete the downloaded episode?") else tr("${done.size} geladene Folgen löschen?", "Delete ${done.size} downloaded episodes?"), style = MaterialTheme.typography.headlineSmall) },
        text = { Text(tr("Nur die Videos verschwinden vom Gerät. Serie und Fortschritt bleiben.", "Only the videos leave this device. The show and your progress stay."), style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
        confirmButton = { QuietButton(tr("Löschen", "Delete"), color = Palette.wine) { confirm = false; actions.deleteOffline(t, done.keys.map { Keys.episode(t, it) }) } },
        dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirm = false } },
    )
}
