package de.marne.streamplayer.library

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.download.DownloadItem

/** Eine Seite über den Tabs: Detail, eine Übersicht oder die Downloads. Der Schlüssel hält ihren Zustand (Scrollen). */
sealed interface Route {
    val key: String

    /** [origin]: Schlüssel des Covers, aus dem die Seite aufgeht (sharedCover) – null ohne Flug. */
    data class Detail(val id: String, val origin: String? = null) : Route { override val key get() = "detail:$id" }
    data class Grid(val spec: GridSpec) : Route { override val key get() = "grid:${spec.kind}:${spec.arg}" }
    data object Downloads : Route { override val key get() = "downloads" }
    data object Settings : Route { override val key get() = "settings" }
}

/**
 * Was eine Übersicht zeigt. [kind]: genre (arg = Name), foryou, because (arg = Titel-ID), continue,
 * list, new, top, series, movies, offline, watched, all, nogenre, collection (arg = Sammlungs-ID).
 */
data class GridSpec(val kind: String, val arg: String? = null)

/** Alles, was die Bibliotheks-Seiten anzeigen – einmal in AppRoot gesammelt. */
class LibState(
    val titles: List<LibTitle>,
    val progress: Map<String, Progress>,
    val profile: Profile,
    val enrich: Enricher.Status,
    val downloads: List<DownloadItem>,
) {
    val myList: List<String> get() = profile.list
    fun inList(t: LibTitle) = t.id in profile.list
    val collections: List<de.marne.streamplayer.account.TitleCollection> get() = profile.collections
    fun collectionsWith(t: LibTitle) = profile.collections.filter { t.id in it.items }
    fun byId(id: String) = titles.firstOrNull { it.id == id }
}

/** Was die Bibliotheks-Seiten auslösen können. Umgesetzt in AppRoot (MainActivity). */
class LibActions(
    /** Detailseite öffnen. */
    val open: (LibTitle) -> Unit,
    /** Detailseite öffnen, das Cover [origin] fliegt hinein (siehe sharedCover). */
    val show: (LibTitle, String) -> Unit,
    /** Blatt „Zu Sammlung hinzufügen“. */
    val collect: (LibTitle) -> Unit,
    /** Neue, leere Sammlung anlegen (Name abfragen). */
    val newCollection: () -> Unit,
    val settings: () -> Unit,
    /** Kurzansicht (Blatt) wie bei Netflix: Abspielen, Meine Liste, „Details & Folgen“. */
    val preview: (LibTitle) -> Unit,
    /** Abspielen; bei Serien ohne Folge: die nächste fällige. */
    val play: (LibTitle, Episode?) -> Unit,
    val playContinue: (ContinueItem) -> Unit,
    val download: (LibTitle, Episode?) -> Unit,
    val toggleList: (LibTitle) -> Unit,
    /** Film oder ganze Serie als gesehen/ungesehen markieren. */
    val markWatched: (LibTitle, Boolean) -> Unit,
    val setWatched: (String, Boolean) -> Unit,
    val grid: (GridSpec) -> Unit,
    val categories: (TitleKind?) -> Unit,
    val search: () -> Unit,
    val profiles: () -> Unit,
    val import: () -> Unit,
    val browser: () -> Unit,
    val downloads: () -> Unit,
    val back: () -> Unit,
)
