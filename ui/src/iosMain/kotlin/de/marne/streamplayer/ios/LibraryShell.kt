package de.marne.streamplayer.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.library.CategorySheet
import de.marne.streamplayer.library.CollectionSheet
import de.marne.streamplayer.library.DetailActions
import de.marne.streamplayer.library.DetailScreen
import de.marne.streamplayer.library.DiscoverScreen
import de.marne.streamplayer.library.EnrichStatus
import de.marne.streamplayer.library.GridScreen
import de.marne.streamplayer.library.GridSpec
import de.marne.streamplayer.library.HomeScreen
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibActions
import de.marne.streamplayer.library.LibState
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.LibraryHost
import de.marne.streamplayer.library.LibraryPickSheet
import de.marne.streamplayer.library.LocalLibraryHost
import de.marne.streamplayer.library.LocalPageScope
import de.marne.streamplayer.library.LocalSharedScope
import de.marne.streamplayer.library.MineScreen
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.QuickSheet
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.nowMillis
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.painterResource

/** Seiten über den Reitern: Detail und Raster. */
private sealed interface Route {
    val key: String

    data class Detail(val id: String, val origin: String?) : Route {
        override val key get() = "d:$id"
    }

    data class Grid(val spec: GridSpec) : Route {
        override val key get() = "g:${spec.kind}:${spec.arg}"
    }
}

/** Was die Hülle von außen bekommt – Beispieltitel oder Daten aus dem Konto. */
internal class ShellData(
    val titles: List<LibTitle>,
    val libraries: List<Library>,
    val profile: Profile,
    val progress: Map<String, Progress>,
)

/** Ändern erlaubt (Beispieltitel: nur im Speicher) oder nur ansehen (Konto: Schreiben kommt noch). */
internal class ShellEdits(
    val toggleList: ((LibTitle) -> Unit)?,
    val setWatched: ((List<String>, Boolean) -> Unit)?,
    val rename: ((LibTitle, String) -> Unit)?,
    val remove: ((LibTitle) -> Unit)?,
    /** Fortschritt beim Abspielen (Konto: wird gespeichert, Beispieltitel: nur im Speicher). */
    val progress: de.marne.streamplayer.ios.play.ProgressSink,
)

/**
 * Die Bibliothek wie in der Android-App: Reiter Start/Entdecken/Meins, Detail- und Rasterseiten,
 * Blätter. Daten und was „Profile“/„Einstellungen“ tun, kommen von außen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun LibraryShell(
    data: ShellData,
    host: LibraryHost,
    edits: ShellEdits,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    notice: String?,
    onNotice: (String?) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Route>() }
    var forward by remember { mutableStateOf(true) }
    var quick by remember { mutableStateOf<LibTitle?>(null) }
    var category by remember { mutableStateOf<TitleKind?>(null) }
    var categoryOpen by remember { mutableStateOf(false) }
    var collecting by remember { mutableStateOf<LibTitle?>(null) }
    var activeLibrary by remember { mutableStateOf(Library.ALL) }
    var pickingLibrary by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<de.marne.streamplayer.ios.play.PlayRequest?>(null) }

    /** Wie auf Android: Serie ohne Folge → die nächste fällige; angefangen → dort weiter. */
    fun playRequest(t: LibTitle, ep: de.marne.streamplayer.library.Episode?): de.marne.streamplayer.ios.play.PlayRequest? {
        val e = if (t.kind == TitleKind.SERIES) ep ?: de.marne.streamplayer.library.Shelves.nextFor(t, data.progress)?.first ?: t.episodes().firstOrNull() else null
        if (t.kind == TitleKind.SERIES && e == null) return null
        val key = if (e != null) Keys.episode(t, e) else Keys.movie(t)
        val p = data.progress[key]
        val start = if (p != null && !p.finished && p.positionMs > Progress.MIN_RESUME_MS) p.positionMs else 0L
        return de.marne.streamplayer.ios.play.PlayRequest(t, e, key, start)
    }

    fun push(r: Route) { forward = true; stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) { forward = false; stack.removeAt(stack.lastIndex) } }
    fun later(what: String) = onNotice(tr("$what kommt in einer späteren Etappe.", "$what comes in a later stage."))
    fun readOnly() = onNotice(tr("Ändern vom iPad aus kommt in der nächsten Etappe – bis dahin nur ansehen.", "Editing from the iPad comes in the next stage – view only for now."))

    val visible = remember(data.titles, activeLibrary) {
        if (activeLibrary == Library.ALL) data.titles else data.titles.filter { it.isIn(activeLibrary) }
    }
    val state = LibState(
        titles = visible, progress = data.progress, profile = data.profile, enrich = EnrichStatus(),
        downloads = emptyList(), all = data.titles, libraries = data.libraries, activeLibrary = activeLibrary,
    )
    val actions = LibActions(
        open = { push(Route.Detail(it.id, null)) },
        show = { t, origin -> push(Route.Detail(t.id, origin)) },
        collect = { if (edits.toggleList != null) collecting = it else readOnly() },
        newCollection = { if (edits.toggleList != null) host.createCollection(tr("Neue Sammlung", "New collection")) else readOnly() },
        settings = onSettings,
        preview = { quick = it },
        play = { t, ep -> playing = playRequest(t, ep) ?: run { onNotice(tr("Keine Folge zum Abspielen.", "No episode to play.")); null } },
        playContinue = { c -> playing = playRequest(c.title, c.episode) },
        download = { _, _ -> later(tr("Laden", "Downloading")) },
        offlineMenu = { _, _ -> later(tr("Laden", "Downloading")) },
        downloadSeason = { _, _ -> later(tr("Laden", "Downloading")) },
        deleteOffline = { _, _ -> },
        toggleList = { t -> edits.toggleList?.invoke(t) ?: readOnly() },
        markWatched = { t, w ->
            val set = edits.setWatched
            if (set == null) readOnly()
            else if (t.kind == TitleKind.MOVIE) set(listOf(Keys.movie(t)), w)
            else set(t.episodes().map { Keys.episode(t, it) }, w)
        },
        setWatched = { k, w -> edits.setWatched?.invoke(listOf(k), w) ?: readOnly() },
        grid = { push(Route.Grid(it)) },
        categories = { category = it; categoryOpen = true },
        search = { stack.clear(); tab = 1 },
        profiles = onProfiles,
        import = { later(tr("Katalog importieren", "Catalog import")) },
        browser = { later(tr("Der Browser", "The browser")) },
        downloads = { later(tr("Downloads", "Downloads")) },
        libraries = { if (data.libraries.size > 1) pickingLibrary = true else onNotice(tr("Es gibt nur eine Bibliothek.", "There is only one library.")) },
        back = ::pop,
    )
    val detail = DetailActions(
        openPage = { later(tr("Die Webseite", "The web page")) },
        rename = { t, name -> edits.rename?.invoke(t, name) ?: readOnly() },
        changeCover = { readOnly() },
        resetCover = { readOnly() },
        matchSheet = null,
        coverSheet = { null },
        setLibraries = { _, _ -> readOnly() },
        removeFrom = { _, _ -> readOnly() },
        setCover = { _, _ -> readOnly() },
        refreshTmdb = null,
        remove = { t -> val r = edits.remove; if (r == null) readOnly() else { pop(); r(t) } },
    )

    CompositionLocalProvider(LocalLibraryHost provides host) {
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            Pages(tab, stack.lastOrNull(), forward, state, actions, detail, onMissing = ::pop)
            AnimatedVisibility(
                visible = stack.isEmpty() && playing == null,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                FloatingNav(tab) { tab = it }
            }
            playing?.let { req ->
                de.marne.streamplayer.ios.play.PlayFlow(
                    req, edits.progress,
                    onNext = { e -> playing = playRequest(req.title, e)?.copy(startMs = 0) },
                    onClose = { playing = null },
                )
            }
            notice?.let { text ->
                LaunchedEffect(text) { delay(2800); onNotice(null) }
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 60.dp).widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Palette.ink).padding(horizontal = 18.dp, vertical = 12.dp),
                ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.onInk) }
            }
        }
        quick?.let { t -> QuickSheet(t, state, actions) { quick = null } }
        if (categoryOpen) CategorySheet(state, category, actions) { categoryOpen = false }
        collecting?.let { t -> CollectionSheet(t, state) { collecting = null } }
        if (pickingLibrary) LibraryPickSheet(
            tr("BIBLIOTHEK", "LIBRARY"), tr("Welche Bibliothek?", "Which library?"),
            listOf(Library(Library.ALL, tr("Alle Bibliotheken", "All libraries"))) + data.libraries,
            activeLibrary,
            onPick = { activeLibrary = it; pickingLibrary = false; stack.clear() },
            onDismiss = { pickingLibrary = false },
        )
    }
}

/** [LibraryHost] für die Beispieltitel: Profil und Sammlungen nur im Speicher. */
internal class DemoHost : LibraryHost {
    val profile = MutableStateFlow(DemoData.profile)
    override val tmdbAvailable = false
    override fun prioritizeEnrich(titleId: String) = Unit
    override val watchDays: StateFlow<Map<Long, Long>> = MutableStateFlow(DemoData.watchDays)
    override val homeContinue: StateFlow<Boolean> = MutableStateFlow(true)
    override val homeMyList: StateFlow<Boolean> = MutableStateFlow(true)

    override fun createCollection(name: String, firstTitle: String?): String {
        val id = "c${nowMillis()}"
        val c = de.marne.streamplayer.account.TitleCollection(id, name, listOfNotNull(firstTitle), nowMillis())
        profile.update { it.copy(collections = listOf(c) + it.collections) }
        return id
    }

    override fun renameCollection(id: String, name: String) =
        profile.update { p -> p.copy(collections = p.collections.map { if (it.id == id) it.copy(name = name) else it }) }

    override fun deleteCollection(id: String) =
        profile.update { p -> p.copy(collections = p.collections.filterNot { it.id == id }) }

    override fun toggleInCollection(id: String, titleId: String) = profile.update { p ->
        p.copy(collections = p.collections.map { c ->
            if (c.id != id) c else c.copy(items = if (titleId in c.items) c.items - titleId else listOf(titleId) + c.items)
        })
    }
}

/** Die Beispieltitel: alles änderbar, aber nur im Speicher. */
@Composable
internal fun DemoLibrary(onProfiles: () -> Unit, onSettings: () -> Unit) {
    val host = remember { DemoHost() }
    val profile: Profile by host.profile.collectAsState()
    var titles by remember { mutableStateOf(DemoData.titles) }
    var progress by remember { mutableStateOf(DemoData.progress) }
    var notice by remember { mutableStateOf<String?>(null) }
    LibraryShell(
        data = ShellData(titles, listOf(DemoData.library), profile, progress),
        host = host,
        edits = ShellEdits(
            toggleList = { t -> host.profile.update { p -> p.copy(list = if (t.id in p.list) p.list - t.id else listOf(t.id) + p.list) } },
            setWatched = { keys, w -> progress = if (w) progress + keys.associateWith { Progress(1, 1, true, nowMillis(), 0) } else progress - keys.toSet() },
            rename = { t, name -> titles = titles.map { if (it.id == t.id) it.copy(title = name) else it } },
            remove = { t -> titles = titles.filterNot { it.id == t.id } },
            progress = { key, pos, dur, ended, played ->
                val finished = ended || (dur > 0 && dur - pos < maxOf(120_000L, dur / 20))
                val before = progress[key]?.countedMs ?: 0
                progress = progress + (key to Progress(pos, dur, finished, nowMillis(), before + played))
            },
        ),
        onProfiles = onProfiles,
        onSettings = onSettings,
        notice = notice,
        onNotice = { notice = it },
    )
}

/** Wie AppRoot auf Android: ein Rahmen, in dem das Cover von der Kachel in die Detailseite fliegt. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Pages(
    tab: Int, route: Route?, forward: Boolean, state: LibState, actions: LibActions, detail: DetailActions,
    onMissing: () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
    val target = (route?.key ?: "tab:$tab") to route
    SharedTransitionLayout {
        val sharedScope = this
        AnimatedContent(
            targetState = target,
            contentKey = { it.first },
            transitionSpec = {
                val tabs = initialState.second == null && targetState.second == null
                val flight = (targetState.second as? Route.Detail)?.origin != null && forward ||
                    (initialState.second as? Route.Detail)?.origin != null && !forward
                when {
                    tabs -> fadeIn(tween(320, easing = spring)) togetherWith fadeOut(tween(200))
                    flight -> fadeIn(tween(360, delayMillis = 60, easing = spring)) togetherWith fadeOut(tween(240, easing = spring))
                    forward -> (slideInHorizontally(tween(420, easing = spring)) { it / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(420, easing = spring)) { -it / 10 } + fadeOut(tween(220)))
                    else -> (slideInHorizontally(tween(380, easing = spring)) { -it / 10 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(380, easing = spring)) { it / 3 } + fadeOut(tween(200)))
                }.apply { targetContentZIndex = if (forward) 1f else 0f }
            },
            label = "page",
        ) { (key, r) ->
            CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalPageScope provides this) {
                holder.SaveableStateProvider(key) {
                    when (r) {
                        is Route.Detail -> {
                            val t = state.anyById(r.id)
                            if (t != null) DetailScreen(t, state, actions, detail, r.origin)
                            else LaunchedEffect(Unit) { onMissing() }
                        }
                        is Route.Grid -> GridScreen(r.spec, state, actions)
                        null -> when (tab) {
                            1 -> DiscoverScreen(state, actions, focusSearch = false, onFocused = {})
                            2 -> MineScreen(state, actions)
                            else -> HomeScreen(state, actions)
                        }
                    }
                }
            }
        }
    }
}

/** Die schwebende Leiste aus der Android-App (FloatingNav), schmaler auf dem großen Bildschirm. */
@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        Triple(tr("Start", "Home"), Ic.library, Ic.libraryFilled),
        Triple(tr("Entdecken", "Discover"), Ic.search, Ic.search),
        Triple(tr("Meins", "My Stuff"), Ic.bookmark, Ic.bookmarkFilled),
    )
    val bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().height(60.dp)
                .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(shape).background(Palette.surface).border(1.dp, Palette.line, shape),
        ) {
            val w = maxWidth / tabs.size
            val x by animateDpAsState(w * selected, tween(420, easing = bouncy), label = "navPill")
            Box(Modifier.padding(6.dp).width(w - 12.dp).fillMaxSize().offset(x = x).clip(RoundedCornerShape(24.dp)).background(Palette.surface3))
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, (label, icon, iconSel) ->
                    val isSel = i == selected
                    val tint by animateColorAsState(if (isSel) Palette.text else Palette.muted, tween(220), label = "navTint")
                    Row(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(if (isSel) iconSel else icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        AnimatedVisibility(
                            visible = isSel,
                            enter = fadeIn(tween(220)) + expandHorizontally(tween(320, easing = bouncy)),
                            exit = fadeOut(tween(160)) + shrinkHorizontally(tween(220)),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
