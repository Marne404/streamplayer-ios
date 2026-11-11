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
    /** Titel der gezeigten Bibliothek (bei „Alle“: alle). Listen und Sammlungen filtern hierüber. */
    val titles: List<LibTitle>,
    val progress: Map<String, Progress>,
    val profile: Profile,
    val enrich: Enricher.Status,
    val downloads: List<DownloadItem>,
    /** Downloads, deren Video noch gesucht wird: Schlüssel → sucht gerade (true) oder wartet (false). */
    val pending: Map<String, Boolean> = emptyMap(),
    /** Wirklich alle Titel – für Detailseite, Downloads, alles, was über Bibliotheken hinweg gilt. */
    val all: List<LibTitle> = titles,
    val libraries: List<Library> = listOf(Library(Library.MAIN, "")),
    /** Gezeigte Bibliothek oder [Library.ALL]. */
    val activeLibrary: String = Library.ALL,
) {
    val library: Library? get() = libraries.firstOrNull { it.id == activeLibrary }
    fun anyById(id: String) = all.firstOrNull { it.id == id }

    /** Ein Zustand für Film oder Folge, überall gleich (siehe OfflineState). */
    fun offline(t: LibTitle, key: String) = de.marne.streamplayer.download.Offline.state(key, t.offline[key], downloads, pending)

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
    /** Blatt zu einem Download: Stand, Anhalten, Abbrechen, Abspielen, Löschen. */
    val offlineMenu: (LibTitle, Episode?) -> Unit,
    /** Alle noch fehlenden Folgen einer Staffel laden. */
    val downloadSeason: (LibTitle, Int) -> Unit,
    /** Geladene Dateien löschen (Schlüssel); Titel und Fortschritt bleiben. */
    val deleteOffline: (LibTitle, List<String>) -> Unit,
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
    /** Bibliotheken: wechseln, anlegen, bearbeiten (Blatt). */
    val libraries: () -> Unit,
    val back: () -> Unit,
)
