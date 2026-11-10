package de.marne.streamplayer.player

import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.VSpace
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** So lange gilt eine Folge von Doppeltipps als eine Sprungserie (+10, +20, +30 s …). */
private const val SEEK_SERIES_MS = 900L

/**
 * Gesten auf dem Bild: Tippen blendet die Steuerung ein/aus, Doppeltipp links/rechts springt
 * 10 s, senkrechtes Wischen links = Helligkeit, rechts = Lautstärke.
 *
 * Liegt *unter* der Steuerung: deren Knöpfe bekommen Berührungen zuerst, alles daneben fällt hierher.
 */
@Composable
internal fun GestureLayer(player: ExoPlayer, enabled: Boolean, onTap: () -> Unit) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }

    var seekSide by remember { mutableIntStateOf(0) }
    var seekSeconds by remember { mutableIntStateOf(0) }
    var seekAt by remember { mutableLongStateOf(0L) }
    var levelSide by remember { mutableIntStateOf(0) }
    var levelValue by remember { mutableFloatStateOf(0f) }
    var levelAt by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    fun seek(side: Int) {
        val now = System.currentTimeMillis()
        // Neue Serie, wenn die Seite wechselt oder die letzte zu lange her ist.
        if (side != seekSide || now - seekAt > SEEK_SERIES_MS) seekSeconds = 0
        seekSide = side
        seekSeconds += 10
        seekAt = now
        player.seekTo((player.currentPosition + side * 10_000L).coerceAtLeast(0))
    }
    LaunchedEffect(seekAt) {
        if (seekAt == 0L) return@LaunchedEffect
        delay(SEEK_SERIES_MS)
        seekSide = 0
    }
    LaunchedEffect(levelAt, dragging) {
        if (levelAt == 0L || dragging) return@LaunchedEffect
        delay(700)
        levelSide = 0
    }

    fun brightness(): Float {
        val w = activity?.window ?: return 0.5f
        val own = w.attributes.screenBrightness
        if (own >= 0f) return own
        // Noch nichts Eigenes gesetzt: beim System-Wert anfangen, sonst springt das Bild beim ersten Wisch.
        return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }
    fun setBrightness(v: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    fun volume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
    fun setVolume(v: Float) {
        // Flags 0: keine System-Lautstärkeleiste – wir zeigen unsere eigene.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { o ->
                        // Während einer Sprungserie zählt auch ein einzelnes Tippen weiter – wie bei YouTube.
                        if (seekSide != 0 && System.currentTimeMillis() - seekAt < SEEK_SERIES_MS) seek(if (o.x < size.width / 2) -1 else 1)
                        else onTap()
                    },
                    onDoubleTap = { o -> seek(if (o.x < size.width / 2) -1 else 1) },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var side = 0
                var value = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        // Ganz oben und unten wischt Android selbst (Systemleisten, Zurück-Geste).
                        side = if (o.y < size.height * 0.08f || o.y > size.height * 0.92f) 0 else if (o.x < size.width / 2) -1 else 1
                        value = when (side) { -1 -> brightness(); 1 -> volume(); else -> 0f }
                        dragging = side != 0
                    },
                    onVerticalDrag = { change, dy ->
                        if (side == 0) return@detectVerticalDragGestures
                        change.consume()
                        // 70 % der Höhe = voller Bereich: von ganz dunkel bis ganz hell mit einem Wisch.
                        value = (value - dy / (size.height * 0.7f)).coerceIn(0f, 1f)
                        if (side < 0) setBrightness(value) else setVolume(value)
                        levelSide = side
                        levelValue = value
                        levelAt = System.currentTimeMillis()
                    },
                    onDragEnd = { dragging = false; levelAt = System.currentTimeMillis() },
                    onDragCancel = { dragging = false; levelAt = System.currentTimeMillis() },
                )
            },
    ) {
        SeekRipple(left = true, visible = seekSide < 0, seconds = seekSeconds)
        SeekRipple(left = false, visible = seekSide > 0, seconds = seekSeconds)
        LevelIndicator(side = levelSide, value = levelValue)
    }
}

/** Halbkreis vom Rand her – zeigt, auf welcher Seite gesprungen wurde und wie weit. */
@Composable
private fun BoxScope.SeekRipple(left: Boolean, visible: Boolean, seconds: Int) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(DUR_PRESS)),
        exit = fadeOut(tween(DUR_SWAP)),
        modifier = Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.36f),
    ) {
        // Jeder weitere Sprung lässt das Symbol kurz federn: Rückmeldung pro Tipp, nicht nur pro Serie.
        val pulse = remember { Animatable(1f) }
        LaunchedEffect(seconds) {
            pulse.snapTo(0.82f)
            pulse.animateTo(1f, tween(DUR_SWAP, easing = Bouncy))
        }
        Box(
            Modifier.fillMaxSize().clip(ArcShape(left)).background(OnVideo.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(pulse.value)) {
                GIcon(if (left) Ic.replay10 else Ic.forward10, tint = OnVideo, size = 36.dp)
                VSpace(6.dp)
                Text("${if (left) "−" else "+"}$seconds s", style = MaterialTheme.typography.labelLarge, color = OnVideo)
            }
        }
    }
}

/** Ellipse, deren Mitte außerhalb des Bildes liegt: Von innen sieht man nur den Bogen. */
private class ArcShape(private val left: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val rect = if (left) Rect(-w, -h * 0.2f, w, h * 1.2f) else Rect(0f, -h * 0.2f, w * 2, h * 1.2f)
        return Outline.Generic(Path().apply { addOval(rect) })
    }
}

/** Kleine senkrechte Pegelanzeige neben dem Daumen: Helligkeit links, Lautstärke rechts. */
@Composable
private fun BoxScope.LevelIndicator(side: Int, value: Float) {
    // Die Seite merken, damit die Anzeige beim Ausblenden nicht auf die andere Seite springt.
    var shownSide by remember { mutableIntStateOf(-1) }
    if (side != 0) shownSide = side
    val fill by animateFloatAsState(value, tween(DUR_PRESS), label = "level")
    AnimatedVisibility(
        visible = side != 0,
        enter = fadeIn(tween(DUR_FADE)) + scaleIn(tween(DUR_SWAP, easing = Bouncy), initialScale = 0.9f),
        exit = fadeOut(tween(DUR_FADE)) + scaleOut(tween(DUR_FADE), targetScale = 0.95f),
        modifier = Modifier.align(if (shownSide < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 56.dp),
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GIcon(if (shownSide < 0) Ic.brightness else Ic.volume, tint = OnVideo, size = 20.dp)
            VSpace(12.dp)
            Box(Modifier.width(6.dp).height(132.dp).clip(RoundedCornerShape(50)).background(OnVideo.copy(alpha = 0.22f))) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill).clip(RoundedCornerShape(50)).background(OnVideo))
            }
            VSpace(10.dp)
            Text("${(value * 100).roundToInt()}", style = MaterialTheme.typography.labelMedium, color = OnVideo)
        }
    }
}

/**
 * Gesperrt: Das Bild nimmt keine Gesten an (Kinder, Hosentasche). Ein Tipp zeigt nur das Schloss;
 * entsperrt wird mit langem Druck oder Doppeltipp – ein einzelner Zufallstipp reicht nicht.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LockLayer(onUnlock: () -> Unit) {
    var shownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shown by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(shownAt) {
        shown = true
        delay(2_500)
        shown = false
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { shownAt = System.currentTimeMillis() }) },
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(DUR_SWAP)) + slideInVertically(tween(DUR_MOVE, easing = Spring)) { it / 2 },
            exit = fadeOut(tween(DUR_FADE)) + slideOutVertically(tween(DUR_SWAP, easing = Spring)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(OnVideo.copy(alpha = 0.16f))
                        .combinedClickable(
                            onClick = { shownAt = System.currentTimeMillis() },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onUnlock() },
                            onDoubleClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onUnlock() },
                        ),
                    contentAlignment = Alignment.Center,
                ) { GIcon(Ic.lock, tint = OnVideo, size = 26.dp) }
                VSpace(8.dp)
                Text("Zum Entsperren lange drücken", style = MaterialTheme.typography.labelMedium, color = OnVideo.copy(alpha = 0.8f))
            }
        }
    }
}
