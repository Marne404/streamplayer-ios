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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.App
import de.marne.streamplayer.account.AccountSection
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.tr
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
import de.marne.streamplayer.update.UpdateRow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Einstellungen: jede Gruppe eine Karte aus Zeilen, nichts Längeres auf der obersten Ebene.
 * Was Erklärung oder Eingabe braucht (Anmelden, TMDB-Schlüssel), öffnet ein Blatt.
 * Zeilen mit Schalter schalten auf der ganzen Fläche – den kleinen Schalter allein trifft man schlecht.
 */
@Composable
fun SettingsScreen(settings: Settings, onClearBrowsingData: () -> Unit, onToast: (String) -> Unit, onProfiles: () -> Unit, onBack: (() -> Unit)? = null) {
    val app = App.instance
    val theme by settings.theme.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val parallel by settings.parallel.collectAsStateWithLifecycle()
    val autoDelete by settings.autoDelete.collectAsStateWithLifecycle()
    val autoNext by settings.autoNext.collectAsStateWithLifecycle()
    val usageStats by settings.usageStats.collectAsStateWithLifecycle()
    val adblockOn by settings.adblock.collectAsStateWithLifecycle()
    val allowlist by settings.allowlist.collectAsStateWithLifecycle()
    val filter by app.adblock.status.collectAsStateWithLifecycle()
    val tmdbKey by settings.tmdbKey.collectAsStateWithLifecycle()
    val tmdbAuto by settings.tmdbAuto.collectAsStateWithLifecycle()
    val askProfile by settings.askProfile.collectAsStateWithLifecycle()
    val homeContinue by settings.homeContinue.collectAsStateWithLifecycle()
    val homeMyList by settings.homeMyList.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var tmdbSheet by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 40.dp),
    ) {
        // Seit 1.9 eine Seite unter „Meins“: mit Zurück.
        if (onBack != null) item { androidx.compose.material3.IconButton(onClick = onBack) { de.marne.streamplayer.ui.design.GIcon(Ic.back) } }
        item { ScreenHeader(title = tr("Wie soll es laufen?", "How should it run?"), eyebrow = tr("Einstellungen", "Settings")) }

        item { SectionTitle(tr("Konto", "Account")) }
        item { AccountSection(onToast = onToast) }

        item { SectionTitle(tr("Profile", "Profiles")) }
        item {
            ListGroup {
                ListRow(
                    title = tr("Profile verwalten", "Manage profiles"),
                    subtitle = tr("Jedes Profil hat seinen eigenen Fortschritt.", "Each profile keeps its own progress."),
                    icon = Ic.group,
                    value = profiles.size.toString(),
                    onClick = onProfiles,
                )
                RowDivider()
                ListRow(
                    title = tr("Beim Start fragen, wer schaut", "Ask who's watching on start"),
                    subtitle = if (profiles.size > 1) tr("Sonst öffnet das zuletzt gewählte Profil.", "Otherwise the last profile opens.") else tr("Ab zwei Profilen.", "With two or more profiles."),
                    icon = Ic.account,
                    showChevron = false,
                    onClick = { settings.setAskProfile(!askProfile) },
                    trailing = { IvorySwitch(askProfile, settings::setAskProfile) },
                )
            }
        }

        item { SectionTitle(tr("Darstellung", "Appearance")) }
        item {
            Segmented(
                options = listOf("system" to "System", "light" to tr("Hell", "Light"), "dark" to tr("Dunkel", "Dark")),
                selected = theme,
                onSelect = settings::setTheme,
            )
        }
        // Sprache: jede heißt in sich selbst. Gilt sofort, ohne Neustart (Lang ist Compose-Zustand).
        item { SectionTitle(tr("Sprache", "Language")) }
        item {
            Segmented(
                options = listOf("de" to "Deutsch", "en" to "English"),
                selected = language.tag,
                onSelect = { tag -> Lang.fromTag(tag)?.let(settings::setLanguage) },
            )
        }

        item { SectionTitle(tr("Startseite", "Home")) }
        item {
            ListGroup {
                ListRow(
                    title = tr("Weiterschauen", "Continue Watching"),
                    subtitle = tr("Aus: steht nur unter „Meins“.", "Off: only shown in My Stuff."),
                    icon = Ic.play,
                    showChevron = false,
                    onClick = { settings.setHomeContinue(!homeContinue) },
                    trailing = { IvorySwitch(homeContinue, settings::setHomeContinue) },
                )
                RowDivider()
                ListRow(
                    title = tr("Meine Liste", "My List"),
                    subtitle = tr("Aus: steht nur unter „Meins“.", "Off: only shown in My Stuff."),
                    icon = Ic.bookmark,
                    showChevron = false,
                    onClick = { settings.setHomeMyList(!homeMyList) },
                    trailing = { IvorySwitch(homeMyList, settings::setHomeMyList) },
                )
            }
        }

        item { SectionTitle(tr("Mediathek", "Library")) }
        item {
            ListGroup {
                ListRow(
                    title = "TMDB",
                    subtitle = tr("Cover, Beschreibungen, Folgen.", "Covers, descriptions, episodes."),
                    icon = Ic.movie,
                    value = if (tmdbKey.isBlank()) tr("Aus", "Off") else tr("Aktiv", "On"),
                    onClick = { tmdbSheet = true },
                )
                RowDivider()
                ListRow(
                    title = tr("Titel automatisch erkennen", "Recognize titles automatically"),
                    subtitle = tr("Ohne TMDB-ID im Katalog nach Namen suchen.", "Search by name when the catalog has no TMDB ID."),
                    icon = Ic.sparkle,
                    showChevron = false,
                    onClick = { settings.setTmdbAuto(!tmdbAuto) },
                    trailing = { IvorySwitch(tmdbAuto, settings::setTmdbAuto) },
                )
                RowDivider()
                ListRow(
                    title = tr("Alle Titel neu laden", "Reload all titles"),
                    icon = Ic.refresh,
                    showChevron = false,
                    onClick = {
                        if (!app.enricher.available) onToast(tr("Erst einen TMDB-Schlüssel eintragen.", "Add a TMDB key first."))
                        else {
                            app.enricher.enqueue(app.library.titles.value.map { it.id }, force = true)
                            onToast(tr("TMDB lädt alle Titel neu.", "Reloading all titles from TMDB."))
                        }
                    },
                )
            }
        }

        item { SectionTitle(tr("Downloads", "Downloads")) }
        item {
            ListGroup {
                ListRow(
                    title = tr("Nur über WLAN", "Wi-Fi only"),
                    subtitle = tr("Mobile Daten bleiben unberührt.", "Mobile data stays untouched."),
                    icon = Ic.wifi,
                    showChevron = false,
                    onClick = { settings.setWifiOnly(!wifiOnly) },
                    trailing = { IvorySwitch(wifiOnly, settings::setWifiOnly) },
                )
                RowDivider()
                ListRow(
                    title = tr("Gleichzeitig", "At once"),
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
                ListRow(
                    title = tr("Gesehenes löschen", "Delete when watched"),
                    subtitle = tr("Zu Ende geschaut: Datei weg, Platz frei.", "Finished: file gone, space freed."),
                    icon = Ic.delete,
                    showChevron = false,
                    onClick = { settings.setAutoDelete(!autoDelete) },
                    trailing = { IvorySwitch(autoDelete, settings::setAutoDelete) },
                )
                RowDivider()
                ListRow(
                    title = tr("Nächste Folge laden", "Download next episode"),
                    subtitle = tr("Geladene Folge gesehen: die nächste kommt.", "Watched a downloaded episode: the next one follows."),
                    icon = Ic.skipNext,
                    showChevron = false,
                    onClick = { settings.setAutoNext(!autoNext) },
                    trailing = { IvorySwitch(autoNext, settings::setAutoNext) },
                )
                RowDivider()
                ListRow(title = tr("Speicherort", "Location"), icon = Ic.folder, value = tr("Filme/Streamplayer", "Movies/Streamplayer"))
            }
        }

        // Werbeblocker und Browserdaten gehören zusammen: beides betrifft nur Seiten im Browser.
        item { SectionTitle(tr("Browser", "Browser")) }
        item {
            ListGroup {
                ListRow(
                    title = tr("Werbung blockieren", "Block ads"),
                    subtitle = tr("Mit Trackern und Popups.", "Including trackers and pop-ups."),
                    icon = Ic.shield,
                    showChevron = false,
                    onClick = { settings.setAdblock(!adblockOn) },
                    trailing = { IvorySwitch(adblockOn, settings::setAdblock) },
                )
                RowDivider()
                ListRow(
                    // Welche Listen es sind, steht nicht mehr hier: die Zeile hat nur eine Untertitelzeile.
                    title = if (filter.updating) tr("Filterlisten werden aktualisiert …", "Updating filter lists …") else tr("Filterlisten aktualisieren", "Update filter lists"),
                    subtitle = filter.error ?: listOfNotNull(
                        filter.updatedAt?.let {
                            tr("Stand ", "Updated ") + DateTimeFormatter.ofPattern(if (Lang.isGerman) "d. MMMM" else "MMMM d", Lang.locale).format(it.atZone(ZoneId.systemDefault()))
                        },
                        if (filter.ready) tr("%,d Regeln", "%,d rules").format(Lang.locale, filter.rules) else null,
                    ).joinToString(" · "),
                    icon = Ic.refresh,
                    showChevron = false,
                    onClick = if (filter.updating) null else ({ app.adblock.updateNow() }),
                )
                if (allowlist.isNotEmpty()) {
                    RowDivider()
                    Disclosure(title = tr("Seiten ohne Werbeblocker", "Sites without ad blocker"), icon = Ic.browser, value = "${allowlist.size}") {
                        Column {
                            allowlist.sorted().forEach { host ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    QuietButton(tr("Entfernen", "Remove"), color = Palette.wine) { settings.setAllowed(host, false) }
                                }
                            }
                        }
                    }
                }
                RowDivider()
                ListRow(
                    title = tr("Verlauf löschen", "Clear history"),
                    icon = Ic.schedule,
                    titleColor = Palette.wine,
                    showChevron = false,
                    onClick = {
                        settings.clearHistory()
                        onToast(tr("Verlauf gelöscht.", "History cleared."))
                    },
                )
                RowDivider()
                ListRow(
                    title = tr("Cookies und Website-Daten löschen", "Clear cookies and site data"),
                    icon = Ic.delete,
                    titleColor = Palette.wine,
                    showChevron = false,
                    onClick = { confirmClear = true },
                )
            }
        }

        item { SectionTitle(tr("Datenschutz", "Privacy")) }
        item {
            ListGroup {
                ListRow(
                    title = tr("Nutzungsstatistik", "Usage statistics"),
                    subtitle = tr("Nur Zahlen an Marne: wie oft, wie lange, was schiefging. Keine Titel, keine Seiten.", "Numbers only, sent to Marne: how often, how long, what failed. No titles, no pages."),
                    icon = Ic.shield,
                    showChevron = false,
                    onClick = {
                        val on = !usageStats
                        settings.setUsageStats(on)
                        if (!on) { de.marne.streamplayer.stats.Usage.forget(); onToast(tr("Aus – auch das schon Geschickte wird gelöscht.", "Off – what was already sent gets deleted too.")) }
                    },
                    trailing = {
                        IvorySwitch(usageStats) { on ->
                            settings.setUsageStats(on)
                            if (!on) { de.marne.streamplayer.stats.Usage.forget(); onToast(tr("Aus – auch das schon Geschickte wird gelöscht.", "Off – what was already sent gets deleted too.")) }
                        }
                    },
                )
            }
        }

        item { SectionTitle(tr("Sicherung", "Backup")) }
        item { BackupRows(onToast) }

        item { SectionTitle(tr("Über", "About")) }
        item {
            ListGroup {
                UpdateRow()
                RowDivider()
                ListRow(title = tr("Einführung noch einmal zeigen", "Show the introduction again"), icon = Ic.sparkle, onClick = { settings.setOnboarded(false) })
                RowDivider()
                Disclosure(title = tr("So findet die App Videos", "How the app finds videos"), icon = Ic.search) {
                    Text(
                        tr(
                            "Die App ist ein Browser. Sie sieht jede Anfrage einer Seite und liest zusätzlich " +
                                "die Video-Elemente im Seiten-Code. Was nach Video aussieht, prüft sie kurz beim " +
                                "Server, bevor es in der Liste erscheint. Geschützte Streams (DRM) und Livestreams " +
                                "lädt sie nicht.",
                            "The app is a browser. It sees every request a page makes and also reads the video " +
                                "elements in the page's code. Anything that looks like a video gets a quick check " +
                                "with the server before it shows up in the list. Protected streams (DRM) and live " +
                                "streams aren't downloaded.",
                        ),
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
            title = { Text(tr("Alles vergessen?", "Forget everything?"), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    tr("Du wirst auf allen Seiten abgemeldet. Deine Downloads bleiben.", "You'll be signed out of every site. Your downloads stay."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            },
            confirmButton = {
                QuietButton(tr("Löschen", "Delete"), color = Palette.wine) {
                    confirmClear = false
                    onClearBrowsingData()
                    onToast(tr("Cookies und Website-Daten gelöscht.", "Cookies and site data cleared."))
                }
            },
            dismissButton = { QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirmClear = false } },
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
            Text(tr("MEDIATHEK", "LIBRARY"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(tr("TMDB verbinden.", "Connect TMDB."), style = MaterialTheme.typography.headlineSmall)
            VSpace(10.dp)
            Text(
                tr(
                    "Mit einem Schlüssel erkennt die App Titel und holt Cover, Hintergründe, Beschreibungen, Staffeln und Folgennamen. " +
                        "Kostenlos unter themoviedb.org → Einstellungen → API. Der Schlüssel bleibt auf diesem Gerät.",
                    "With a key, the app recognizes titles and fetches covers, backdrops, descriptions, seasons and episode names. " +
                        "Free at themoviedb.org → Settings → API. The key stays on this device.",
                ),
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
            VSpace(18.dp)
            IvoryField(draft, { draft = it; error = null }, placeholder = tr("API-Schlüssel oder Lese-Token", "API key or read access token"), password = true)
            error?.let { VSpace(10.dp); StatusLine(Ic.error, it) }
            VSpace(18.dp)
            PrimaryButton(if (busy) tr("Prüfe …", "Checking …") else tr("Speichern", "Save"), enabled = !busy && draft.isNotBlank()) {
                busy = true; error = null
                settings.setTmdbKey(draft)
                scope.launch {
                    // Gespeichert bleibt er auch bei einem Fehler: check() unterscheidet „abgelehnt“ nicht von
                    // „kein Netz“, und offline soll niemand seinen Schlüssel neu tippen müssen.
                    val ok = app.tmdb.check()
                    busy = false
                    if (ok) onDone(tr("TMDB ist verbunden.", "TMDB is connected.")) else error = tr("Gespeichert, aber TMDB antwortet nicht – Schlüssel falsch oder keine Verbindung.", "Saved, but TMDB isn't responding – wrong key or no connection.")
                }
            }
            if (saved.isNotBlank()) {
                QuietButton(tr("Schlüssel entfernen", "Remove key"), color = Palette.wine, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    settings.setTmdbKey("")
                    onDone(tr("TMDB ist aus.", "TMDB is off."))
                }
            }
        }
    }
}
