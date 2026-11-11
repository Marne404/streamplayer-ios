package de.marne.streamplayer.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.App
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.launch

/** TMDB-ID des Titels, wenn er eine hat und ein Schlüssel eingetragen ist – sonst gibt es nichts zu zeigen. */
fun tmdbCoverSource(t: LibTitle): Pair<Tmdb.Kind, Int>? {
    if (!App.instance.enricher.available) return null
    val id = t.tmdb?.id ?: t.tmdbId ?: return null
    return (if (t.kind == TitleKind.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE) to id
}

/**
 * Cover wählen: alle Poster, die TMDB zu dem Titel hat (Wunschsprache zuerst), und als erste
 * Kachel ein eigenes Bild aus der Galerie. Das gewählte ist markiert; das Standard-Poster
 * wählen heißt: kein eigenes Cover mehr.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSheet(t: LibTitle, source: Pair<Tmdb.Kind, Int>, onPick: (String?) -> Unit, onGallery: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    var paths by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        runCatching { App.instance.tmdb.posters(source.first, source.second) }
            .onSuccess { paths = it }
            .onFailure { failed = it.message ?: tr("TMDB antwortet nicht.", "TMDB isn't responding.") }
    }
    // Was gerade zu sehen ist: eigenes Cover (Pfad oder TMDB-Adresse) oder das Standard-Poster.
    val current = t.cover.orEmpty()
    val default = t.poster ?: t.tmdb?.poster

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.heightIn(max = 720.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(tr("COVER", "COVER"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    VSpace(6.dp)
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                    VSpace(4.dp)
                    Text(
                        when {
                            failed != null -> failed!!
                            paths == null -> tr("Lädt Poster von TMDB …", "Loading posters from TMDB …")
                            paths!!.isEmpty() -> tr("TMDB hat keine weiteren Poster – nimm ein eigenes Bild.", "TMDB has no other posters – use your own image.")
                            else -> tr("${paths!!.size} Poster von TMDB – oder ein eigenes Bild.", "${paths!!.size} posters from TMDB – or your own image.")
                        },
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                    VSpace(8.dp)
                }
            }
            item(key = "gallery") {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Palette.surface2)
                        .clickable { then(onGallery) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GIcon(Ic.image, tint = Palette.text, size = 26.dp)
                        VSpace(8.dp)
                        Text(tr("Eigenes Bild", "Own image"), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                    }
                }
            }
            items(paths.orEmpty(), key = { it }) { path ->
                val url = Tmdb.poster(path)!!
                // Gleiches Bild, andere Größe in der Adresse: am Pfad vergleichen.
                val selected = current.endsWith(path)
                val isDefault = default?.endsWith(path) == true
                PosterChoice(Tmdb.posterSmall(path), selected) { then { onPick(if (isDefault) null else url) } }
            }
        }
    }
}

@Composable
private fun PosterChoice(image: String?, selected: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(320, easing = BouncyEase), label = "ring")
    Box(
        Modifier.fillMaxWidth().aspectRatio(2f / 3f).pressScale(press, 0.94f)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        Thumb(image, Modifier.fillMaxWidth().aspectRatio(2f / 3f), radius = 14.dp)
        if (selected) {
            Box(Modifier.matchParentSizeCompat().border(2.5.dp, Palette.ink, RoundedCornerShape(14.dp)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).graphicsLayer { scaleX = ring; scaleY = ring }
                    .clip(CircleShape).background(Palette.ink).padding(4.dp),
            ) { GIcon(Ic.check, tint = Palette.onInk, size = 14.dp) }
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
