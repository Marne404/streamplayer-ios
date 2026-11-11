package de.marne.streamplayer.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.VSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lädt eine Ansicht und hält das Ergebnis, auch wenn man den Tab wechselt (die Loader leben in
 * AdminRoot). Ein Fehler beim Aktualisieren lässt die alten Zahlen stehen und sagt nur, was schiefging.
 */
class Loader<T>(private val scope: CoroutineScope, private val fetch: suspend () -> T) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Wann die angezeigten Zahlen geholt wurden (ms). */
    var loadedAt by mutableStateOf(0L)
        private set
    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        loading = true
        job = scope.launch {
            try {
                data = fetch()
                loadedAt = System.currentTimeMillis()
                error = null
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    /** Beim ersten Anzeigen laden, danach nur auf Wunsch. */
    fun ensure() { if (data == null && !loading) refresh() }
}

/**
 * Eine Seite: Liste mit Ziehen-zum-Aktualisieren, Kopf, und je nach Zustand Ladekreis, Fehlerkarte
 * oder Inhalt. [content] bekommt die geladenen Daten.
 */
@Composable
fun <T> Page(
    loader: Loader<T>,
    header: @Composable () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 32.dp,
    content: LazyListScope.(T) -> Unit,
) {
    LaunchedEffect(loader) { loader.ensure() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val state = rememberPullToRefreshState()
    val data = loader.data
    PullToRefreshBox(
        isRefreshing = loader.loading && data != null,
        onRefresh = { loader.refresh() },
        state = state,
        modifier = Modifier.fillMaxSize().background(Palette.bg),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = loader.loading && data != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
                containerColor = Palette.surface,
                color = Palette.ink,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = bottomPadding),
        ) {
            item { header() }
            val err = loader.error
            when {
                data == null && err != null -> item {
                    VSpace(28.dp)
                    NoticeCard(Ic.error, "Nicht geladen", err, action = "Nochmal", onAction = { loader.refresh() })
                }
                data == null -> item {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.ink, trackColor = Palette.surface2, modifier = Modifier.size(32.dp))
                    }
                }
                else -> {
                    if (err != null) item { VSpace(16.dp); StatusLine(Ic.error, err) }
                    content(data)
                }
            }
        }
    }
}

/** Untertitel „Stand 14:32“ bzw. „Lädt …“. */
fun stand(loader: Loader<*>): String = when {
    loader.loading -> "Lädt …"
    loader.loadedAt > 0 -> "Stand ${clock(loader.loadedAt)}"
    else -> " "
}

/** Knopf zum Aktualisieren für den Seitenkopf – für alle, die nicht ziehen wollen. */
@Composable
fun RefreshButton(loader: Loader<*>) {
    IconButton(onClick = { loader.refresh() }, enabled = !loader.loading) {
        GIcon(Ic.refresh, tint = if (loader.loading) Palette.faint else Palette.text)
    }
}

/** Kennzahl-Karte: Name in Versalien, Zahl groß in der Serifenschrift, darunter eine Zeile. */
@Composable
fun StatTile(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    GCard(modifier, padding = 16.dp, radius = 20.dp) {
        Column {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1)
            VSpace(10.dp)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = Palette.text, maxLines = 1)
            VSpace(2.dp)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
        }
    }
}

/** Kennzahlen zu zweit nebeneinander, gleich hoch. */
@Composable
fun StatGrid(tiles: List<Triple<String, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (l, v, n) -> StatTile(l, v, n, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Leere Liste: eine ruhige Zeile statt einer leeren Karte. */
@Composable
fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(horizontal = 4.dp))
}
