@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package de.marne.streamplayer.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

// ---------- Die eine bewegte Ebene: Cover → Detail ----------

/** Gesetzt von LibraryHost: der Rahmen, in dem Cover und Detailbild dieselbe Ebene sind. */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
/** Gesetzt je Seite: ihr Ein-/Ausblenden treibt den Übergang. */
val LocalPageScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Cover, das beim Öffnen in das Detailbild übergeht – und beim Zurück wieder an seinen Platz.
 * Ein Objekt wandert und wächst, statt dass eine Seite die andere wegschiebt. Der Schlüssel trägt
 * das Regal mit („cover-foryou-<id>“): derselbe Titel steht oft in mehreren Regalen, und zwei
 * gleiche Schlüssel gleichzeitig ergäben zwei fliegende Klone.
 *
 * Größe über Bounds (RemeasureToBounds), nicht über Skalierung: das Bild wird während des Flugs
 * neu zugeschnitten statt verzerrt. Kurve Spring – eine Größenänderung federt nicht.
 */
fun Modifier.sharedCover(key: String?, radius: Dp): Modifier = if (key == null) this else composed {
    val shared = LocalSharedScope.current
    val page = LocalPageScope.current
    if (shared == null || page == null) this
    else with(shared) {
        this@composed.sharedBounds(
            rememberSharedContentState(key),
            page,
            enter = fadeIn(tween(320, easing = SpringEase)),
            exit = fadeOut(tween(220, easing = SpringEase)),
            boundsTransform = { _, _ -> tween(440, easing = SpringEase) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(radius)),
        )
    }
}

fun coverKey(origin: String, id: String) = "cover-$origin-$id"

/**
 * Für Bedienelemente, die über einem Cover-Ziel liegen (Zurück, Stift). Das fliegende Cover wird
 * über allem gezeichnet; ohne das hier lägen die Knöpfe den ganzen Flug darunter und sprängen bei
 * der Landung schlagartig hervor. So liegen sie während des Flugs darüber und blenden erst ein,
 * wenn das Bild fast angekommen ist (Hülle zuerst, Inhalt danach) – und gehen schneller, als sie kamen.
 */
fun Modifier.overFlight(): Modifier = composed {
    val shared = LocalSharedScope.current
    val page = LocalPageScope.current
    if (shared == null || page == null) this
    else with(shared) {
        with(page) {
            this@composed
                .renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                .animateEnterExit(
                    enter = fadeIn(tween(260, delayMillis = 220, easing = SpringEase)),
                    exit = fadeOut(tween(140, easing = SpringEase)),
                )
        }
    }
}

// ---------- Regal-Überschrift ----------

/**
 * Überschrift eines Regals: ruhig, in Satzschreibung, mit einem Pfeil, wenn es mehr gibt.
 * Ersetzt die Versal-Labels plus „ALLE“ – die lasen sich wie Menüpunkte, nicht wie Regale.
 */
@Composable
fun ShelfTitle(name: String, onAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 30.dp, bottom = 12.dp)
            .let { if (onAll != null) it.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAll) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onAll != null) GIcon(Ic.chevron, tint = Palette.muted, size = 20.dp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Abschnitts-Überschrift ohne eigenen Seitenrand – für Spalten, die schon eingerückt sind. */
@Composable
fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 12.dp))
}

// ---------- Kino-Karte ----------

/**
 * Breite Karte (16:9) mit dem Titel auf dem Bild – für Regale, die „Kino“ sein sollen
 * (Für dich, erstes Genre). Antippen öffnet die Detailseite, langes Drücken die Kurzansicht.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CinematicCard(t: LibTitle, origin: String, actions: LibActions, width: Dp = 292.dp) {
    val press = remember { MutableInteractionSource() }
    val ivory = Color(0xFFF4F1EA)
    Box(
        Modifier.width(width).aspectRatio(16f / 9f).pressScale(press, 0.97f)
            .sharedCover(coverKey(origin, t.id), 20.dp)
            .clip(RoundedCornerShape(20.dp)).background(Palette.ink)
            .combinedClickable(interactionSource = press, indication = null, onClick = { actions.show(t, coverKey(origin, t.id)) }, onLongClick = { actions.preview(t) }),
    ) {
        Thumb(Tmdb.largeModel(t.wide ?: t.cover), Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            val g = genreLine(t, 2)
            if (g.isNotEmpty()) Text(g.uppercase(), style = MaterialTheme.typography.labelSmall, color = ivory.copy(alpha = 0.8f), maxLines = 1)
            Text(
                t.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Serif), color = ivory,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        t.rating?.let {
            Text(
                ratingText(it), style = MaterialTheme.typography.labelMedium, color = ivory,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ---------- Bühne der Startseite ----------

/**
 * Die Bühne oben auf der Startseite: bis zu sechs Empfehlungen als randlose Breitbilder zum
 * Durchwischen. Das aktuelle Bild zoomt ganz langsam (Kamerafahrt), der Text blendet beim Wischen
 * mit. Alle 8 s weiter, solange niemand wischt.
 * Text und Knöpfe stehen auf dem Verlauf in den Grund – in Hell wie in Dunkel lesbar.
 */
@Composable
fun HeroStage(items: List<LibTitle>, state: LibState, actions: LibActions, topInset: Dp) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.64f).dp.coerceIn(440.dp, 640.dp)
    if (items.isEmpty()) { Box(Modifier.height(topInset)); return }
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress, items.size) {
        if (pager.isScrollInProgress || items.size < 2) return@LaunchedEffect
        delay(8_000)
        pager.animateScrollToPage((pager.currentPage + 1) % items.size, animationSpec = tween(900, easing = SpringEase))
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            HeroPage(items[page], page, pager, state, actions)
        }
        if (items.size > 1) {
            Row(
                // Mittig ganz unten, unter den Knöpfen – rechts lagen sie auf dem Info-Knopf.
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.indices.forEach { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 18.dp else 6.dp, tween(320, easing = BouncyEase), label = "dot")
                    Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) Palette.text else Palette.text.copy(alpha = 0.25f)))
                }
            }
        }
    }
}

@Composable
private fun HeroPage(t: LibTitle, page: Int, pager: PagerState, state: LibState, actions: LibActions) {
    val current = pager.currentPage == page
    // Kamerafahrt: nur das sichtbare Bild bewegt sich – sonst zoomten alle sechs unsichtbar mit.
    val zoom by animateFloatAsState(if (current) 1.09f else 1.0f, tween(if (current) 9_000 else 600, easing = SpringEase), label = "kenburns")
    val key = coverKey("hero", t.id)
    // clipToBounds: der Pager beschneidet seine Seiten nicht – ohne zeichnete der Zoom (und eine erste
    // Parallaxe) das Bild der Nachbarseite über Titel und Knöpfe der aktuellen.
    Box(Modifier.fillMaxSize().clipToBounds().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.show(t, key) }) {
        // Alles auf dem Bild – Verlauf, Text, Knöpfe – steckt in der fliegenden Ebene: Beim Zurück
        // landet es mit dem Bild und blendet darin auf, statt nach der Landung hervorzuspringen.
        Box(Modifier.fillMaxSize().sharedCover(key, 0.dp)) {
            Thumb(
                Tmdb.largeModel(t.wide ?: t.cover),
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                radius = 0.dp,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.bg.copy(alpha = 0.7f), 0.22f to Color.Transparent, 0.5f to Color.Transparent, 0.82f to Palette.bg.copy(alpha = 0.92f), 1f to Palette.bg)))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp)
                    .graphicsLayer {
                        // Der Text weicht beim Wischen etwas schneller aus als das Bild – er gehört zur Seite.
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        alpha = 1f - offset.absoluteValue.coerceIn(0f, 1f)
                    },
            ) {
                val g = genreLine(t, 3)
                if (g.isNotEmpty()) Text(g.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.brass, maxLines = 1)
                VSpace(6.dp)
                Text(t.title, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = factLine(t)
                if (facts.isNotEmpty()) {
                    VSpace(4.dp)
                    Text(facts, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
                }
                VSpace(18.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StageButton(playLabel(t, state.progress), Ic.play, primary = true, modifier = Modifier.weight(1f)) { actions.play(t, null) }
                    StageButton(tr("Meine Liste", "My List"), if (state.inList(t)) Ic.check else Ic.add, primary = false, modifier = Modifier.weight(1f)) { actions.toggleList(t) }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Palette.surface2).clickable { actions.preview(t) },
                        contentAlignment = Alignment.Center,
                    ) { GIcon(Ic.info, size = 22.dp) }
                }
            }
        }
    }
}

/** Knopf der Bühne: gefüllt (ink) für Abspielen, Fläche für den Rest. */
@Composable
fun StageButton(text: String, icon: Int, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.height(48.dp).pressScale(press, 0.97f).clip(RoundedCornerShape(50))
            .background(if (primary) Palette.ink else Palette.surface2)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon(icon, tint = if (primary) Palette.onInk else Palette.text, size = 20.dp)
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = if (primary) Palette.onInk else Palette.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
        )
    }
}
