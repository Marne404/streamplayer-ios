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

/** Textfeld in Ivory & Ink: radius-field, Haarlinie, Fokus in text. */
@Composable
fun IvoryField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    type: KeyboardType = KeyboardType.Text,
    label: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        label = label?.let { { Text(it) } },
        placeholder = { Text(placeholder, color = Palette.muted) },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.text,
            unfocusedBorderColor = Palette.line,
            focusedLabelColor = Palette.text,
            unfocusedLabelColor = Palette.muted,
            cursorColor = Palette.text,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text,
            focusedContainerColor = Palette.surface,
            unfocusedContainerColor = Palette.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Metazeile eines Titels: „Serie · 3 Staffeln · 30 Folgen“ / „Film · 1:42:10 · voe.sx“ */
fun titleMeta(t: LibTitle): String = buildList {
    if (t.kind == TitleKind.SERIES) {
        add(if (t.seasons.size == 1) "1 Staffel" else "${t.seasons.size} Staffeln")
        add("${t.episodeCount} Folgen")
    } else {
        t.durationMs?.let { add(Format.duration(it)) }
        if (t.offline.isNotEmpty()) add("offline")
    }
    t.host?.let { add(it) }
}.joinToString(" · ")
