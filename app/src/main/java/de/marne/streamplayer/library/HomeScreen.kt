package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IconBadge
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace
import java.time.LocalTime

class HomeActions(
    val open: (LibTitle) -> Unit,
    val play: (ContinueItem) -> Unit,
    val playTitle: (LibTitle) -> Unit,
    val add: () -> Unit,
    val browser: () -> Unit,
)

/** Startseite wie eine Mediathek: eine dunkle Hero-Karte, darunter Regale. */
@Composable
fun HomeScreen(titles: List<LibTitle>, progress: Map<String, Progress>, actions: HomeActions) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cont = remember(titles, progress) { Shelves.continueWatching(titles, progress) }
    val series = remember(titles) { titles.filter { it.kind == TitleKind.SERIES } }
    val movies = remember(titles) { titles.filter { it.kind == TitleKind.MOVIE } }
    val offline = remember(titles) { titles.filter { it.offline.isNotEmpty() } }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(top = top + 16.dp, bottom = 40.dp),
    ) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text("BIBLIOTHEK", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    VSpace(6.dp)
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge)
                }
                IconButton(onClick = actions.browser) { GIcon(Ic.browser, tint = Palette.text) }
                IconButton(onClick = actions.add) { IconBadge(Ic.add) }
            }
        }

        if (titles.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    VSpace(32.dp)
                    Text("Noch leer.", style = MaterialTheme.typography.headlineSmall)
                    VSpace(6.dp)
                    Text(
                        "Füg einen Film oder eine Serie per Link hinzu. Abgespielt wird direkt im eigenen Player.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                    VSpace(28.dp)
                    PrimaryButton("Titel hinzufügen", icon = Ic.add, onClick = actions.add)
                    VSpace(4.dp)
                    QuietButton("Im Browser suchen", color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally), onClick = actions.browser)
                }
            }
            return@LazyColumn
        }

        // Hero: was du zuletzt gesehen hast – sonst das Neueste.
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp)) {
                val first = cont.firstOrNull()
                if (first != null) {
                    HeroCard(
                        image = first.title.image ?: first.title.offline.values.firstOrNull(),
                        eyebrow = if (first.isNextUp) "Nächste Folge" else "Weiterschauen",
                        title = first.title.title,
                        meta = continueMeta(first).removePrefix("Nächste Folge · "),
                        progress = first.progress?.takeIf { !it.finished }?.fraction,
                        button = if (first.isNextUp || first.progress == null) "Abspielen" else "Weiterschauen",
                        onPlay = { actions.play(first) },
                        onOpen = { actions.open(first.title) },
                    )
                } else {
                    val newest = titles.maxBy { it.addedAt }
                    HeroCard(
                        image = newest.image ?: newest.offline.values.firstOrNull(),
                        eyebrow = "Neu in der Bibliothek",
                        title = newest.title,
                        meta = titleMeta(newest),
                        progress = null,
                        button = "Abspielen",
                        onPlay = { actions.playTitle(newest) },
                        onOpen = { actions.open(newest) },
                    )
                }
            }
        }

        val more = cont.drop(1)
        if (more.isNotEmpty()) {
            shelf("Weiterschauen") {
                items(more, key = { it.key }) { c ->
                    WideTile(
                        image = c.title.image, title = c.title.title, meta = continueMeta(c), width = 240.dp,
                        progress = c.progress?.takeIf { !it.finished }?.fraction,
                        onClick = { actions.play(c) }, onLongClick = { actions.open(c.title) },
                    )
                }
            }
        }
        if (series.isNotEmpty()) {
            shelf("Serien") {
                items(series, key = { it.id }) { t ->
                    WideTile(t.image, t.title, titleMeta(t), 200.dp, onClick = { actions.open(t) })
                }
            }
        }
        if (movies.isNotEmpty()) {
            shelf("Filme") {
                items(movies, key = { it.id }) { t ->
                    val p = progress[Keys.movie(t)]
                    WideTile(
                        t.image ?: t.offline.values.firstOrNull(), t.title, titleMeta(t), 200.dp,
                        progress = p?.takeIf { !it.finished }?.fraction,
                        badgeIcon = if (p?.finished == true) Ic.check else null,
                        badge = if (p?.finished == true) "Gesehen" else null,
                        onClick = { actions.open(t) },
                    )
                }
            }
        }
        if (offline.isNotEmpty()) {
            shelf("Offline") {
                items(offline, key = { "o" + it.id }) { t ->
                    WideTile(
                        t.image ?: t.offline.values.firstOrNull(), t.title,
                        if (t.kind == TitleKind.SERIES) "${t.offline.size} Folgen offline" else titleMeta(t), 200.dp,
                        badgeIcon = Ic.download, badge = "Offline",
                        onClick = { actions.open(t) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(
    name: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    item(key = "shelf-$name") {
        Column {
            SectionTitle(name, modifier = Modifier.padding(horizontal = 20.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Guten Morgen."
    in 11..17 -> "Guten Tag."
    else -> "Guten Abend."
}
