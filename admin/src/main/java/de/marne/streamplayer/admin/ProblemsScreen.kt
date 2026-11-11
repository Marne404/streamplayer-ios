package de.marne.streamplayer.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace

/**
 * Was schiefgeht, über alle Installationen in 30 Tagen: Seiten, auf denen die Video-Suche nichts
 * fand (nur der Hostname), Absturz-Signaturen und die Fehlerquote der Downloads je Tag.
 */
@Composable
fun ProblemsScreen(loader: Loader<Problems>) {
    Page(loader, header = {
        ScreenHeader(title = "Probleme", eyebrow = "Letzte 30 Tage", subtitle = stand(loader), trailing = { RefreshButton(loader) })
    }) { p ->
        item {
            VSpace(28.dp)
            StatGrid(
                listOf(
                    Triple("Download-Fehler", percent(p.dlRate), "${count(p.dlFailed)} von ${count(p.dlDone + p.dlFailed)} beendeten"),
                    Triple("Abstürze", count(p.crashes.sumOf { it.count }), "${p.crashes.size} verschiedene"),
                ),
            )
        }
        item { SectionTitle("Download-Fehlerquote je Tag") }
        item {
            GCard {
                DayBars(
                    values = p.dlDays.map { it.rate },
                    days = p.dlDays.map { it.day },
                    format = { percent(it) },
                    summary = "Fehlgeschlagen ÷ beendet · leer = kein Download beendet",
                    color = Palette.wine,
                )
            }
        }
        item { SectionTitle("Suche erfolglos · Seiten") }
        item {
            if (p.domains.isEmpty()) EmptyLine("Keine – die Video-Suche fand überall etwas.")
            else ListGroup {
                p.domains.forEachIndexed { i, d ->
                    if (i > 0) RowDivider(18.dp)
                    ListRow(
                        d.domain,
                        subtitle = "${if (d.installs == 1) "1 Installation" else "${d.installs} Installationen"} · zuletzt ${dayShort(d.last)}",
                        value = times(d.count),
                        titleMaxLines = 1,
                    )
                }
            }
        }
        item { SectionTitle("Abstürze") }
        item {
            if (p.crashes.isEmpty()) EmptyLine("Keine Abstürze gemeldet.")
            else ListGroup {
                p.crashes.forEachIndexed { i, c ->
                    if (i > 0) RowDivider(18.dp)
                    CrashRow(c, showInstalls = true)
                }
            }
        }
    }
}
