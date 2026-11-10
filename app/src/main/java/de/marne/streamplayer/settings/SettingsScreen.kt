package de.marne.streamplayer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.ui.design.Disclosure
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvorySwitch
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Segmented

@Composable
fun SettingsScreen(settings: Settings, onClearBrowsingData: () -> Unit, onToast: (String) -> Unit) {
    val theme by settings.theme.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val parallel by settings.parallel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–"
    }
    var confirmClear by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 40.dp),
    ) {
        item { ScreenHeader(title = "Wie soll es laufen?", eyebrow = "Einstellungen") }

        item { SectionTitle("Darstellung") }
        item {
            Segmented(
                options = listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel"),
                selected = theme,
                onSelect = settings::setTheme,
            )
        }

        item { SectionTitle("Downloads") }
        item {
            ListGroup {
                ListRow(
                    title = "Nur über WLAN",
                    subtitle = "Mobile Daten bleiben unberührt.",
                    icon = Ic.wifi,
                    trailing = { IvorySwitch(wifiOnly, settings::setWifiOnly) },
                )
                RowDivider()
                ListRow(
                    title = "Gleichzeitig",
                    icon = Ic.download,
                    trailing = {
                        Segmented(
                            options = listOf("1" to "1", "2" to "2", "3" to "3"),
                            selected = parallel.toString(),
                            onSelect = { settings.setParallel(it.toInt()) },
                            modifier = Modifier.width(132.dp),
                            height = 36.dp,
                        )
                    },
                )
                RowDivider()
                ListRow(title = "Speicherort", icon = Ic.folder, value = "Filme/Streamplayer")
            }
        }

        item { SectionTitle("Browser") }
        item {
            ListGroup {
                ListRow(
                    title = "Verlauf löschen",
                    icon = Ic.schedule,
                    titleColor = Palette.wine,
                    showChevron = false,
                    onClick = {
                        settings.clearHistory()
                        onToast("Verlauf gelöscht.")
                    },
                )
                RowDivider()
                ListRow(
                    title = "Cookies und Website-Daten löschen",
                    icon = Ic.delete,
                    titleColor = Palette.wine,
                    showChevron = false,
                    onClick = { confirmClear = true },
                )
            }
        }

        item { SectionTitle("Über") }
        item {
            ListGroup {
                ListRow(title = "Version", icon = Ic.info, value = version)
                RowDivider()
                Disclosure(title = "So findet die App Videos", icon = Ic.search) {
                    Text(
                        "Die App ist ein Browser. Sie sieht jede Anfrage einer Seite und liest zusätzlich " +
                            "die Video-Elemente im Seiten-Code. Was nach Video aussieht, prüft sie kurz beim " +
                            "Server, bevor es in der Liste erscheint. Geschützte Streams (DRM) und Livestreams " +
                            "lädt sie nicht.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.muted,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Palette.surface,
            title = { Text("Alles vergessen?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Du wirst auf allen Seiten abgemeldet. Deine Downloads bleiben.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            },
            confirmButton = {
                QuietButton("Löschen", color = Palette.wine) {
                    confirmClear = false
                    onClearBrowsingData()
                    onToast("Cookies und Website-Daten gelöscht.")
                }
            },
            dismissButton = { QuietButton("Behalten", color = Palette.muted) { confirmClear = false } },
        )
    }
}
