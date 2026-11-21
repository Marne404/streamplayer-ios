package de.marne.streamplayer.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.runtime.collectAsState
import de.marne.streamplayer.App
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.locale
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// ---------- Suchleiste ----------

/**
 * Eigene Suchleiste: dünn in Ruhe, dicker unter dem Finger, mit Zeitblase über dem Daumen.
 * Tippen springt, Ziehen spult; gesprungen wird erst beim Loslassen – sonst lädt der Stream bei
 * jedem Millimeter neu.
 */
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    // Nach dem Loslassen kurz am Ziel festhalten: die gepollte Position hinkt bis zu 250 ms hinterher,
    // und der Daumen spränge sonst einmal zurück.
    var hold by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(hold) { if (hold != null) { delay(700); hold = null } }
    val actual = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = scrub ?: hold ?: actual
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(if (scrub != null) 9.dp else 6.dp, tween(DUR_PRESS), label = "thumb")
    val track by animateDpAsState(if (scrub != null) 6.dp else 4.dp, tween(DUR_PRESS), label = "track")
    var widthPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current

    fun commit(f: Float) {
        hold = f
        onSeek((f * duration).toLong())
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            // Zeitblase: folgt dem Daumen, bleibt aber im Bild.
            scrub?.let { f ->
                var bubbleW by remember { mutableIntStateOf(0) }
                val x = (f * widthPx - bubbleW / 2f).coerceIn(0f, (widthPx - bubbleW).toFloat().coerceAtLeast(0f))
                Text(
                    clock((f * duration).toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Black,
                    modifier = Modifier
                        .onSizeChanged { bubbleW = it.width }
                        .offset { IntOffset(x.roundToInt(), with(density) { (-30).dp.roundToPx() }) }
                        .clip(RoundedCornerShape(50))
                        .background(OnVideo)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(duration) {
                        if (duration <= 0) return@pointerInput
                        detectTapGestures(onTap = { o -> commit((o.x / size.width).coerceIn(0f, 1f)) })
                    }
                    .pointerInput(duration) {
                        if (duration <= 0) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); onScrubbing(true) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = { scrub?.let(::commit); scrub = null; onScrubbing(false) },
                            onDragCancel = { scrub?.let(::commit); scrub = null; onScrubbing(false) },
                        )
                    },
            ) {
                val h = track.toPx()
                val y = size.height / 2 - h / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(OnVideo.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
                drawRoundRect(OnVideo.copy(alpha = 0.40f), Offset(0f, y), Size(size.width * bufferedFraction, h), r)
                drawRoundRect(OnVideo, Offset(0f, y), Size(size.width * fraction, h), r)
                if (duration > 0) drawCircle(OnVideo, thumb.toPx(), Offset(size.width * fraction, size.height / 2))
            }
        }
        Row {
            Text(
                clock(((scrub ?: hold)?.let { (it * duration).toLong() }) ?: position),
                style = MaterialTheme.typography.labelMedium, color = OnVideo.copy(alpha = 0.85f),
            )
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text(
                    "−" + clock(duration - ((scrub ?: hold)?.let { (it * duration).toLong() } ?: position)),
                    style = MaterialTheme.typography.labelMedium, color = OnVideo.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ---------- Nächste Folge ----------

/**
 * Karte „Nächste Folge“ unten rechts. Vor dem Ende ein Angebot; nach dem Ende füllt sich der
 * Knopf 8 s lang und spielt dann von selbst weiter – wie bei Netflix, aber jederzeit abbrechbar.
 */
@Composable
internal fun NextUpCard(
    visible: Boolean,
    title: LibTitle?,
    next: Episode?,
    ended: Boolean,
    controlsShown: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Inhalt festhalten, solange die Karte hinausgleitet – sonst wäre sie beim Ausblenden leer.
    var shown by remember { mutableStateOf(next) }
    if (next != null) shown = next
    // Über der Leiste, solange die Steuerung sichtbar ist; sonst tiefer. Im Querformat bleibt so die
    // Mitte (−10 s, Play, +10 s) frei – die erste, hohe Fassung verdeckte dort „+10 s“.
    val bottom by animateDpAsState(if (controlsShown) 100.dp else 28.dp, tween(DUR_MOVE, easing = Spring), label = "nextBottom")
    AnimatedVisibility(
        visible = visible && title != null,
        enter = fadeIn(tween(DUR_SWAP)) + slideInHorizontally(tween(DUR_MOVE, easing = Bouncy)) { it / 2 },
        exit = fadeOut(tween(DUR_FADE)) + slideOutHorizontally(tween(DUR_SWAP, easing = Spring)) { it / 2 },
        modifier = modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp, bottom = bottom),
    ) {
        val e = shown ?: return@AnimatedVisibility
        val countdown = remember { Animatable(0f) }
        LaunchedEffect(ended) {
            if (!ended) { countdown.snapTo(0f); return@LaunchedEffect }
            // Linear: ein Strich, der in einem Zug gezogen wird – eine Feder würde losschießen und auskriechen.
            countdown.animateTo(1f, tween(NEXT_UP_COUNTDOWN_MS, easing = LinearEasing))
            onPlay()
        }
        // Eine Zeile statt zwei: Bild, Text, Knopf, Schließen – flach genug für das Querformat.
        Row(
            Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Palette.surface.copy(alpha = 0.96f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(Tmdb.largeModel(e.still ?: title?.wide), Modifier.width(96.dp).aspectRatio(16f / 9f), radius = 10.dp)
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("NÄCHSTE FOLGE", "NEXT EPISODE"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                Text(
                    listOfNotNull(e.label, e.title).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            HSpace(8.dp)
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(DUR_PRESS), label = "press")
            Box(
                Modifier.width(124.dp).height(40.dp).scale(scale).clip(RoundedCornerShape(50))
                    .background(Palette.text.copy(alpha = if (ended) 0.35f else 1f))
                    .clickable(interactionSource = source, indication = null, onClick = onPlay),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Nach dem Ende füllt sich der Knopf von links: Countdown ohne Zahl.
                if (ended) Box(Modifier.fillMaxHeight().fillMaxWidth(countdown.value.coerceAtLeast(0.001f)).background(Palette.text))
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    GIcon(Ic.play, tint = Palette.bg, size = 18.dp)
                    HSpace(6.dp)
                    Text(tr("Abspielen", "Play"), style = MaterialTheme.typography.labelLarge, color = Palette.bg)
                }
            }
            Box(
                Modifier.padding(start = 4.dp).size(40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { GIcon(Ic.close, tint = Palette.text, size = 20.dp) }
        }
    }
}

// ---------- Seitenpanel ----------

/** Panel von rechts (Folgen, Geschwindigkeit, Ton). Ein Tipp daneben schließt es. */
@Composable
internal fun SidePanel(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(DUR_SWAP)), exit = fadeOut(tween(DUR_FADE))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = (maxWidth * 0.5f).coerceIn(320.dp, 440.dp).coerceAtMost(maxWidth)
            AnimatedVisibility(
                visible,
                enter = fadeIn(tween(DUR_FADE)) + slideInHorizontally(tween(DUR_MOVE, easing = Spring)) { it },
                exit = fadeOut(tween(DUR_FADE)) + slideOutHorizontally(tween(DUR_SWAP, easing = Spring)) { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    Modifier.width(width).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp))
                        .background(Palette.bg)
                        // Berührungen im Panel nicht zum Schließen durchreichen.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                        .windowInsetsPadding(WindowInsets.displayCutout),
                ) { content() }
            }
        }
    }
}

@Composable
private fun PanelHeader(eyebrow: String, title: String) {
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        VSpace(4.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun EpisodesPanel(t: LibTitle, current: Episode?, onPick: (Episode) -> Unit) {
    val progress by App.instance.progress.all.collectAsStateWithLifecycle()
    val all = remember(t) { t.episodes() }
    val seasons = remember(t) { all.map { it.season }.distinct().sorted() }
    var season by remember { mutableIntStateOf(current?.season ?: seasons.firstOrNull() ?: 1) }
    val list = remember(season, all) { all.filter { it.season == season } }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = list.indexOfFirst { it.season == current?.season && it.number == current.number }.coerceAtLeast(0),
    )
    Column(Modifier.fillMaxSize()) {
        PanelHeader(tr("Folgen", "Episodes"), t.title)
        if (seasons.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { n -> OutlinePill(t.seasonLabel(n), selected = n == season, onClick = { season = n }) }
            }
            VSpace(10.dp)
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
            items(list, key = { "${it.season}:${it.number}" }) { e ->
                val isCurrent = current != null && e.season == current.season && e.number == current.number
                val p = progress[Keys.episode(t, e)]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (isCurrent) Palette.surface2 else Color.Transparent)
                        .clickable { onPick(e) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(124.dp)) {
                        Thumb(Tmdb.largeModel(e.still), Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12.dp)
                        if (p?.finished == true) {
                            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape).background(Palette.ink).padding(3.dp)) {
                                GIcon(Ic.check, tint = Palette.onInk, size = 12.dp)
                            }
                        } else if (p != null && p.positionMs > 0) {
                            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Bar(p.fraction, height = 3.dp, color = Palette.ink, track = Palette.bg.copy(alpha = 0.7f))
                            }
                        }
                    }
                    HSpace(14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title?.let { "${e.number}. $it" } ?: tr("Folge ${e.number}", "Episode ${e.number}"),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val meta = when {
                            isCurrent -> tr("Läuft gerade", "Now playing")
                            else -> listOfNotNull(e.runtimeMin?.let { tr("$it Min.", "$it min") }, if (p?.finished == true) tr("gesehen", "watched") else null).joinToString(" · ")
                        }
                        if (meta.isNotEmpty()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = if (isCurrent) Palette.brass else Palette.muted)
                        }
                    }
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun SpeedPanel(current: Float, onPick: (Float) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader(tr("Wiedergabe", "Playback"), tr("Geschwindigkeit", "Speed"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
            items(SPEEDS) { s ->
                OptionRow(if (s == 1f) "1× · normal" else speedLabel(s), null, selected = s == current, onClick = { onPick(s) })
            }
        }
    }
}

/** Ton und Untertitel aus den Spuren des Streams. [version] steigt, wenn sich die Spuren ändern. */
@Composable
internal fun TracksPanel(player: ExoPlayer, version: Int, onPickSubtitles: () -> Unit = {}) {
    val tracks = remember(version) { player.currentTracks }
    val audio = remember(tracks) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g to it } }
    }
    val text = remember(tracks) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g to it } }
    }
    var tick by remember { mutableIntStateOf(0) }
    val textOff = remember(tracks, tick) { text.none { (g, i) -> g.isTrackSelected(i) } || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(tr("Wiedergabe", "Playback"), tr("Ton & Untertitel", "Audio & subtitles"))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
            item { SectionLabel(tr("Ton", "Audio")) }
            if (audio.size <= 1) {
                item { Hint(if (audio.isEmpty()) tr("Keine Angaben zur Tonspur.", "No audio track info.") else tr("Nur eine Tonspur: ", "Only one audio track: ") + trackLabel(audio.first().first.getTrackFormat(audio.first().second), audio = true)) }
            } else {
                items(audio.size) { n ->
                    val (g, i) = audio[n]
                    OptionRow(trackLabel(g.getTrackFormat(i), audio = true), null, selected = g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                        tick++
                    }
                }
            }
            item { VSpace(10.dp); SectionLabel(tr("Untertitel", "Subtitles")) }
            if (text.isEmpty()) {
                item { Hint(tr("Dieses Video bringt keine Untertitel mit – du kannst eine eigene Datei laden.", "This video has no subtitles – you can load your own file.")) }
            } else {
                item {
                    OptionRow(tr("Aus", "Off"), null, selected = textOff) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        tick++
                    }
                }
                items(text.size) { n ->
                    val (g, i) = text[n]
                    OptionRow(trackLabel(g.getTrackFormat(i), audio = false), null, selected = !textOff && g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                        tick++
                    }
                }
            }
            // Eigene Datei (.srt, .vtt, .ass): bleibt bei diesem Film bzw. dieser Folge gespeichert.
            item { OptionRow(tr("Datei laden …", "Load file …"), tr(".srt, .vtt oder .ass vom Gerät", ".srt, .vtt or .ass from this device"), selected = false, onClick = onPickSubtitles) }
            item {
                val settings = App.instance.settings
                val scale by settings.subtitleScale.collectAsState()
                VSpace(10.dp)
                SectionLabel(tr("Schriftgröße", "Text size"))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f to "S", 1f to "M", 1.3f to "L", 1.6f to "XL").forEach { (f, l) ->
                        val sel = kotlin.math.abs(scale - f) < 0.01f
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Palette.surface2 else Color.Transparent)
                                .clickable { settings.setSubtitleScale(f) },
                            contentAlignment = Alignment.Center,
                        ) { Text(l, style = MaterialTheme.typography.labelLarge, color = if (sel) Palette.text else Palette.muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable
private fun OptionRow(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(DUR_PRESS), label = "press")
    Row(
        Modifier.fillMaxWidth().scale(scale).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.surface2 else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
        }
        if (selected) GIcon(Ic.check, tint = Palette.text, size = 20.dp)
    }
}

/** „Deutsch · 5.1“, „Englisch (Untertitel für Hörgeschädigte)“ – Sprache aus dem Code, sonst das Label des Streams. */
private fun trackLabel(f: Format, audio: Boolean): String {
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Lang.locale).takeIf { it.isNotBlank() && it != code }
            ?.replaceFirstChar { it.uppercase(Lang.locale) }
    }
    val name = f.label?.takeIf { it.isNotBlank() } ?: language ?: tr("Spur ${f.id ?: ""}", "Track ${f.id ?: ""}").trim()
    val extra = buildList {
        if (language != null && f.label != null && !f.label!!.contains(language, ignoreCase = true)) add(language)
        if (audio) when (f.channelCount) {
            1 -> add("Mono"); 2 -> add("Stereo"); 6 -> add("5.1"); 8 -> add("7.1")
        }
        if (!audio && f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add(tr("für Hörgeschädigte", "SDH"))
        if (!audio && f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(tr("erzwungen", "forced"))
    }
    return (listOf(name) + extra).joinToString(" · ")
}
