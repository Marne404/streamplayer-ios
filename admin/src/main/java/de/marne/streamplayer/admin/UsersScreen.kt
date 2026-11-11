package de.marne.streamplayer.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.BackBar
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.Pill
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace
import java.time.LocalDate
import java.time.ZoneId

/** Alle Installationen, gruppiert danach, wann sie sich zuletzt gemeldet haben. */
@Composable
fun UsersScreen(loader: Loader<List<Install>>, onOpen: (String) -> Unit) {
    Page(loader, header = {
        val n = loader.data?.size
        ScreenHeader(
            title = "Nutzer",
            eyebrow = if (n == null) "Installationen" else if (n == 1) "Eine Installation" else "${count(n)} Installationen",
            subtitle = stand(loader),
            trailing = { RefreshButton(loader) },
        )
    }) { users ->
        if (users.isEmpty()) {
            item { VSpace(28.dp); EmptyLine("Noch hat sich keine Installation gemeldet.") }
            return@Page
        }
        val today = LocalDate.now()
        fun ageDays(u: Install) = today.toEpochDay() - java.time.Instant.ofEpochMilli(u.lastSeen).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val groups = listOf(
            "Heute gemeldet" to users.filter { ageDays(it) <= 0 },
            "Letzte 7 Tage" to users.filter { ageDays(it) in 1..6 },
            "Länger her" to users.filter { ageDays(it) >= 7 },
        ).filter { it.second.isNotEmpty() }
        groups.forEach { (title, list) ->
            item(key = "t:$title") { SectionTitle("$title · ${list.size}") }
            item(key = "g:$title") {
                ListGroup {
                    list.forEachIndexed { i, u ->
                        if (i > 0) RowDivider(18.dp)
                        ListRow(
                            title = u.label,
                            subtitle = "${u.version.ifEmpty { "?" }} · ${ago(u.lastSeen)} · ${duration(u.watch7)} in 7 Tagen",
                            value = if (u.sessions7 > 0) "${u.sessions7}×" else null,
                            titleMaxLines = 1,
                            onClick = { onOpen(u.install) },
                        )
                    }
                }
            }
        }
    }
}

private val userMetrics = listOf<Metric<Day>>(
    Metric("Sehzeit", { it.watchMs.toDouble() }, { hours(it.toLong()) }, { d -> "${duration(d.sumOf { it.watchMs })} in 30 Tagen" }),
    Metric("Sitzungen", { it.sessions.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.sessions })} in 30 Tagen" }),
    Metric("Wiedergaben", { it.plays.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.plays })} in 30 Tagen" }),
    Metric("Downloads", { it.dlDone.toDouble() }, { count(it.toInt()) }, { d -> "${count(d.sumOf { it.dlDone })} fertig, ${count(d.sumOf { it.dlFailed })} fehlgeschlagen" }),
    Metric("Probleme", { (it.findFailed + it.dlFailed + it.crashes).toDouble() }, { count(it.toInt()) },
        { d -> "${count(d.sumOf { it.findFailed })} Suchen, ${count(d.sumOf { it.dlFailed })} Downloads, ${count(d.sumOf { it.crashes })} Abstürze" }),
)

/** Eine Installation: Stammdaten, Bestand, Tagesreihe, Fehl-Domains, Abstürze. */
@Composable
fun UserDetailScreen(install: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loader = remember(install) { Loader(scope) { parseUser(Api.call("user", "install" to install)) } }
    var metric by rememberSaveable { mutableIntStateOf(0) }
    Page(loader, bottomPadding = 48.dp, header = {
        Column {
            BackBar(onBack, trailing = { RefreshButton(loader) })
            val u = loader.data?.user
            ScreenHeader(
                title = u?.label ?: "Installation",
                eyebrow = if (u?.accountName != null) "Angemeldet" else "Ohne Konto",
                subtitle = install,
            )
        }
    }) { d ->
        val u = d.user
        item { SectionTitle("Gerät und App") }
        item {
            ListGroup {
                ListRow("Version", value = "${u.version.ifEmpty { "?" }} (${u.versionCode})")
                RowDivider(18.dp)
                ListRow("Android", value = if (u.sdk > 0) "SDK ${u.sdk}" else "?")
                RowDivider(18.dp)
                ListRow("Sprache", value = u.lang.ifEmpty { "?" })
                RowDivider(18.dp)
                ListRow("Zuletzt gemeldet", value = ago(u.lastSeen))
                RowDivider(18.dp)
                ListRow("Zuerst gesehen", value = ago(u.firstSeen))
            }
        }
        item { SectionTitle("Bestand") }
        item {
            StatGrid(
                listOf(
                    Triple("Titel", count(u.counts.titles), "${count(u.counts.libraries)} Mediatheken"),
                    Triple("Profile", count(u.counts.profiles), " "),
                    Triple("Downloads", count(u.counts.downloadsStored), "gespeichert"),
                    Triple("Speicher", bytes(u.counts.bytesStored), "durch Downloads"),
                ),
            )
        }
        item { SectionTitle("Verlauf · 30 Tage") }
        item { GCard { MetricChart(userMetrics, d.days, d.days.map { it.day }, metric) { metric = it } } }

        val active = d.days.filter { it.sessions > 0 || it.watchMs > 0 || it.dlFailed > 0 || it.findFailed > 0 || it.crashes > 0 }.reversed()
        item { SectionTitle("Tage") }
        item {
            if (active.isEmpty()) EmptyLine("Keine Nutzung in den letzten 30 Tagen.")
            else ListGroup {
                active.forEachIndexed { i, day ->
                    if (i > 0) RowDivider(18.dp)
                    DayRow(day)
                }
            }
        }
        item { SectionTitle("Suche erfolglos · Seiten") }
        item {
            if (d.domains.isEmpty()) EmptyLine("Keine.")
            else ListGroup {
                d.domains.forEachIndexed { i, x ->
                    if (i > 0) RowDivider(18.dp)
                    ListRow(x.domain, subtitle = "an ${x.days} ${if (x.days == 1) "Tag" else "Tagen"} · zuletzt ${dayShort(x.last)}", value = times(x.count), titleMaxLines = 1)
                }
            }
        }
        item { SectionTitle("Abstürze") }
        item {
            if (d.crashes.isEmpty()) EmptyLine("Keine.")
            else ListGroup {
                d.crashes.forEachIndexed { i, c ->
                    if (i > 0) RowDivider(18.dp)
                    CrashRow(c, showInstalls = false)
                }
            }
        }
    }
}

/** Ein Tag einer Installation: Datum, Sitzungen und Sehzeit, darunter, was schiefging. */
@Composable
private fun DayRow(d: Day) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayLong(d.day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(duration(d.watchMs), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        val parts = buildList {
            add(if (d.sessions == 1) "1 Sitzung" else "${d.sessions} Sitzungen")
            if (d.plays > 0) add("${d.plays} Wiedergaben")
            if (d.dlStarted + d.dlDone + d.dlFailed > 0) add("Downloads ${d.dlDone}/${d.dlStarted}")
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        if (d.dlFailed + d.findFailed + d.crashes > 0) {
            VSpace(6.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.crashes > 0) Pill(if (d.crashes == 1) "1 Absturz" else "${d.crashes} Abstürze", textColor = Palette.wine)
                if (d.dlFailed > 0) Pill("${d.dlFailed} Download-Fehler", textColor = Palette.wine)
                if (d.findFailed > 0) Pill("${d.findFailed} Suche erfolglos", textColor = Palette.brass)
            }
        }
    }
}

/** Absturz-Signatur über mehrere Zeilen (sie ist der eigentliche Inhalt), darunter Versionen. */
@Composable
fun CrashRow(c: Crash, showInstalls: Boolean) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(c.sig, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            HSpace(12.dp)
            Text(times(c.count), style = MaterialTheme.typography.bodyMedium, color = Palette.wine)
        }
        VSpace(4.dp)
        val parts = buildList {
            if (showInstalls) add(if (c.installs == 1) "1 Installation" else "${c.installs} Installationen")
            if (c.versions.isNotEmpty()) add("Version ${c.versions.joinToString(", ")}")
            add("zuletzt ${dayShort(c.last)}")
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    }
}
