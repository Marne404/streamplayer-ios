package de.marne.spike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import de.marne.spike.res.Res
import kotlinx.coroutines.delay
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

const val PAGES = "https://marne404.github.io/streamplayer-ios/"
const val TEST_PAGE = PAGES + "test/"
const val APPLE_SAMPLE = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

fun MainViewController(): UIViewController = ComposeUIViewController { IvoryInkTheme { App() } }

private fun appVersion(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val v = info?.get("CFBundleShortVersionString") as? String ?: "?"
    val b = info?.get("CFBundleVersion") as? String ?: "?"
    return "$v ($b)"
}

@Composable
fun App() {
    val p = LocalPalette.current
    val player = remember { SpikePlayer() }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    val detector = remember { Detector { found = it } }
    var detectJs by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(PlayerState()) }

    LaunchedEffect(Unit) {
        detectJs = Res.readBytes("files/detect.js").decodeToString()
    }
    LaunchedEffect(Unit) {
        while (true) {
            state = player.state()
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth > 900.dp
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header()
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1.15f)) { PlayerCard(player, state) }
                        Column(Modifier.weight(1f)) {
                            DetectCard(detector, detectJs, found) { f -> player.load(f.url, f.page ?: TEST_PAGE) }
                        }
                    }
                } else {
                    PlayerCard(player, state)
                    DetectCard(detector, detectJs, found) { f -> player.load(f.url, f.page ?: TEST_PAGE) }
                }
                Text(
                    "Etappe 0 · Version ${appVersion()} · gebaut auf GitHub Actions ohne Mac",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.faint,
                )
            }
        }
    }
}

@Composable
private fun Header() {
    val p = LocalPalette.current
    Column {
        Text("IPAD-TEST", style = MaterialTheme.typography.labelSmall, color = p.brass)
        Text("Streamplayer", style = MaterialTheme.typography.displaySmall)
        Text(
            "Compose Multiplatform nativ auf iPadOS – Ivory & Ink, AVPlayer und die Erkennung aus der Android-App.",
            style = MaterialTheme.typography.bodyLarge,
            color = p.muted,
        )
    }
}

@Composable
private fun Card(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(p.surface)
            .border(BorderStroke(1.dp, p.line), MaterialTheme.shapes.large)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
        content()
    }
}

@Composable
private fun InkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.onInk),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun QuietButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, p.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.text),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Status(label: String, value: String, good: Boolean? = null) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dot = when (good) { true -> p.green; false -> p.wine; null -> p.faint }
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerCard(player: SpikePlayer, s: PlayerState) {
    Card("Player", "AVPlayer mit Referer-Kopfzeile, Steuerung in Compose") {
        UIKitView(
            factory = { player.view },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton(if (s.playing) "Pause" else "Abspielen", enabled = s.loaded != null) { player.togglePlay() }
            QuietButton(if (s.pipActive) "Bild im Bild beenden" else "Bild im Bild", enabled = s.pipPossible || s.pipActive) { player.togglePip() }
            QuietButton("Apple-Testvideo laden") { player.load(APPLE_SAMPLE, TEST_PAGE) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Status("Zustand", s.item + if (s.size.isNotEmpty()) " · ${s.size}" else "", good = when (s.item) { "bereit", "puffert …" -> true; "Fehler" -> false; else -> null })
            Status("Position", "${s.position.toInt() / 60}:${(s.position.toInt() % 60).toString().padStart(2, '0')}", good = if (s.position > 0.5) true else null)
            Status("Bild im Bild", when { s.pipActive -> "aktiv"; s.pipPossible -> "möglich"; else -> "noch nicht bereit" }, good = if (s.pipPossible || s.pipActive) true else null)
            Status("Referer", s.referer ?: "–")
            Status("Quelle", s.loaded ?: "–")
            s.error?.let { Status("Fehler", it, good = false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectCard(detector: Detector, detectJs: String?, found: List<Found>, onPlay: (Found) -> Unit) {
    val p = LocalPalette.current
    Card("Erkennung", "detect.js aus der Android-App in einer WKWebView") {
        if (detectJs == null) {
            Text("detect.js wird geladen …", color = p.muted)
            return@Card
        }
        UIKitView(
            factory = { detector.view(detectJs).also { detector.open(TEST_PAGE) } },
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Testseite neu laden") { detector.open(TEST_PAGE) }
        }
        Text("FUNDE (${found.size})", style = MaterialTheme.typography.labelSmall, color = p.brass)
        if (found.isEmpty()) {
            Text("Noch nichts gefunden.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
        found.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(p.surface2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        f.url.substringAfterLast('/').substringBefore('?').ifEmpty { f.url },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "über " + f.sources.joinToString(", ") + " · " + f.url.substringAfter("://").substringBefore('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InkButton("Abspielen") { onPlay(f) }
            }
        }
    }
}
