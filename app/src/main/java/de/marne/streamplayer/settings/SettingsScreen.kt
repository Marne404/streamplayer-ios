package de.marne.streamplayer.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.App
import de.marne.streamplayer.account.AccountSection
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.ui.design.Disclosure
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.IvorySwitch
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.update.UpdateState
import de.marne.streamplayer.update.Updater
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Einstellungen: jede Gruppe eine Karte aus Zeilen, nichts Längeres auf der obersten Ebene.
 * Was Erklärung oder Eingabe braucht (Anmelden, TMDB-Schlüssel), öffnet ein Blatt.
 * Zeilen mit Schalter schalten auf der ganzen Fläche – den kleinen Schalter allein trifft man schlecht.
 */
@Composable
fun SettingsScreen(settings: Settings, onClearBrowsingData: () -> Unit, onToast: (String) -> Unit, onProfiles: () -> Unit) {
    val app = App.instance
    val theme by settings.theme.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val parallel by settings.parallel.collectAsStateWithLifecycle()
    val adblockOn by settings.adblock.collectAsStateWithLifecycle()
    val allowlist by settings.allowlist.collectAsStateWithLifecycle()
    val filter by app.adblock.status.collectAsStateWithLifecycle()
    val tmdbKey by settings.tmdbKey.collectAsStateWithLifecycle()
    val tmdbAuto by settings.tmdbAuto.collectAsStateWithLifecycle()
    val askProfile by settings.askProfile.collectAsStateWithLifecycle()
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–"
    }
    var confirmClear by remember { mutableStateOf(false) }
    var tmdbSheet by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 40.dp),
    ) {
        item { ScreenHeader(title = "Wie soll es laufen?", eyebrow = "Einstellungen") }

        item { SectionTitle("Konto") }
        item { AccountSection(onToast = onToast) }

        item { SectionTitle("Profile") }
        item {
            ListGroup {
                ListRow(
                    title = "Profile verwalten",
                    subtitle = "Jedes Profil hat seinen eigenen Fortschritt.",
                    icon = Ic.group,
                    value = profiles.size.toString(),
                    onClick = onProfiles,
                )
                RowDivider()
                ListRow(
                    title = "Beim Start fragen, wer schaut",
                    subtitle = if (profiles.size > 1) "Sonst öffnet das zuletzt gewählte Profil." else "Ab zwei Profilen.",
                    icon = Ic.account,
                    showChevron = false,
                    onClick = { settings.setAskProfile(!askProfile) },
                    trailing = { IvorySwitch(askProfile, settings::setAskProfile) },
                )
            }
        }

        item { SectionTitle("Darstellung") }
        item {
            Segmented(
                options = listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel"),
                selected = theme,
                onSelect = settings::setTheme,
            )
        }

        item { SectionTitle("Mediathek") }
        item {
            ListGroup {
                ListRow(
                    title = "TMDB",
                    subtitle = "Cover, Beschreibungen, Folgen.",
                    icon = Ic.movie,
                    value = if (tmdbKey.isBlank()) "Aus" else "Aktiv",
                    onClick = { tmdbSheet = true },
                )
                RowDivider()
                ListRow(
                    title = "Titel automatisch erkennen",
                    subtitle = "Ohne TMDB-ID im Katalog nach Namen suchen.",
                    icon = Ic.sparkle,
                    showChevron = false,
                    onClick = { settings.setTmdbAuto(!tmdbAuto) },
                    trailing = { IvorySwitch(tmdbAuto, settings::setTmdbAuto) },
                )
                RowDivider()
                ListRow(
                    title = "Alle Titel neu laden",
                    icon = Ic.refresh,
                    showChevron = false,
                    onClick = {
                        if (!app.enricher.available) onToast("Erst einen TMDB-Schlüssel eintragen.")
                        else {
                            app.enricher.enqueue(app.library.titles.value.map { it.id }, force = true)
                            onToast("TMDB lädt alle Titel neu.")
                        }
                    },
                )
            }
        }

        item { SectionTitle("Downloads") }
        item {
            ListGroup {
                ListRow(
                    title = "Nur über WLAN",
                    subtitle = "Mobile Daten bleiben unberührt.",
                    icon = Ic.wifi,
                    showChevron = false,
                    onClick = { settings.setWifiOnly(!wifiOnly) },
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

        // Werbeblocker und Browserdaten gehören zusammen: beides betrifft nur Seiten im Browser.
        item { SectionTitle("Browser") }
        item {
            ListGroup {
                ListRow(
                    title = "Werbung blockieren",
                    subtitle = "Mit Trackern und Popups.",
                    icon = Ic.shield,
                    showChevron = false,
                    onClick = { settings.setAdblock(!adblockOn) },
                    trailing = { IvorySwitch(adblockOn, settings::setAdblock) },
                )
                RowDivider()
                ListRow(
                    // Welche Listen es sind, steht nicht mehr hier: die Zeile hat nur eine Untertitelzeile.
                    title = if (filter.updating) "Filterlisten werden aktualisiert …" else "Filterlisten aktualisieren",
                    subtitle = filter.error ?: listOfNotNull(
                        filter.updatedAt?.let {
                            "Stand " + DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN).format(it.atZone(ZoneId.systemDefault()))
                        },
                        if (filter.ready) "%,d Regeln".format(Locale.GERMANY, filter.rules) else null,
                    ).joinToString(" · "),
                    icon = Ic.refresh,
                    showChevron = false,
                    onClick = if (filter.updating) null else ({ app.adblock.updateNow() }),
                )
                if (allowlist.isNotEmpty()) {
                    RowDivider()
                    Disclosure(title = "Seiten ohne Werbeblocker", icon = Ic.browser, value = "${allowlist.size}") {
                        Column {
                            allowlist.sorted().forEach { host ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    QuietButton("Entfernen", color = Palette.wine) { settings.setAllowed(host, false) }
                                }
                            }
                        }
                    }
                }
                RowDivider()
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
                ListRow(
                    title = "Nach Updates suchen",
                    subtitle = when (val u = update) {
                        is UpdateState.Downloading -> "Lädt ${u.version} … ${(u.progress * 100).toInt().coerceIn(0, 100)} %"
                        is UpdateState.Ready -> "${u.version} ist bereit – tippen zum Installieren"
                        UpdateState.None -> updateNote ?: if (Updater.enabled) "Version $version · prüft bei jedem Start" else "Version $version · Testversion, ohne Updates"
                    },
                    icon = Ic.download,
                    showChevron = false,
                    trailing = if (checking) ({ androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Palette.text, strokeWidth = 2.dp) }) else null,
                    onClick = {
                        if (update is UpdateState.Ready) Updater.install(context)
                        else if (!checking) scope.launch {
                            checking = true
                            // Immer kurz „prüfen“ – auch wenn die letzte Antwort noch gilt und gar keine Anfrage rausgeht.
                            val started = System.currentTimeMillis()
                            val result = Updater.checkNow(context)
                            kotlinx.coroutines.delay((700 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                            updateNote = when (result) {
                                Updater.CheckResult.UpToDate -> "Version $version ist die neueste."
                                Updater.CheckResult.Found -> null
                                Updater.CheckResult.Failed -> "Keine Verbindung zum Update-Server."
                            }
                            checking = false
                        }
                    },
                )
                RowDivider()
                ListRow(title = "Einführung noch einmal zeigen", icon = Ic.sparkle, onClick = { settings.setOnboarded(false) })
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

    if (tmdbSheet) TmdbSheet(settings, onDone = { tmdbSheet = false; it?.let(onToast) })
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

/** TMDB-Schlüssel eintragen und prüfen. Schließt von selbst, sobald TMDB ihn annimmt. */
@Composable
private fun TmdbSheet(settings: Settings, onDone: (String?) -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val saved by settings.tmdbKey.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { onDone(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding().imePadding().animateContentSize(),
        ) {
            Text("MEDIATHEK", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text("TMDB verbinden.", style = MaterialTheme.typography.headlineSmall)
            VSpace(10.dp)
            Text(
                "Mit einem Schlüssel erkennt die App Titel und holt Cover, Hintergründe, Beschreibungen, Staffeln und Folgennamen. " +
                    "Kostenlos unter themoviedb.org → Einstellungen → API. Der Schlüssel bleibt auf diesem Gerät.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
            VSpace(18.dp)
            IvoryField(draft, { draft = it; error = null }, placeholder = "API-Schlüssel oder Lese-Token", password = true)
            error?.let { VSpace(10.dp); StatusLine(Ic.error, it) }
            VSpace(18.dp)
            PrimaryButton(if (busy) "Prüfe …" else "Speichern", enabled = !busy && draft.isNotBlank()) {
                busy = true; error = null
                settings.setTmdbKey(draft)
                scope.launch {
                    // Gespeichert bleibt er auch bei einem Fehler: check() unterscheidet „abgelehnt“ nicht von
                    // „kein Netz“, und offline soll niemand seinen Schlüssel neu tippen müssen.
                    val ok = app.tmdb.check()
                    busy = false
                    if (ok) onDone("TMDB ist verbunden.") else error = "Gespeichert, aber TMDB antwortet nicht – Schlüssel falsch oder keine Verbindung."
                }
            }
            if (saved.isNotBlank()) {
                QuietButton("Schlüssel entfernen", color = Palette.wine, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    settings.setTmdbKey("")
                    onDone("TMDB ist aus.")
                }
            }
        }
    }
}
