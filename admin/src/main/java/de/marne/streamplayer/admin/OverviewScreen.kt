package de.marne.streamplayer.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.MenuAction
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.RowMenu
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace

/** Was im Verlauf wählbar ist. Summen über 30 Tage im Kopf, Aktive als Höchstwert eines Tages. */
private val overviewMetrics = listOf<Metric<Day>>(
    Metric("Aktive", { it.active.toDouble() }, { count(it.toInt()) }, { d -> "Höchstens ${count(d.maxOfOrNull { it.active } ?: 0)} an einem Tag" }),
    Metric("Sehzeit", { it.watchMs.toDouble() }, { hours(it.toLong()) }, { d -> "${duration(d.sumOf { it.watchMs })} in 30 Tagen" }),
    Metric("Wiedergaben", { it.plays.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.plays })} in 30 Tagen" }),
    Metric("Downloads", { it.dlDone.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.dlDone })} fertig in 30 Tagen" }),
    Metric("Download-Fehler", { it.dlFailed.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.dlFailed })} in 30 Tagen" }),
    Metric("Suche erfolglos", { it.findFailed.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.findFailed })} in 30 Tagen" }),
    Metric("Abstürze", { it.crashes.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.crashes })} in 30 Tagen" }),
)

@Composable
fun OverviewScreen(loader: Loader<Overview>) {
    var metric by rememberSaveable { mutableIntStateOf(0) }
    Page(loader, header = {
        ScreenHeader(
            title = "Überblick",
            eyebrow = "Streamplayer · Nutzung",
            subtitle = stand(loader),
            trailing = {
                Row {
                    RefreshButton(loader)
                    RowMenu(listOf(MenuAction("Abmelden", destructive = true) { Api.signOut() }))
                }
            },
        )
    }) { o ->
        item {
            VSpace(28.dp)
            StatGrid(
                listOf(
                    Triple("Aktiv heute", count(o.activeToday), "Installationen"),
                    Triple("Aktiv 7 Tage", count(o.active7), "von ${count(o.installs)} bekannten"),
                    Triple("Aktiv 30 Tage", count(o.active30), if (o.new7 > 0) "${count(o.new7)} neu in 7 Tagen" else "keine neuen in 7 Tagen"),
                    Triple("Wiedergaben", count(o.plays7), "in 7 Tagen · ${count(o.plays30)} in 30"),
                    Triple("Sehzeit 7 Tage", duration(o.watch7), perActive(o.watch7, o.active7)),
                    Triple("Sehzeit 30 Tage", duration(o.watch30), perActive(o.watch30, o.active30)),
                ),
            )
        }
        item { SectionTitle("Verlauf · 30 Tage") }
        item {
            GCard {
                MetricChart(overviewMetrics, o.days, o.days.map { it.day }, metric) { metric = it }
            }
        }
        item { SectionTitle("Versionen · aktiv in 30 Tagen") }
        item {
            if (o.versions.isEmpty()) EmptyLine("Noch keine aktive Installation.")
            else ListGroup {
                val total = o.versions.sumOf { it.installs }.coerceAtLeast(1)
                o.versions.forEachIndexed { i, v ->
                    if (i > 0) RowDivider(18.dp)
                    ShareRow(v.version.ifEmpty { "?" }, v.installs, total, if (i == 0) Palette.ink else Palette.faint)
                }
            }
        }
        item { SectionTitle("Konten · aktiv in 30 Tagen") }
        item {
            val total = (o.withAccount + o.withoutAccount).coerceAtLeast(1)
            ListGroup {
                ShareRow("Angemeldet", o.withAccount, total, Palette.green)
                RowDivider(18.dp)
                ShareRow("Ohne Konto", o.withoutAccount, total, Palette.faint)
                RowDivider(18.dp)
                ListRow("Verschiedene Konten", value = count(o.distinctAccounts), subtitle = "ein Konto kann auf mehreren Geräten laufen")
            }
        }
    }
}

private fun perActive(ms: Long, active: Int) = if (active > 0) "${hours(ms / active)} je Aktivem" else "niemand aktiv"

/** Zeile mit Anteil: Name, Balken, Anzahl und Prozent. */
@Composable
private fun ShareRow(label: String, n: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(count(n), style = MaterialTheme.typography.bodyLarge, color = Palette.text)
            HSpace(8.dp)
            Text(percent(n.toDouble() / total), style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.width(44.dp))
        }
        VSpace(8.dp)
        Bar(n.toFloat() / total, color = color)
    }
}
