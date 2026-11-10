package de.marne.streamplayer.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.data.Visit
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.DetectionHub
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.detect.ProbeStatus
import de.marne.streamplayer.detect.Variant
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Bar
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.NoticeCard
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.SectionTitle
import de.marne.streamplayer.ui.design.VSpace

@Composable
fun BrowserScreen(
    browser: BrowserController,
    hub: DetectionHub,
    watch: WatchController,
    history: List<Visit>,
    onClearHistory: () -> Unit,
    onDownload: (Candidate, Variant?, String, String?) -> Unit,
    onPreview: (Candidate, String) -> Unit,
) {
    val url by browser.url.collectAsStateWithLifecycle()
    val title by browser.title.collectAsStateWithLifecycle()
    val loading by browser.loading.collectAsStateWithLifecycle()
    val progress by browser.progress.collectAsStateWithLifecycle()
    val showStart by browser.showStart.collectAsStateWithLifecycle()
    val candidates by hub.candidates.collectAsStateWithLifecycle()
    val meta by hub.meta.collectAsStateWithLifecycle()
    val blocked by browser.blocked.collectAsStateWithLifecycle()
    val settings = de.marne.streamplayer.App.instance.settings
    val adblockOn by settings.adblock.collectAsStateWithLifecycle()
    val allowlist by settings.allowlist.collectAsStateWithLifecycle()
    val pageHost = remember(url) { browser.pageHost }
    val allowedHere = pageHost != null && allowlist.any { pageHost == it || pageHost.endsWith(".$it") }

    val found = remember(candidates) {
        candidates.filter { it.status == ProbeStatus.OK }.sortedWith(
            compareByDescending<Candidate> { it.downloadable }
                .thenByDescending { it.kind != MediaKind.FILE }
                .thenByDescending { it.sizeBytes ?: 0 }
        )
    }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val watchMode by settings.watchMode.collectAsStateWithLifecycle()
    val watchState by watch.state.collectAsStateWithLifecycle()
    val watching = watchState != WatchController.State.Idle

    // Im Modus „Nur ansehen“ werden Links abgespielt; Suchbegriffe öffnen weiter die Suche.
    val open: (String) -> Unit = { input ->
        if (watchMode && BrowserController.looksLikeUrl(input)) watch.start(input) else browser.load(input)
    }

    // Gefunden: Player sofort öffnen, genau einmal.
    LaunchedEffect(watchState) {
        val s = watchState
        if (s is WatchController.State.Ready && s.autoStart) {
            onPreview(s.candidate, s.title)
            watch.playerOpened()
        }
    }

    BackHandler(enabled = !showStart) { browser.back() }
    BackHandler(enabled = watching) { watch.cancel() }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        AddressBar(
            url = if (showStart) "" else url,
            loading = loading,
            onPage = !showStart,
            onSubmit = open,
            onHome = { if (watching) watch.cancel() else browser.showStart() },
            placeholder = if (watchMode) "Link zum Ansehen einfügen" else "Adresse oder Suchbegriff",
            onReload = browser::reload,
            onStop = browser::stop,
            shield = if (adblockOn && !showStart) ShieldState(blocked, allowedHere, pageHost.orEmpty()) else null,
            onToggleAllow = browser::toggleAllowCurrentPage,
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading && !showStart) Bar(progress / 100f, height = 2.dp, track = Color.Transparent)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showStart) {
                StartPage(
                    history = history,
                    current = url.takeIf { it.isNotBlank() }?.let { it to title },
                    onOpen = open,
                    onResume = browser::resumePage,
                    onClear = onClearHistory,
                    watchMode = watchMode,
                    onWatchMode = settings::setWatchMode,
                )
            } else {
                AndroidView(
                    factory = {
                        (browser.webView.parent as? ViewGroup)?.removeView(browser.webView)
                        browser.webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (watching && !showStart) {
                WatchOverlay(
                    state = watchState,
                    onPlay = { (watchState as? WatchController.State.Ready)?.let { onPreview(it.candidate, it.title) } },
                    onDownload = {
                        (watchState as? WatchController.State.Ready)?.let {
                            onDownload(it.candidate, it.candidate.variants.firstOrNull(), it.title, it.thumbnail)
                        }
                    },
                    onShowPage = watch::showPage,
                    onCancel = watch::cancel,
                )
            }

            FoundOverlay(
                visible = !showStart && !watching && found.isNotEmpty(),
                count = found.size,
                onClick = { sheetOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }

    if (sheetOpen && found.isNotEmpty()) {
        FoundSheet(
            found = found,
            meta = meta,
            pageTitle = title,
            onDismiss = { sheetOpen = false },
            onDownload = { c, v, t, thumb ->
                sheetOpen = false
                onDownload(c, v, t, thumb)
            },
            onPreview = onPreview,
        )
    }
}

/** Eigene Funktion, damit AnimatedVisibility nicht die ColumnScope-Variante des äußeren Layouts erwischt. */
@Composable
private fun FoundOverlay(visible: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 } + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + slideOutVertically { it / 2 } + scaleOut(targetScale = 0.9f),
        modifier = modifier,
    ) {
        FoundButton(count, onClick)
    }
}

@Composable
private fun FoundButton(count: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.ink, contentColor = Palette.onInk),
        contentPadding = PaddingValues(start = 18.dp, end = 22.dp),
        modifier = Modifier.height(48.dp),
    ) {
        GIcon(Ic.download, tint = Palette.onInk, size = 20.dp)
        HSpace(10.dp)
        Text(
            if (count == 1) "1 Video gefunden" else "$count Videos gefunden",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
        )
    }
}

@Composable
private fun AddressBar(
    url: String,
    loading: Boolean,
    onPage: Boolean,
    onSubmit: (String) -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    shield: ShieldState?,
    onToggleAllow: () -> Unit,
    placeholder: String,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focus = LocalFocusManager.current
    // Ohne Fokus nur der Host: ruhiger als die volle Adresse.
    LaunchedEffect(url, focused) { if (!focused) text = TextFieldValue(url) }
    val shown = if (focused) text else TextFieldValue(if (url.isBlank()) "" else Format.host(url))

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPage) {
            IconButton(onClick = onHome) { GIcon(Ic.home, tint = Palette.text) }
        } else {
            HSpace(14.dp)
        }
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.surface2)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GIcon(if (!focused && url.startsWith("https")) Ic.lock else Ic.search, tint = Palette.muted, size = 18.dp)
            HSpace(10.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (shown.text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, maxLines = 1)
                }
                BasicTextField(
                    value = shown,
                    onValueChange = { if (focused) text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.text),
                    cursorBrush = SolidColor(Palette.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = {
                        onSubmit(text.text)
                        focus.clearFocus()
                    }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (it.isFocused && !focused) text = TextFieldValue(url, selection = TextRange(0, url.length))
                        focused = it.isFocused
                    },
                )
            }
            if (!focused && shield != null) {
                HSpace(8.dp)
                Shield(shield, onToggleAllow)
            }
            if (focused && text.text.isNotEmpty()) {
                GIcon(
                    Ic.close, tint = Palette.muted, size = 18.dp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickableNoRipple { text = TextFieldValue("") },
                )
            }
        }
        if (onPage) {
            IconButton(onClick = if (loading) onStop else onReload) {
                GIcon(if (loading) Ic.close else Ic.refresh, tint = Palette.text)
            }
        } else {
            HSpace(14.dp)
        }
    }
}

@Composable
private fun StartPage(
    history: List<Visit>,
    current: Pair<String, String>?,
    onOpen: (String) -> Unit,
    onResume: () -> Unit,
    onClear: () -> Unit,
    watchMode: Boolean,
    onWatchMode: (Boolean) -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalContext.current
        .getSystemService(android.content.ClipboardManager::class.java)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
    ) {
        item {
            ScreenHeader(
                title = if (watchMode) "Was willst du sehen?" else "Was willst du laden?",
                eyebrow = "Browser",
                subtitle = if (watchMode) "Link einfügen – das Video startet direkt im Player."
                else "Öffne eine Seite und starte das Video. Die App findet es.",
            )
            VSpace(24.dp)
            de.marne.streamplayer.ui.design.Segmented(
                options = listOf("browse" to "Browsen", "watch" to "Nur ansehen"),
                selected = if (watchMode) "watch" else "browse",
                onSelect = { onWatchMode(it == "watch") },
            )
            if (watchMode) {
                VSpace(8.dp)
                QuietButton("Link aus der Zwischenablage ansehen", color = Palette.text) {
                    // Erst auf Tipp lesen: Android zeigt beim Lesen der Zwischenablage einen Hinweis.
                    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    Regex("""https?://\S+""").find(text.orEmpty())?.value?.let(onOpen)
                }
            }
        }
        if (current != null) {
            item {
                VSpace(32.dp)
                NoticeCard(
                    icon = Ic.browser,
                    title = current.second.ifBlank { Format.host(current.first) },
                    text = Format.host(current.first),
                    action = "Zurück",
                    onAction = onResume,
                )
            }
        }
        item {
            SectionTitle("Zuletzt besucht", action = if (history.isNotEmpty()) "Löschen" else null, onAction = onClear)
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Hier stehen die Seiten, die du öffnest.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.muted,
                )
            }
        } else {
            item {
                ListGroup {
                    history.forEachIndexed { i, v ->
                        if (i > 0) RowDivider()
                        ListRow(
                            title = v.title,
                            subtitle = Format.host(v.url),
                            icon = Ic.browser,
                            titleMaxLines = 1,
                            onClick = { onOpen(v.url) },
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, onClick = onClick)

/** Zustand des Werbeblockers für die Adressleiste. */
data class ShieldState(val blocked: Int, val allowed: Boolean, val host: String)

/** Schild mit Zähler; Tipp öffnet ein kleines Menü zum Erlauben/Blockieren dieser Seite. */
@Composable
private fun Shield(state: ShieldState, onToggle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GIcon(Ic.shield, tint = if (state.allowed) Palette.faint else Palette.muted, size = 16.dp)
            HSpace(4.dp)
            Text(
                if (state.allowed) "aus" else "${state.blocked}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.muted,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Palette.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Palette.line),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("WERBEBLOCKER", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(4.dp)
                Text(
                    when {
                        state.allowed -> "Auf ${state.host} aus."
                        state.blocked == 1 -> "1 Anfrage blockiert."
                        else -> "${state.blocked} Anfragen blockiert."
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        if (state.allowed) "Hier wieder blockieren" else "Auf dieser Seite erlauben",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                onClick = { open = false; onToggle() },
            )
        }
    }
}
