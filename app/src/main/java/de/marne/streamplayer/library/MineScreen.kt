package de.marne.streamplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace

/**
 * „Meins“: wer schaut, was er geschaut hat (Auswertung), Weiterschauen, Meine Liste – und die
 * Werkzeuge der Bibliothek (Downloads, Offline, Gesehen, Import, Browser).
 */
@Composable
fun MineScreen(state: LibState, actions: LibActions) {
    val stats = remember(state.titles, state.progress, state.myList) { Recommend.stats(state.titles, state.progress, state.myList) }
    val cont = remember(state.titles, state.progress) { Shelves.continueWatching(state.titles, state.progress) }
    val myList = remember(state.titles, state.myList) { state.myList.mapNotNull(state::byId) }
    val watched = remember(state.titles, state.progress) { state.titles.count { Recommend.seen(it, state.progress) == Recommend.Seen.DONE } }
    val offline = remember(state.titles) { state.titles.count { it.offline.isNotEmpty() } }
    val running = state.downloads.count { it.active }

    LazyColumn(Modifier.fillMaxSize().background(Palette.bg), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                de.marne.streamplayer.account.ProfileAvatar(state.profile, 56.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("MEIN BEREICH", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    Text(state.profile.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                QuietButton("Wechseln", color = Palette.text, onClick = actions.profiles)
            }
        }
        item { StatsCard(stats, Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) }

        if (cont.isNotEmpty()) {
            shelf("Weiterschauen", key = "m-continue", onAll = if (cont.size > 3) ({ actions.grid(GridSpec("continue")) }) else null) {
                items(cont, key = { "mc" + it.key }) { c ->
                    WideTile(
                        image = c.episode?.still ?: c.title.wide, title = c.title.title, meta = continueMeta(c), width = 220.dp,
                        progress = c.progress?.takeIf { !it.finished }?.fraction,
                        onClick = { actions.playContinue(c) }, onLongClick = { actions.preview(c.title) },
                    )
                }
            }
        }
        if (myList.isNotEmpty()) {
            shelf("Meine Liste", key = "m-list", onAll = { actions.grid(GridSpec("list")) }) {
                items(myList, key = { "ml" + it.id }) { TitlePoster(it, state, actions) }
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SectionTitle("Meine Liste")
                    Text(
                        "Noch leer. Tippe bei einem Titel auf „Meine Liste“, dann liegt er hier – auch auf deinen anderen Geräten.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("Bibliothek")
                ListGroup {
                    ListRow(
                        "Downloads", icon = Ic.download,
                        subtitle = when {
                            running > 0 -> if (running == 1) "1 läuft gerade" else "$running laufen gerade"
                            else -> null
                        },
                        value = state.downloads.size.takeIf { it > 0 }?.toString(),
                        onClick = actions.downloads,
                    )
                    RowDivider()
                    ListRow("Offline verfügbar", icon = Ic.folder, value = offline.toString(), onClick = { actions.grid(GridSpec("offline")) })
                    RowDivider()
                    ListRow("Gesehen", icon = Ic.checkCircle, value = watched.toString(), onClick = { actions.grid(GridSpec("watched")) })
                    RowDivider()
                    ListRow("Katalog importieren", icon = Ic.importFile, onClick = actions.import)
                    RowDivider()
                    ListRow("Browser", subtitle = "Seiten öffnen, Videos finden", icon = Ic.browser, onClick = actions.browser)
                }
            }
        }
    }
}

/** Die dunkle Karte dieses Screens: wie viel, was zuletzt, welche Genres. */
@Composable
private fun StatsCard(s: Recommend.Stats, modifier: Modifier) {
    GCard(modifier, tone = "ink", radius = 28.dp, padding = 22.dp) {
        Column {
            Text("DEIN SCHAUEN", style = MaterialTheme.typography.labelSmall, color = Palette.onInk.copy(alpha = 0.7f))
            VSpace(8.dp)
            if (s.watchedMs < 60_000) {
                Text("Noch nichts.", style = MaterialTheme.typography.headlineMedium, color = Palette.onInk)
                VSpace(4.dp)
                Text("Hier steht bald, wie viel du schaust und was dir gefällt.", style = MaterialTheme.typography.bodySmall, color = Palette.onInk.copy(alpha = 0.7f))
                return@Column
            }
            Text(minutesText(s.watchedMs) ?: "–", style = MaterialTheme.typography.displaySmall, color = Palette.onInk)
            Text("insgesamt geschaut", style = MaterialTheme.typography.bodySmall, color = Palette.onInk.copy(alpha = 0.7f))
            VSpace(18.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Fact(minutesText(s.weekMs) ?: "0 Min.", "diese Woche")
                Fact(s.episodes.toString(), if (s.episodes == 1) "Folge" else "Folgen")
                Fact(s.movies.toString(), if (s.movies == 1) "Film" else "Filme")
            }
            if (s.topGenres.isNotEmpty()) {
                VSpace(22.dp)
                Text("LIEBLINGSGENRES", style = MaterialTheme.typography.labelSmall, color = Palette.onInk.copy(alpha = 0.7f))
                VSpace(10.dp)
                val max = s.topGenres.first().second
                s.topGenres.forEach { (g, share) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, style = MaterialTheme.typography.bodyMedium, color = Palette.onInk, modifier = Modifier.width(112.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Bar(share / max, height = 6.dp, color = Palette.onInk, track = Palette.onInk.copy(alpha = 0.18f), modifier = Modifier.weight(1f))
                        Text("${(share * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = Palette.onInk.copy(alpha = 0.7f), modifier = Modifier.padding(start = 10.dp).width(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = Palette.onInk)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.onInk.copy(alpha = 0.7f))
    }
}
