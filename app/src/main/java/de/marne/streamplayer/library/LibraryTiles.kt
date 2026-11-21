package de.marne.streamplayer.library

import org.jetbrains.compose.resources.DrawableResource

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.locale
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.tr

internal val SpringEase = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
internal val BouncyEase = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Druck unter dem Finger: je kleiner das Element, desto tiefer (Karte 0,96, breite Fläche 0,97). */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val down by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) pressed else 1f, tween(120, easing = SpringEase), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** „★ 7,9“ – Komma bzw. Punkt je nach Sprache, eine Stelle. */
fun ratingText(r: Double): String = "★ " + String.format(Lang.locale, "%.1f", r)

/** „Krimi · Drama · Thriller“ */
fun genreLine(t: LibTitle, max: Int = 3): String = t.genres.take(max).joinToString(" · ")

/** Kurzzeile unter dem Titel: Jahr, Bewertung, Staffeln oder Laufzeit. */
fun factLine(t: LibTitle): String = buildList {
    t.shownYear?.let { add(it.toString()) }
    t.rating?.let { add(ratingText(it)) }
    if (t.kind == TitleKind.SERIES) {
        val real = t.seasons.count { it.number > 0 }
        if (real > 0) add(if (real == 1) tr("1 Staffel", "1 season") else tr("$real Staffeln", "$real seasons"))
    } else t.runtimeMs?.let { minutesText(it) }?.let(::add)
}.joinToString(" · ")

/** „1 Std. 42 Min.“ statt „1:42:10“ – so stehen Laufzeiten in einer Mediathek. */
fun minutesText(ms: Long): String? {
    val min = (ms / 60_000).toInt()
    if (min <= 0) return null
    return if (min >= 60) tr("${min / 60} Std. ${min % 60} Min.", "${min / 60} h ${min % 60} min") else tr("$min Min.", "$min min")
}

/** Neu = in den letzten sieben Tagen dazugekommen und noch nicht angefangen. */
fun isNew(t: LibTitle, progress: Map<String, Progress>): Boolean =
    System.currentTimeMillis() - t.addedAt < 7L * 24 * 3600 * 1000 && Recommend.seen(t, progress) == Recommend.Seen.NONE

/** Poster mit allem, was aus dem Zustand folgt: Fortschritt (Film), Haken (fertig), „NEU“. */
@Composable
fun TitlePoster(t: LibTitle, state: LibState, actions: LibActions, width: Dp = 116.dp, showMeta: Boolean = true, origin: String = "x", showNew: Boolean = true) {
    val seen = Recommend.seen(t, state.progress)
    val p = if (t.kind == TitleKind.MOVIE) state.progress[Keys.movie(t)] else null
    PosterTile(
        image = t.cover ?: t.offline.values.firstOrNull(),
        title = t.title,
        meta = if (showMeta) shortMeta(t) else null,
        width = width,
        progress = p?.takeIf { !it.finished }?.fraction,
        watched = seen == Recommend.Seen.DONE,
        offline = t.offline.isNotEmpty(),
        // In „Neu in der Bibliothek“ sagt das schon die Überschrift – nicht auf jedem Cover noch einmal.
        badge = if (showNew && isNew(t, state.progress)) tr("NEU", "NEW") else null,
        // Antippen öffnet die Seite (das Cover fliegt hinein), langes Drücken die Kurzansicht.
        sharedKey = coverKey(origin, t.id),
        onLongClick = { actions.preview(t) },
        onClick = { actions.show(t, coverKey(origin, t.id)) },
    )
}

/**
 * Platz in den Top 10: große Ziffer in Umriss hinter dem Poster, wie bei Netflix – aber in der
 * Serifenschrift des Designs. Die Ziffer steht links und wird vom Poster überlappt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopTenTile(rank: Int, t: LibTitle, onClick: () -> Unit, onLongClick: () -> Unit, sharedKey: String? = null) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.width(if (rank >= 10) 196.dp else 168.dp).pressScale(press, 0.96f)
            .combinedClickable(interactionSource = press, indication = null, onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 132.sp, lineHeight = 132.sp, letterSpacing = (-8).sp,
                drawStyle = Stroke(width = 5f),
            ),
            color = Palette.muted,
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 22.dp),
        )
        Thumb(
            Tmdb.largeModel(t.cover ?: t.offline.values.firstOrNull()),
            Modifier.align(Alignment.CenterEnd).width(108.dp).aspectRatio(2f / 3f).sharedCover(sharedKey, 16.dp),
            radius = 16.dp,
        )
    }
}

/** Kategorie-Karte: Bild des besten Titels im Genre, Name in Serif darauf. */
@Composable
fun GenreCard(name: String, count: Int, image: Any?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.aspectRatio(16f / 10f).pressScale(press, 0.97f).clip(RoundedCornerShape(20.dp))
            .background(Palette.ink).clickable(interactionSource = press, indication = null, onClick = onClick)
    ) {
        if (image != null) Thumb(Tmdb.largeModel(image), Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = 0.78f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif), color = Color(0xFFF4F1EA), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (count == 1) tr("1 Titel", "1 title") else tr("$count Titel", "$count titles"), style = MaterialTheme.typography.labelMedium, color = Color(0xFFF4F1EA).copy(alpha = 0.75f))
        }
    }
}

/** Alle / Serien / Filme. Ausgewählt: ink; daneben bei aktivem Filter ein runder Knopf zum Zurücksetzen. */
@Composable
fun KindChips(selected: TitleKind?, onSelect: (TitleKind?) -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(selected != null) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Palette.surface2).clickable { onSelect(null) },
                contentAlignment = Alignment.Center,
            ) { GIcon(Ic.close, size = 16.dp) }
        }
        OutlinePill(tr("Serien", "Shows"), selected = selected == TitleKind.SERIES, onClick = { onSelect(if (selected == TitleKind.SERIES) null else TitleKind.SERIES) })
        OutlinePill(tr("Filme", "Movies"), selected = selected == TitleKind.MOVIE, onClick = { onSelect(if (selected == TitleKind.MOVIE) null else TitleKind.MOVIE) })
        trailing()
    }
}

/** Ein Regal: Überschrift (mit „Alle“, wenn es mehr gibt) und eine waagrechte Reihe. */
fun LazyListScope.shelf(
    name: String,
    key: String = name,
    onAll: (() -> Unit)? = null,
    spacing: Dp = 12.dp,
    content: LazyListScope.() -> Unit,
) {
    item(key = "shelf-$key") {
        Column(Modifier.animateItem()) {
            ShelfTitle(name, onAll)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

/** Runder Aktionsknopf mit Beschriftung darunter (Meine Liste, Gesehen, Laden) – wie bei Netflix. */
@Composable
fun ActionIcon(icon: DrawableResource, label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(press, 0.92f).clip(RoundedCornerShape(16.dp)).clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (active) Palette.ink else Palette.surface2),
            contentAlignment = Alignment.Center,
        ) { GIcon(icon, tint = if (active) Palette.onInk else Palette.text, size = 20.dp) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** Gefüllter Knopf auf Bildern (Hero): hell auf dunkel, unabhängig vom Theme. */
@Composable
fun ImageButton(text: String, icon: DrawableResource, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val ivory = Color(0xFFF4F1EA)
    Row(
        modifier.height(46.dp).pressScale(press, 0.97f).clip(RoundedCornerShape(50))
            .background(if (primary) ivory else Color.White.copy(alpha = 0.18f))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon(icon, tint = if (primary) Color(0xFF1A1917) else ivory, size = 20.dp)
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = if (primary) Color(0xFF1A1917) else ivory,
            modifier = Modifier.padding(start = 8.dp), maxLines = 1,
        )
    }
}
