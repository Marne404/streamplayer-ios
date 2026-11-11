package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.App
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TMDB-Eintrag von Hand wählen: wenn die automatische Suche nichts oder das Falsche fand
 * (anderer Titel auf der Seite, Originaltitel, Anime mit zwei Namen). Gesucht wird beim Tippen,
 * leicht verzögert; ein Tipp auf einen Treffer ordnet zu und lädt Cover, Beschreibung, Folgen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmdbMatchSheet(t: LibTitle, onPick: (Tmdb.Result) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(t.tmdbQuery ?: t.title) }
    var results by remember { mutableStateOf<List<Tmdb.Result>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val kind = if (t.kind == TitleKind.SERIES) Tmdb.Kind.TV else Tmdb.Kind.MOVIE

    LaunchedEffect(query, round) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350) // erst suchen, wenn das Tippen kurz ruht
        error = null
        runCatching { App.instance.tmdb.search(q, kind) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: tr("TMDB antwortet nicht.", "TMDB isn't responding.") }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        LazyColumn(Modifier.heightIn(max = 760.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item {
                Text("TMDB", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(tr("Welcher ist es?", "Which one is it?"), style = MaterialTheme.typography.headlineSmall)
                VSpace(4.dp)
                Text(
                    tr("Suche nach dem Namen – auch Originaltitel oder englischer Name helfen. Gesucht wird unter ${if (kind == Tmdb.Kind.TV) "Serien" else "Filmen"}.",
                        "Search by name – the original or English title helps too. Searching ${if (kind == Tmdb.Kind.TV) "shows" else "movies"}."),
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
                VSpace(14.dp)
                IvoryField(query, { query = it }, tr("Name", "Name"))
                VSpace(12.dp)
                when {
                    error != null -> {
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = Palette.wine)
                        VSpace(8.dp)
                        SecondaryButton(tr("Nochmal", "Retry"), icon = Ic.refresh) { round++ }
                    }
                    results == null -> Text(tr("Sucht …", "Searching …"), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    results!!.isEmpty() && query.trim().length >= 2 -> Text(tr("Nichts gefunden. Anders schreiben?", "Nothing found. Try another spelling?"), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
            }
            items(results.orEmpty(), key = { it.id }) { r ->
                val current = t.tmdb?.id == r.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (current) Palette.surface2 else Palette.bg)
                        .clickable { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); onPick(r) } }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Kleine Fassung des Posters: die Liste soll schnell da sein.
                    Thumb(r.poster?.replace("/w780/", "/w185/"), Modifier.size(width = 56.dp, height = 84.dp), radius = 10.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(r.year?.toString(), r.originalTitle?.takeIf { it != r.title }).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        r.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.faint, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (current) Box(Modifier.padding(start = 6.dp)) { GIcon(Ic.check, tint = Palette.text, size = 20.dp) }
                }
            }
        }
    }
}
