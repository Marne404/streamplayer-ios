package de.marne.streamplayer.ios.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.nowMillis
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Was abgespielt werden soll: Film oder Folge, Fortschritts-Schlüssel und Startposition. */
internal data class PlayRequest(val title: LibTitle, val episode: Episode?, val key: String, val startMs: Long)

/** Stand für den Fortschritt: Position, Dauer, ob zu Ende, wie lange seit der letzten Meldung geschaut. */
internal fun interface ProgressSink {
    fun report(key: String, positionMs: Long, durationMs: Long, ended: Boolean, playedMs: Long)
}

@Composable
internal fun PlayFlow(req: PlayRequest, sink: ProgressSink, onNext: (Episode) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resolver = remember { Resolver(scope) }
    val rs by resolver.state.collectAsState()
    var found by remember(req.key) { mutableStateOf<Found?>(null) }
    var showPage by remember(req.key) { mutableStateOf(false) }
    val page = req.episode?.url?.takeIf { it.isNotBlank() } ?: req.title.pageUrl

    LaunchedEffect(req.key) {
        found = null
        if (page != null) resolver.start(page)
    }
    LaunchedEffect(rs) {
        (rs as? Resolver.State.Ready)?.let { found = it.found; resolver.stop() }
    }
    DisposableEffect(Unit) { onDispose { resolver.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val f = found
        if (f != null) {
            PlayerScreen(req, f, sink, onNext, onClose)
        } else {
            // Die Seite muss auf dem Bildschirm liegen, sonst bauen viele Player sich nicht auf.
            UIKitView(factory = { resolver.webView }, modifier = Modifier.fillMaxSize())
            if (showPage) PageBar(onSearch = { showPage = false; scope.launch { page?.let { resolver.start(it) } } }, onClose = onClose)
            else Searching(req, page, rs, onShowPage = { showPage = true }, onRetry = { scope.launch { page?.let { resolver.start(it) } } }, onClose = onClose)
        }
    }
}

@Composable
private fun Searching(req: PlayRequest, page: String?, rs: Resolver.State, onShowPage: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Thumb(req.title.wide, Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.bg.copy(alpha = 0.55f), 0.5f to Palette.bg.copy(alpha = 0.85f), 1f to Palette.bg)))
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((page?.let { Format.host(it) } ?: "").uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Spacer(Modifier.height(8.dp))
                Text(req.title.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                req.episode?.let { e ->
                    Text(listOfNotNull(e.label, e.title).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                }
                Spacer(Modifier.height(28.dp))
                when (rs) {
                    Resolver.State.NotFound, Resolver.State.Protected -> {
                        Text(
                            if (rs == Resolver.State.Protected) tr("Das Video ist geschützt (DRM) und lässt sich hier nicht abspielen.", "The video is protected (DRM) and can't be played here.")
                            else tr("Kein Video gefunden. Auf der Seite selbst starten oder es nochmal versuchen.", "No video found. Start it on the page itself or try again."),
                            style = MaterialTheme.typography.bodyLarge, color = Palette.text, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton(tr("Seite anzeigen", "Show page"), Modifier.fillMaxWidth(), onClick = onShowPage)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(tr("Nochmal suchen", "Search again"), Modifier.fillMaxWidth(), onClick = onRetry)
                    }
                    else -> {
                        val s = rs as? Resolver.State.Searching
                        if (page == null) Text(tr("Für diesen Titel ist keine Seite hinterlegt.", "This title has no page."), color = Palette.muted)
                        else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(4) { i -> Box(Modifier.width(34.dp).height(3.dp).clip(CircleShape).background(if (i <= (s?.phase ?: 0)) Palette.brass else Palette.line)) }
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(s?.step ?: tr("Seite lädt …", "Loading page …"), style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onClose)
            }
        }
    }
}

/** Über der Seite: zurück zur Suche (sie läuft dann erneut) oder schließen. */
@Composable
private fun PageBar(onSearch: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.bg.copy(alpha = 0.94f)).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietButton(tr("Schließen", "Close"), color = Palette.text, onClick = onClose)
        Spacer(Modifier.weight(1f))
        Text(tr("Starte das Video auf der Seite – dann übernimmt der Player.", "Start the video on the page – the player takes over."), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        Spacer(Modifier.weight(1f))
        QuietButton(tr("Erneut suchen", "Search again"), color = Palette.text, onClick = onSearch)
    }
}

@Composable
private fun PlayerScreen(req: PlayRequest, f: Found, sink: ProgressSink, onNext: (Episode) -> Unit, onClose: () -> Unit) {
    val player = remember(f.url) { IpadPlayer() }
    var st by remember { mutableStateOf(PlayState()) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(nowMillis()) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var ended by remember(f.url) { mutableStateOf(false) }
    val next = remember(req) { req.episode?.let { req.title.next(it) } }
    val acc = remember(f.url) { Acc() }

    fun report(end: Boolean) {
        val s = player.state()
        if (s.durationMs <= 0) return
        sink.report(req.key, if (end) s.durationMs else s.positionMs, s.durationMs, end, acc.take())
    }

    LaunchedEffect(f.url) {
        player.load(f, req.startMs)
        var last = nowMillis()
        var lastSave = last
        var wasPlaying = false
        while (true) {
            delay(500)
            val now = nowMillis()
            val s = player.state()
            if (s.playing) acc.played += now - last
            last = now
            st = s
            val atEnd = s.ready && s.durationMs > 0 && s.positionMs >= s.durationMs - 800 && !s.playing && !s.buffering
            if (atEnd && !ended) {
                ended = true
                report(true)
                lastSave = now
            } else if ((wasPlaying && !s.playing) || (s.playing && now - lastSave > 30_000)) {
                report(false)
                lastSave = now
            }
            wasPlaying = s.playing
            if (controls && s.playing && seeking == null && now - touched > 3500) controls = false
        }
    }
    // Zu Ende: nach kurzem Hinweis die nächste Folge.
    LaunchedEffect(ended) {
        if (ended && next != null) { delay(5000); if (ended) onNext(next) }
    }
    DisposableEffect(f.url) {
        onDispose {
            if (!ended) report(false)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        UIKitView(factory = { player.view }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controls = !controls; touched = nowMillis()
            },
        )
        if (st.buffering || !st.ready && st.failed == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
        }
        AnimatedVisibility(controls || st.failed != null || ended, enter = fadeIn(tween(180)), exit = fadeOut(tween(260))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Ic.back, tr("Zurück", "Back"), 44) { onClose() }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(req.title.title, fontFamily = Serif, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            req.episode?.let { e -> Text(listOfNotNull(e.label, e.title).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                        }
                        if (st.pipPossible || st.pip) RoundButton(Ic.cropFree, tr("Bild im Bild", "Picture in picture"), 44) { player.togglePip(); touched = nowMillis() }
                    }
                    Spacer(Modifier.weight(1f))
                    st.failed?.let {
                        Text(tr("Wiedergabe fehlgeschlagen: $it", "Playback failed: $it"), color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    if (st.failed == null) Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Ic.replay10, tr("10 s zurück", "Back 10 s"), 56) { player.skip(-10_000); touched = nowMillis() }
                        RoundButton(if (st.playing) Ic.pause else Ic.play, if (st.playing) "Pause" else tr("Abspielen", "Play"), 84, filled = true) {
                            if (ended) { ended = false; player.seek(0) }
                            player.toggle(); touched = nowMillis()
                        }
                        RoundButton(Ic.forward10, tr("10 s vor", "Forward 10 s"), 56) { player.skip(10_000); touched = nowMillis() }
                    }
                    Spacer(Modifier.weight(1f))
                    if (ended && next != null) {
                        Text(tr("Nächste Folge startet gleich …", "Next episode starts shortly …"), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(10.dp))
                    }
                    val dur = st.durationMs.coerceAtLeast(1)
                    Slider(
                        value = seeking ?: (st.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { seeking = it; touched = nowMillis() },
                        onValueChangeFinished = { seeking?.let { player.seek((it * dur).toLong()) }; seeking = null },
                        enabled = st.durationMs > 0,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.brass, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = seeking?.let { (it * dur).toLong() } ?: st.positionMs
                        Text(Format.duration(shown), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        if (next != null) {
                            Row(
                                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable { onNext(next) }.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(painterResource(Ic.skipNext), null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Nächste Folge", "Next episode"), color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(if (st.durationMs > 0) "-" + Format.duration((st.durationMs - shown).coerceAtLeast(0)) else "", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Geschaute Zeit seit der letzten Meldung. */
private class Acc {
    var played = 0L
    fun take(): Long = played.also { played = 0 }
}

@Composable
private fun RoundButton(icon: DrawableResource, label: String, size: Int, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, tint = if (filled) Color.Black else Color.White, modifier = Modifier.size((size * 0.44f).dp))
    }
}
