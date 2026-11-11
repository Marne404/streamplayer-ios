package de.marne.streamplayer.library

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import de.marne.streamplayer.browser.WatchController
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlin.math.exp

/** Was der Ladebildschirm zeigt, solange der Stream gesucht wird. */
data class ResolveInfo(
    val title: String,
    val eyebrow: String? = null,
    val subtitle: String? = null,
    /** Breites Bild: Standbild der Folge oder Hintergrund des Titels. */
    val image: String? = null,
    /** Hochformat-Cover neben dem Titel. */
    val poster: String? = null,
    val download: Boolean = false,
)

// Zwei Kurven, mehr nicht: Spring kommt an und bleibt, Bouncy schießt über und federt zurück.
private val Spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val Bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private const val DUR_FADE = 220
private const val DUR_SWAP = 320
private const val DUR_MOVE = 420

/**
 * Ladebildschirm „Gleich geht's los“: deckt alles ab – auch Adressleiste und Statusleiste –,
 * während die Seite dahinter verdeckt lädt. Immer dunkel, egal welches Theme: danach kommt der
 * schwarze Player, und hell → dunkel → schwarz liest sich als ein Abdunkeln statt als zwei Wechsel.
 *
 * [visible] steuert das Ein- und Ausblenden; [instant] blendet ohne Animation aus – für den Fall,
 * dass der Player schon davor liegt und ein Ausblenden erst beim Zurückkommen sichtbar würde.
 */
@Composable
fun ResolveScreen(
    visible: Boolean,
    instant: Boolean,
    info: ResolveInfo?,
    state: WatchController.State,
    onCancel: () -> Unit,
    onShowPage: () -> Unit,
) {
    val appDark = Palette.isDark
    val shown = remember { Animatable(0f) }
    LaunchedEffect(visible, instant) {
        when {
            visible -> shown.animateTo(1f, tween(DUR_MOVE, easing = Spring))
            instant -> shown.snapTo(0f)
            // Rückweg kürzer als Hinweg.
            else -> shown.animateTo(0f, tween(DUR_FADE, easing = Spring))
        }
    }

    // Beim Ausblenden ist der Zustand schon Idle – den letzten echten behalten, sonst wird der
    // Bildschirm leer, während er noch sichtbar verblasst.
    var held by remember { mutableStateOf(state) }
    if (state != WatchController.State.Idle && held != state) held = state
    var heldInfo by remember { mutableStateOf(info) }
    if (info != null && heldInfo != info) heldInfo = info

    // Symbole erst umschalten, wenn der dunkle Grund überwiegt – sonst stehen beim Einblenden
    // kurz weiße Symbole auf der hellen Bibliothek.
    val dark by remember { derivedStateOf { shown.value > 0.5f } }
    SystemBarsDark(dark, appDark)
    BackHandler(enabled = visible) { onCancel() }

    if (!visible && shown.value == 0f) return
    val i = heldInfo ?: return

    IvoryInkTheme(dark = true) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = shown.value
                    // Kommt ganz leicht auf einen zu: der Bildschirm „öffnet sich“, statt nur aufzutauchen.
                    val s = 1.03f - 0.03f * shown.value
                    scaleX = s; scaleY = s
                }
                .background(Palette.bg)
                // Alles abfangen: darunter liegt die Bibliothek bzw. die WebView.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        ) {
            // Querformat (z. B. nach dem Player, der quer läuft): Bild über die ganze Breite, Text links.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight
                Backdrop(i.image ?: i.poster, blurred = i.image == null, landscape = landscape)
                Content(i, held, visible, onCancel, onShowPage, landscape)
            }
        }
    }
}

/** Statusleisten-Symbole hell, solange der dunkle Bildschirm liegt; danach wieder wie das Theme. */
@Composable
private fun SystemBarsDark(active: Boolean, appDark: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(active, appDark) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (active) {
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
        }
        onDispose {
            c.isAppearanceLightStatusBars = !appDark
            c.isAppearanceLightNavigationBars = !appDark
        }
    }
}

@Composable
private fun Backdrop(model: String?, blurred: Boolean, landscape: Boolean) {
    if (model == null) return
    var loaded by remember(model) { mutableStateOf(false) }
    // Das Bild blendet ein, sobald es da ist – nie ein harter Wechsel von leer auf Bild.
    val imageAlpha by animateFloatAsState(if (loaded) 1f else 0f, tween(DUR_MOVE * 2, easing = Spring), label = "backdrop")
    // Langsamer Ken-Burns-Zoom: das Bild lebt, ohne dass etwas passiert, das man verfolgen muss.
    val zoom by rememberInfiniteTransition(label = "kenburns").animateFloat(
        initialValue = 1.04f, targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    val bg = Palette.bg
    // clipToBounds: der Zoom vergrößert über den Rahmen hinaus, der Verlauf deckt aber nur den Rahmen –
    // ohne Beschnitt stand unten eine harte Kante aus Bild unter dem Verlauf.
    // Hochformat: oberes Zweidrittel. Querformat: ganze Fläche, der Text liegt links auf dem Verlauf.
    Box(Modifier.fillMaxWidth().fillMaxHeight(if (landscape) 1f else 0.66f).clipToBounds()) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { if (it is AsyncImagePainter.State.Success) loaded = true },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    alpha = imageAlpha * if (blurred) 0.45f else 0.62f
                }
                // Ohne Breitbild nur das Cover: groß gezogen wäre es pixelig, verschwommen wird es Stimmung.
                // Unter Android 12 gibt es keinen Blur – dann eben ohne, die Bewegung bleibt.
                .then(if (blurred && Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
        )
        if (landscape) {
            // Von links: Grund, auf dem der Text ruht, nach rechts frei für das Bild; unten leicht abgedunkelt.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to bg,
                        0.38f to bg.copy(alpha = 0.82f),
                        0.72f to Color.Transparent,
                    )
                )
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to bg.copy(alpha = 0.7f))))
        } else {
            // Oben: Statusleiste lesbar. Unten: Bild läuft in den Grund aus, der Text steht auf Ruhe.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.55f),
                        0.25f to Color.Transparent,
                        0.55f to bg.copy(alpha = 0.35f),
                        1f to bg,
                    )
                )
            )
        }
    }
}

@Composable
private fun Content(
    i: ResolveInfo,
    state: WatchController.State,
    visible: Boolean,
    onCancel: () -> Unit,
    onShowPage: () -> Unit,
    landscape: Boolean,
) {
    // Hülle zuerst, Inhalt danach: 150 ms Vorlauf, dann Zeile für Zeile im 30-ms-Takt von oben.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(INTRO_TOTAL, easing = LinearEasing)) }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    fun Modifier.enter(index: Int) = graphicsLayer {
        val ms = intro.value * INTRO_TOTAL - 150 - index * 30
        val t = (ms / DUR_MOVE).coerceIn(0f, 1f)
        translationY = (1f - Bouncy.transform(t)) * lift
        alpha = Spring.transform(t)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            if (landscape) Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars))
                .padding(start = 48.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)
                .widthIn(max = 470.dp)
            else Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        ) {
            val poster: @Composable () -> Unit = {
                Box(
                    Modifier
                        .enter(0)
                        .size(width = 84.dp, height = 126.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Palette.text.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                        .background(Palette.surface2),
                ) {
                    AsyncImage(model = i.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            val texts: @Composable () -> Unit = {
                Column {
                    i.eyebrow?.let {
                        Text(
                            it.uppercase(), modifier = Modifier.enter(1),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                            color = Palette.brass, maxLines = 1,
                        )
                        VSpace(10.dp)
                    }
                    Text(
                        i.title, modifier = Modifier.enter(2),
                        // Quer ist die Höhe knapp: eine Stufe kleiner, höchstens zwei Zeilen.
                        style = if (landscape) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                        color = Palette.text,
                        maxLines = if (landscape) 2 else 3, overflow = TextOverflow.Ellipsis,
                    )
                    i.subtitle?.let {
                        VSpace(if (landscape) 4.dp else 8.dp)
                        Text(
                            it, modifier = Modifier.enter(3),
                            style = MaterialTheme.typography.bodyLarge, color = Palette.muted,
                            maxLines = if (landscape) 1 else 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val hasPoster = i.poster != null && i.image != null
            if (landscape) {
                // Poster neben dem Text statt darüber – sonst passte der Balken nicht mehr auf den Schirm.
                Row(verticalAlignment = Alignment.Bottom) {
                    if (hasPoster) { poster(); Spacer(Modifier.width(20.dp)) }
                    texts()
                }
                VSpace(28.dp)
            } else {
                if (hasPoster) { poster(); VSpace(22.dp) }
                texts()
                VSpace(36.dp)
            }

            // Suchen und Scheitern sind zwei Gesten: der Balken weicht nach unten, die Meldung steigt auf.
            val kind = when (state) {
                is WatchController.State.NotFound -> 1
                is WatchController.State.Protected -> 2
                else -> 0
            }
            AnimatedContent(
                targetState = kind,
                modifier = Modifier.enter(4),
                transitionSpec = {
                    (fadeIn(tween(DUR_SWAP, delayMillis = 120, easing = Spring)) +
                        slideInVertically(tween(DUR_MOVE, delayMillis = 120, easing = Bouncy)) { it / 6 }) togetherWith
                        (fadeOut(tween(DUR_FADE, easing = Spring)) + slideOutVertically(tween(DUR_FADE, easing = Spring)) { it / 8 }) using
                        SizeTransform(clip = false) { _, _ -> tween(DUR_MOVE, easing = Spring) }
                },
                label = "resolve",
            ) { k ->
                Column(Modifier.fillMaxWidth()) {
                    when (k) {
                        0 -> Searching(state, i.download, visible, onCancel)
                        1 -> Failed(
                            tr("Kein Video gefunden.", "No video found."),
                            tr(
                                "Manche Seiten zeigen es erst nach einem Tipp. Öffne die Seite und starte es selbst.",
                                "Some sites only show it after a tap. Open the page and start it yourself.",
                            ),
                            null, onShowPage, onCancel,
                        )
                        else -> Failed(
                            tr("Geschützt.", "Protected."),
                            null,
                            tr("Kopierschutz (DRM). Geht nur auf der Seite selbst.", "Copy protection (DRM). Only plays on the site itself."),
                            onShowPage, onCancel,
                        )
                    }
                }
            }
        }
    }
}

private const val INTRO_TOTAL = 150 + 4 * 30 + DUR_MOVE

@Composable
private fun ColumnScope.Searching(state: WatchController.State, download: Boolean, visible: Boolean, onCancel: () -> Unit) {
    val (phase, step) = when (state) {
        is WatchController.State.Searching -> state.phase to state.step
        is WatchController.State.Ready -> 4 to if (download) tr("Download startet …", "Starting download …") else tr("Gleich geht's los.", "Starting soon.")
        is WatchController.State.Challenge -> 1 to tr("Wartet auf deine Bestätigung …", "Waiting for your confirmation …")
        else -> 0 to ""
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(phase >= 3) { if (phase >= 3) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }

    ProgressLine(phase, running = visible)
    VSpace(14.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                // Nacheinander, nicht gleichzeitig: zwei halb sichtbare Zeilen übereinander lesen sich als Fehler.
                (fadeIn(tween(DUR_SWAP, delayMillis = 140, easing = Spring)) +
                    slideInVertically(tween(DUR_SWAP, delayMillis = 140, easing = Bouncy)) { it / 2 }) togetherWith
                    (fadeOut(tween(140, easing = Spring)) + slideOutVertically(tween(DUR_FADE, easing = Spring)) { -it / 2 }) using
                    SizeTransform(clip = false)
            },
            label = "step",
        ) { s ->
            Text(s, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onCancel)
    }
}

/**
 * Dünne Messinglinie. Jede Phase hat ein Band; innerhalb kriecht die Linie asymptotisch auf das
 * Bandende zu – sie steht nie still und erreicht es nie, bevor die nächste Phase wirklich da ist.
 * Ein Phasenwechsel zieht sie weich nach, statt zu springen.
 */
@Composable
private fun ProgressLine(phase: Int, running: Boolean) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(phase, running) {
        if (!running) return@LaunchedEffect
        val (lo, hi) = BANDS[phase.coerceIn(0, BANDS.lastIndex)]
        if (p.value < lo) p.animateTo(lo, tween(DUR_MOVE, easing = Spring))
        if (lo == hi) return@LaunchedEffect
        val t0 = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val creep = lo + (hi - lo) * (1f - exp(-(now - t0) / 4000f))
            if (creep > p.value) p.snapTo(creep)
        }
    }
    // Ein Lichtschein läuft über die Linie: zeigt „es arbeitet“, auch wenn der Fortschritt kriecht.
    // Linear, weil er in einem Zug durchläuft – eine Federkurve würde ihn vorne hinschießen lassen.
    val sheen by rememberInfiniteTransition(label = "sheen").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sheenPos",
    )
    val track = Palette.text.copy(alpha = 0.12f)
    val brass = Palette.brass
    val glow = Palette.text.copy(alpha = 0.55f)
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        val y = size.height / 2
        val w = size.width * p.value
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        if (w > 0f) {
            drawLine(brass, Offset(0f, y), Offset(w, y), strokeWidth = size.height, cap = StrokeCap.Round)
            val band = size.width * 0.18f
            val x = -band + (w + band) * sheen
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, glow, Color.Transparent), startX = x, endX = x + band),
                Offset(maxOf(0f, x), y), Offset(minOf(w, x + band), y),
                strokeWidth = size.height, cap = StrokeCap.Round,
            )
        }
    }
}

/** Fortschritts-Bänder je Phase (siehe WatchController.State.Searching.phase); 4 = gefunden, voll. */
private val BANDS = listOf(0.04f to 0.34f, 0.34f to 0.58f, 0.58f to 0.80f, 0.80f to 0.94f, 1f to 1f)

@Composable
private fun ColumnScope.Failed(headline: String, body: String?, protectedNote: String?, onShowPage: () -> Unit, onCancel: () -> Unit) {
    Text(headline, style = MaterialTheme.typography.headlineSmall, color = Palette.text)
    body?.let {
        VSpace(6.dp)
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
    }
    protectedNote?.let {
        VSpace(10.dp)
        StatusLine(Ic.shieldLock, it, Palette.wine)
    }
    VSpace(24.dp)
    PrimaryButton(tr("Seite anzeigen", "Show page"), icon = Ic.browser, onClick = onShowPage)
    QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally), onClick = onCancel)
}
