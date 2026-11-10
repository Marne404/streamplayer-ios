package de.marne.streamplayer.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

/** „noch 12 Min.“ */
fun remaining(p: Progress): String {
    val min = (p.remainingMs / 60_000).coerceAtLeast(1)
    return if (min >= 60) "noch ${min / 60} Std. ${min % 60} Min." else "noch $min Min."
}

/** Metazeile eines Weiterschauen-Eintrags: „S1 · F4 · noch 12 Min.“ / „Nächste Folge · S1 · F5“ */
fun continueMeta(c: ContinueItem): String = buildList {
    if (c.isNextUp) add("Nächste Folge")
    c.episode?.let { add(it.label) }
    c.progress?.takeIf { !it.finished && it.positionMs > 0 }?.let { add(remaining(it)) }
}.joinToString(" · ")

/**
 * Kachel 16:9 mit Titel und Metazeile darunter, optional Fortschritt im Bild.
 * Fläche und Haarlinie statt Schatten; der Text steht frei darunter – ruhig wie ein Regal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WideTile(
    image: Any?,
    title: String,
    meta: String?,
    width: Dp,
    progress: Float? = null,
    @DrawableRes badgeIcon: Int? = null,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        // Nicht die ganze Kachel rund beschneiden: die Ecke fräße das erste Zeichen der Metazeile.
        Modifier
            .width(width)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            Thumb(image, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 22.dp)
            if (badge != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(50)).background(Palette.bg.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (badgeIcon != null) {
                        GIcon(badgeIcon, tint = Palette.text, size = 13.dp)
                        HSpace(4.dp)
                    }
                    Text(badge, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Bar(progress, height = 4.dp, color = Palette.ink, track = Palette.bg.copy(alpha = 0.85f))
                }
            }
        }
        VSpace(8.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        if (!meta.isNullOrBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Hochformat-Kachel (2:3) wie in Jellyfin/Netflix: Cover, darunter Titel und Jahr. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterTile(
    image: Any?,
    title: String,
    meta: String?,
    width: Dp = 124.dp,
    progress: Float? = null,
    watched: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width).combinedClickable(onClick = onClick)) {
        Box {
            Thumb(image, Modifier.fillMaxWidth().aspectRatio(2f / 3f), radius = 18.dp)
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Palette.ink).padding(4.dp)
                ) { GIcon(Ic.check, tint = Palette.onInk, size = 14.dp) }
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Bar(progress, height = 4.dp, color = Palette.ink, track = Palette.bg.copy(alpha = 0.85f))
                }
            }
        }
        VSpace(8.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        if (!meta.isNullOrBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/**
 * Die eine dunkle Karte pro Screen (Ivory & Ink): großes Bild, Titel, ein Knopf.
 * Für „Weiterschauen“ oder den neuesten Titel.
 */
@Composable
fun HeroCard(
    image: Any?,
    eyebrow: String,
    title: String,
    meta: String?,
    progress: Float?,
    button: String,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    GCard(tone = "ink", padding = 0.dp, radius = 28.dp, onClick = onOpen) {
        Column {
            Thumb(image, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 0.dp)
            Column(Modifier.padding(22.dp)) {
                Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.onInk.copy(alpha = 0.7f))
                VSpace(6.dp)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.onInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!meta.isNullOrBlank()) {
                    VSpace(2.dp)
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.onInk.copy(alpha = 0.7f), maxLines = 1)
                }
                if (progress != null && progress > 0f) {
                    VSpace(14.dp)
                    Bar(progress, height = 4.dp, color = Palette.onInk, track = Palette.onInk.copy(alpha = 0.2f))
                }
                VSpace(18.dp)
                Button(
                    onClick = onPlay,
                    shapes = ButtonDefaults.shapesFor(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.onInk, contentColor = Palette.ink),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    GIcon(Ic.play, tint = Palette.ink, size = 20.dp)
                    HSpace(10.dp)
                    Text(button, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
                }
            }
        }
    }
}

/** Metazeile eines Titels: „Serie · 3 Staffeln · 30 Folgen“ / „Film · 1:42:10 · voe.sx“ */
fun titleMeta(t: LibTitle, withHost: Boolean = true): String = buildList {
    t.shownYear?.let { add(it.toString()) }
    if (t.kind == TitleKind.SERIES) {
        val real = t.seasons.count { it.number > 0 }
        if (real > 0) add(if (real == 1) "1 Staffel" else "$real Staffeln")
        if (t.episodeCount > 0) add("${t.episodeCount} Folgen")
    } else {
        t.runtimeMs?.let { add(Format.duration(it)) }
        if (t.offline.isNotEmpty()) add("offline")
    }
    if (withHost) t.host?.let { add(it) }
}.joinToString(" · ")

/** Kurz für Poster-Kacheln: „2008 · 5 Staffeln“ / „2010 · 2:28:00“ */
fun shortMeta(t: LibTitle): String = titleMeta(t, withHost = false).split(" · ").take(2).joinToString(" · ")
