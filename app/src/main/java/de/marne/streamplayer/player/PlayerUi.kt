package de.marne.streamplayer.player

import org.jetbrains.compose.resources.DrawableResource

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.locale
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay

// Das Vokabular der Bewegungssprache: zwei Kurven, vier Dauern. Hin lang, zurück eine Stufe kürzer.
internal val Spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
internal val Bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
internal const val DUR_PRESS = 120
internal const val DUR_FADE = 220
internal const val DUR_SWAP = 320
internal const val DUR_MOVE = 420

/** Auf schwarzem Bild: Weiß des Themes mit Abstufungen statt neuer Grautöne. */
internal val OnVideo = Color(0xFFF4F1EA)

/** So lange ohne Berührung, dann zieht sich die Steuerung zurück. */
private const val HIDE_AFTER_MS = 3_500L

/** In den letzten 40 Sekunden einer Folge steht die nächste bereit; nach dem Ende zählt sie 8 s herunter. */
internal const val NEXT_UP_BEFORE_END_MS = 40_000L
internal const val NEXT_UP_COUNTDOWN_MS = 8_000

internal enum class Panel { Episodes, Speed, Tracks }

/** Zustand des Players, für Compose lesbar. Position wird gepollt – ExoPlayer meldet sie nicht. */
internal class PlayState {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var error by mutableStateOf<PlaybackException?>(null)
    var speed by mutableFloatStateOf(1f)
    /** Zähler, der bei jeder Änderung der Spuren steigt – Panels lesen danach player.currentTracks neu. */
    var tracksVersion by mutableIntStateOf(0)
}

@Composable
fun PlayerScreen(
    player: ExoPlayer,
    info: PlaybackInfo,
    /** Läuft als kleines Fenster (Bild im Bild): nur das Bild, keine Steuerung – tippen kann man da nicht. */
    pip: Boolean = false,
    onBack: () -> Unit,
    onPlayEpisode: (LibTitle, Episode) -> Unit,
    /** Eigene Untertitel-Datei wählen (Dateiwähler der Activity). */
    onPickSubtitles: () -> Unit = {},
) {
    val state = remember { PlayState() }
    val subScale by de.marne.streamplayer.App.instance.settings.subtitleScale.collectAsState()
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
            override fun onPlaybackStateChanged(s: Int) {
                state.buffering = s == Player.STATE_BUFFERING || s == Player.STATE_IDLE && state.error == null
                state.ended = s == Player.STATE_ENDED
                if (s == Player.STATE_READY) state.error = null
            }
            override fun onPlayerError(error: PlaybackException) {
                state.error = error
                state.buffering = false
            }
            override fun onTracksChanged(tracks: Tracks) { state.tracksVersion++ }
            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) { state.speed = p.speed }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state.position = player.currentPosition.coerceAtLeast(0)
            state.duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            state.buffered = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrubbing by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var nextDismissed by remember { mutableStateOf(false) }

    fun touch() { touchedAt = System.currentTimeMillis() }
    fun showHint(text: String) { hint = text; hintAt = System.currentTimeMillis() }

    // Steuerung zieht sich zurück – aber nicht, solange pausiert, gezogen oder ein Panel offen ist:
    // Wer gerade etwas tut, soll es nicht unter dem Finger verschwinden sehen.
    LaunchedEffect(controls, touchedAt, scrubbing, state.playing, panel) {
        if (controls && state.playing && !scrubbing && panel == null) {
            delay(HIDE_AFTER_MS)
            controls = false
        }
    }
    LaunchedEffect(hintAt) {
        if (hint != null) { delay(1_400); hint = null }
    }
    // Zu Ende ohne nächste Folge (oder abgelehnt): Steuerung zeigen, damit man nicht vor Schwarz sitzt.
    LaunchedEffect(state.ended) { if (state.ended) controls = true }
    // Ins kleine Fenster: Panel zu. Zurück im Vollbild: Steuerung zeigen (blendet sich beim Abspielen
    // von selbst aus) – sonst stünde ein im Fenster pausiertes Video ohne Play-Knopf da.
    LaunchedEffect(pip) { panel = null; controls = !pip; touch() }

    BackHandler(enabled = panel != null || locked) {
        when {
            panel != null -> panel = null
            locked -> showHint(tr("Zum Entsperren das Schloss lange drücken", "Press and hold the lock to unlock"))
        }
    }

    val nextUp = info.next?.takeIf { info.libTitle != null }
    val nextVisible = nextUp != null && !nextDismissed && state.error == null && state.duration > 0 &&
        (state.ended || state.duration - state.position <= NEXT_UP_BEFORE_END_MS) && panel == null && !locked

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setBackgroundColor(AndroidColor.BLACK)
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                }
            },
            update = {
                // Im Fenster immer ganz zeigen: Es hat schon das Seitenverhältnis des Videos.
                it.resizeMode = if (pip) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
                it.subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subScale)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!pip) {
            if (locked) {
                LockLayer(onUnlock = { locked = false; controls = true; touch() })
            } else {
                GestureLayer(
                    player = player,
                    enabled = panel == null && state.error == null,
                    onTap = { controls = !controls; touch() },
                )
            }

            // Puffern ohne sichtbare Steuerung: nur der Kreis, sonst sitzt er im Play-Knopf.
            AnimatedVisibility(
                visible = state.buffering && !controls && state.error == null,
                enter = fadeIn(tween(DUR_FADE, delayMillis = 300)),
                exit = fadeOut(tween(DUR_FADE)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(Modifier.size(44.dp), color = OnVideo, strokeWidth = 2.5.dp, trackColor = OnVideo.copy(alpha = 0.15f))
            }

            Controls(
                visible = controls && !locked && panel == null && state.error == null,
                player = player,
                info = info,
                state = state,
                resizeMode = resizeMode,
                onBack = onBack,
                onTouch = ::touch,
                onScrubbing = { scrubbing = it; touch() },
                onLock = { locked = true; controls = false; showHint(tr("Bildschirm gesperrt", "Screen locked")) },
                onResize = {
                    resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    showHint(if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) tr("Füllen", "Fill") else tr("Anpassen", "Fit"))
                    touch()
                },
                onPanel = { panel = it },
                onNext = { nextUp?.let { onPlayEpisode(info.libTitle!!, it) } },
            )

            NextUpCard(
                visible = nextVisible,
                title = info.libTitle,
                next = nextUp,
                ended = state.ended,
                controlsShown = controls,
                onPlay = { nextUp?.let { onPlayEpisode(info.libTitle!!, it) } },
                onCancel = { nextDismissed = true; controls = true },
                modifier = Modifier.align(Alignment.BottomEnd),
            )

            // Kurze Rückmeldung in der Mitte: „Füllen“, „Bildschirm gesperrt“.
            AnimatedContent(
                targetState = hint,
                transitionSpec = {
                    (fadeIn(tween(DUR_FADE)) + scaleIn(tween(DUR_SWAP, easing = Bouncy), initialScale = 0.9f)) togetherWith
                        fadeOut(tween(DUR_FADE))
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                label = "hint",
            ) { h ->
                if (h != null) {
                    Text(
                        h,
                        style = MaterialTheme.typography.labelLarge,
                        color = OnVideo,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                } else Spacer(Modifier.size(1.dp))
            }

            state.error?.let { e ->
                ErrorCard(e, onClose = onBack, modifier = Modifier.align(Alignment.Center))
            }

            // Beim Hinausgleiten den Inhalt behalten, sonst schöbe sich ein leeres Panel weg.
            var shownPanel by remember { mutableStateOf<Panel?>(null) }
            if (panel != null) shownPanel = panel
            SidePanel(visible = panel != null, onDismiss = { panel = null }) {
                when (shownPanel) {
                    Panel.Episodes -> info.libTitle?.let { t ->
                        EpisodesPanel(t, info.episode, onPick = { e ->
                            if (e == info.episode) panel = null else onPlayEpisode(t, e)
                        })
                    }
                    Panel.Speed -> SpeedPanel(state.speed, onPick = { player.setPlaybackSpeed(it); panel = null })
                    Panel.Tracks -> TracksPanel(player, state.tracksVersion, onPickSubtitles)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun Controls(
    visible: Boolean,
    player: ExoPlayer,
    info: PlaybackInfo,
    state: PlayState,
    resizeMode: Int,
    onBack: () -> Unit,
    onTouch: () -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onLock: () -> Unit,
    onResize: () -> Unit,
    onPanel: (Panel) -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Verläufe oben und unten: Schrift bleibt auf hellen Bildern lesbar, ohne Kasten.
        AnimatedVisibility(visible, enter = fadeIn(tween(DUR_SWAP)), exit = fadeOut(tween(DUR_FADE))) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                Box(
                    Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                )
            }
        }

        // Oben: kommt von oben, geht nach oben.
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(DUR_SWAP)) + slideInVertically(tween(DUR_MOVE, easing = Spring)) { -it / 3 },
            exit = fadeOut(tween(DUR_FADE)) + slideOutVertically(tween(DUR_SWAP, easing = Spring)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Ic.back, onClick = onBack)
                HSpace(8.dp)
                Column(Modifier.weight(1f)) {
                    Text(info.title, style = MaterialTheme.typography.headlineSmall, color = OnVideo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    info.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OnVideo.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundIcon(Ic.subtitles, onClick = { onPanel(Panel.Tracks) })
                HSpace(4.dp)
                if (state.speed != 1f) {
                    LabelButton(Ic.speed, speedLabel(state.speed), onClick = { onPanel(Panel.Speed) })
                } else RoundIcon(Ic.speed, onClick = { onPanel(Panel.Speed) })
            }
        }

        // Mitte: wächst aus sich heraus (Bouncy) – das Wichtigste kommt mit Nachdruck.
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(DUR_SWAP)) + scaleIn(tween(DUR_MOVE, easing = Bouncy), initialScale = 0.85f),
            exit = fadeOut(tween(DUR_FADE)) + scaleOut(tween(DUR_FADE, easing = Spring), targetScale = 0.92f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                RoundIcon(Ic.replay10, size = 56.dp, iconSize = 34.dp, onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); onTouch() })
                PlayPauseButton(
                    playing = state.playing || (player.playWhenReady && state.buffering),
                    buffering = state.buffering,
                    onClick = {
                        when {
                            state.ended -> { player.seekTo(0); player.play() }
                            player.playWhenReady -> player.pause()
                            else -> player.play()
                        }
                        onTouch()
                    },
                )
                RoundIcon(Ic.forward10, size = 56.dp, iconSize = 34.dp, onClick = { player.seekTo(player.currentPosition + 10_000); onTouch() })
            }
        }

        // Unten: kommt von unten.
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(DUR_SWAP)) + slideInVertically(tween(DUR_MOVE, easing = Spring)) { it / 3 },
            exit = fadeOut(tween(DUR_FADE)) + slideOutVertically(tween(DUR_SWAP, easing = Spring)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
                SeekBar(
                    position = state.position,
                    duration = state.duration,
                    buffered = state.buffered,
                    onScrubbing = onScrubbing,
                    onSeek = { player.seekTo(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelButton(Ic.lock, tr("Sperren", "Lock"), onClick = onLock)
                    LabelButton(
                        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Ic.cropFree else Ic.fitScreen,
                        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) tr("Füllen", "Fill") else tr("Anpassen", "Fit"),
                        onClick = onResize,
                    )
                    Spacer(Modifier.weight(1f))
                    if (info.libTitle != null && info.episode != null) {
                        LabelButton(Ic.episodes, tr("Folgen", "Episodes"), onClick = { onPanel(Panel.Episodes) })
                        if (info.next != null) LabelButton(Ic.skipNext, tr("Nächste Folge", "Next episode"), onClick = onNext)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(DUR_PRESS), label = "press")
    Box(
        Modifier.size(84.dp).scale(scale).clip(CircleShape).background(OnVideo.copy(alpha = 0.14f))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(Modifier.size(84.dp), color = OnVideo.copy(alpha = 0.8f), strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
        // Play ↔ Pause: wechselt mit kurzem Federn, nicht hart.
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (fadeIn(tween(DUR_FADE)) + scaleIn(tween(DUR_SWAP, easing = Bouncy), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(DUR_PRESS)) + scaleOut(tween(DUR_PRESS), targetScale = 0.6f))
            },
            label = "playpause",
        ) { p ->
            GIcon(if (p) Ic.pause else Ic.play, tint = OnVideo, size = 46.dp)
        }
    }
}

/** Runder Symbolknopf auf dem Video: drückt sich bei Berührung leicht ein. */
@Composable
internal fun RoundIcon(icon: DrawableResource, size: androidx.compose.ui.unit.Dp = 44.dp, iconSize: androidx.compose.ui.unit.Dp = 24.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(DUR_PRESS), label = "press")
    Box(
        Modifier.size(size).scale(scale).clip(CircleShape)
            .background(if (pressed) OnVideo.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GIcon(icon, tint = OnVideo, size = iconSize) }
}

/** Symbol mit Wort daneben, für die untere Leiste („Sperren“, „Folgen“). */
@Composable
internal fun LabelButton(icon: DrawableResource, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(DUR_PRESS), label = "press")
    Row(
        Modifier.scale(scale).clip(RoundedCornerShape(50))
            .background(if (pressed) OnVideo.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon(icon, tint = OnVideo, size = 20.dp)
        HSpace(8.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = OnVideo)
    }
}

@Composable
private fun ErrorCard(e: PlaybackException, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp).width(380.dp).clip(RoundedCornerShape(26.dp)).background(Palette.surface).padding(24.dp),
    ) {
        GIcon(Ic.error, tint = Palette.wine, size = 26.dp)
        VSpace(12.dp)
        Text(tr("Das Video lässt sich nicht abspielen.", "This video can't be played."), style = MaterialTheme.typography.headlineSmall)
        VSpace(6.dp)
        Text(
            listOfNotNull(e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '), e.cause?.message ?: e.message)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        VSpace(14.dp)
        QuietButton(tr("Schließen", "Close"), modifier = Modifier.align(Alignment.End), onClick = onClose)
    }
}

internal fun speedLabel(s: Float): String =
    (if (s % 1f == 0f) "%.0f".format(Lang.locale, s) else "%s".format(Lang.locale, if (Lang.isGerman) s.toString().replace('.', ',') else s.toString())) + "×"

/** „1:02:03“ bzw. „4:05“. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(Lang.locale, h, m, sec) else "%d:%02d".format(Lang.locale, m, sec)
}
