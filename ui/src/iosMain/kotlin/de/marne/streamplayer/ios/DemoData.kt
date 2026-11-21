package de.marne.streamplayer.ios

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.TitleCollection
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.library.EpInfo
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.library.TmdbInfo
import de.marne.streamplayer.ui.nowMillis

/**
 * Erfundene Beispieltitel für die iPad-Vorschau (Etappe 1). Keine echten Filme, keine Bilder –
 * echte Daten kommen in Etappe 2 aus dem Konto.
 */
object DemoData {
    private const val DAY = 24L * 3600 * 1000

    private fun movie(
        id: String, title: String, year: Int, minutes: Int, genres: List<String>, rating: Double,
        overview: String, tagline: String? = null, cast: List<String> = emptyList(), ageDays: Long = 30,
    ) = LibTitle(
        id = id, kind = TitleKind.MOVIE, title = title, pageUrl = "https://example.org/film/$id",
        year = year,
        tmdb = TmdbInfo(
            id = id.hashCode() and 0xffff, title = title, poster = null, backdrop = null, overview = overview,
            year = year, runtimeMin = minutes, genres = genres, rating = rating, votes = 400,
            tagline = tagline, cast = cast,
        ),
        addedAt = nowMillis() - ageDays * DAY,
    )

    private fun series(
        id: String, title: String, year: Int, seasons: List<Int>, genres: List<String>, rating: Double,
        overview: String, tagline: String? = null, cast: List<String> = emptyList(), creators: List<String> = emptyList(),
        ageDays: Long = 30, episodeNames: List<String> = emptyList(),
    ): LibTitle {
        val specs = seasons.mapIndexed { i, n -> SeasonSpec(number = i + 1, episodes = n) }
        val info = buildMap {
            episodeNames.forEachIndexed { i, name ->
                put("1:${i + 1}", EpInfo(name, "Eine Folge mit Wendung. Danach ist nichts mehr wie vorher.", null, 48))
            }
        }
        return LibTitle(
            id = id, kind = TitleKind.SERIES, title = title, pageUrl = null,
            template = "https://example.org/serie/$id/staffel-{s}/folge-{e}",
            seasons = specs, year = year, episodeInfo = info,
            tmdb = TmdbInfo(
                id = id.hashCode() and 0xffff, title = title, poster = null, backdrop = null, overview = overview,
                year = year, runtimeMin = 48, genres = genres, rating = rating, votes = 900,
                tagline = tagline, cast = cast, creators = creators,
            ),
            addedAt = nowMillis() - ageDays * DAY,
        )
    }

    val titles: List<LibTitle> = listOf(
        series(
            "schattenland", "Schattenland", 2022, listOf(6, 8), listOf("Mystery", "Thriller", "Drama"), 7.9,
            "In einem Bergdorf verschwinden nachts die Schatten der Menschen. Eine Polizistin folgt der Spur bis in den alten Stollen.",
            tagline = "Manche Türen bleiben besser zu.", cast = listOf("Lena Hartwig", "Jonas Brandt", "Mira Okafor"),
            creators = listOf("Paul Winter"), ageDays = 2,
            episodeNames = listOf("Kapitel 1", "Kapitel 2", "Die Lampe", "Stollen", "Nachtschicht", "Ohne Schatten"),
        ),
        series(
            "nordlicht", "Nordlicht", 2021, listOf(10, 10, 8), listOf("Drama", "Abenteuer"), 8.2,
            "Eine Fischerfamilie auf den Lofoten hält ihren Hof gegen Sturm, Schulden und ein Geheimnis im Eis.",
            cast = listOf("Sigrid Aune", "Tom Haller"), ageDays = 40,
        ),
        movie(
            "orbit", "Orbit", 2023, 118, listOf("Science-Fiction", "Thriller"), 7.4,
            "Drei Ingenieurinnen reparieren eine Raumstation, die langsam aus der Bahn fällt – und nur eine passt in die Kapsel.",
            tagline = "Die Erde ist 400 Kilometer entfernt.", cast = listOf("Ada Lind", "Noor Haddad"), ageDays = 12,
        ),
        movie(
            "tiefsee", "Tiefsee", 2020, 104, listOf("Dokumentarfilm"), 8.6,
            "Mit einem Tauchboot in 4.000 Meter Tiefe: Licht, das Tiere selbst machen, und Landschaften ohne Sonne.",
            ageDays = 90,
        ),
        movie(
            "papierdrache", "Der Papierdrache", 2019, 92, listOf("Animation", "Familie"), 7.8,
            "Ein Drache aus Zeitungspapier will fliegen lernen, bevor der Herbstwind ihn mitnimmt.",
            ageDays = 120,
        ),
        series(
            "glasstadt", "Glasstadt", 2024, listOf(8), listOf("Science-Fiction", "Drama"), 7.1,
            "In einer Stadt aus Glas sieht jeder alles – bis ein Mann verschwindet, den niemand gesehen hat.",
            ageDays = 5,
        ),
        movie(
            "funkstille", "Funkstille", 2018, 101, listOf("Thriller", "Krimi"), 6.9,
            "Ein Radiomoderator bekommt nachts Anrufe von einer Hörerin, die Dinge weiß, die noch nicht passiert sind.",
            ageDays = 200,
        ),
        series(
            "kupferkueste", "Kupferküste", 2023, listOf(6, 6), listOf("Krimi", "Drama"), 7.6,
            "Eine Kommissarin kehrt an die Küste ihrer Kindheit zurück – und in einen Fall, der nie abgeschlossen wurde.",
            ageDays = 20,
        ),
        movie(
            "uhrmacherin", "Die Uhrmacherin", 2022, 112, listOf("Drama", "Historie"), 8.0,
            "Wien, 1910: Eine junge Frau übernimmt heimlich die Werkstatt ihres Vaters und baut eine Uhr, die rückwärts läuft.",
            ageDays = 60,
        ),
        movie(
            "leuchtfeuer", "Leuchtfeuer", 2021, 96, listOf("Abenteuer", "Familie"), 7.3,
            "Zwei Geschwister verbringen den Sommer auf einer Insel mit einem Leuchtturm, der jede Nacht ein anderes Zeichen sendet.",
            ageDays = 75,
        ),
        series(
            "morgengrau", "Morgengrau", 2020, listOf(10), listOf("Mystery", "Drama"), 7.0,
            "Jeden Morgen wacht in einer Kleinstadt ein anderer Mensch ohne Erinnerung auf.",
            ageDays = 150,
        ),
        movie(
            "salzwiesen", "Salzwiesen", 2024, 88, listOf("Komödie", "Drama"), 7.2,
            "Ein Berliner Koch erbt eine Imbissbude am Deich und lernt, dass hier niemand Tapas will.",
            ageDays = 3,
        ),
        movie(
            "halbmond", "Halbmond", 2017, 124, listOf("Science-Fiction", "Abenteuer"), 7.7,
            "Eine Expedition zur dunklen Seite des Mondes findet Spuren einer Station, die es nie gegeben hat.",
            ageDays = 300,
        ),
        series(
            "eisenbahnwinter", "Eisenbahnwinter", 2019, listOf(4), listOf("Drama", "Historie"), 8.4,
            "Ein Zug bleibt im Winter 1947 eingeschneit stehen. Vier Folgen, vier Tage, zweihundert Reisende.",
            ageDays = 45,
        ),
    )

    val library = Library(Library.MAIN, "")

    val profile = Profile(
        id = Profile.DEFAULT_ID, name = "Marne", color = 0, createdAt = 0,
        list = listOf("uhrmacherin", "glasstadt", "halbmond"),
        collections = listOf(
            TitleCollection("abende", "Filmabend", listOf("tiefsee", "orbit", "leuchtfeuer"), 0),
        ),
    )

    /** Angefangenes und Gesehenes – damit „Weiterschauen“, „Weil du … geschaut hast“ und die Statistik etwas zeigen. */
    val progress: Map<String, Progress> = buildMap {
        val now = nowMillis()
        val byId = titles.associateBy { it.id }
        fun ep(id: String, s: Int, e: Int) = byId.getValue(id).let { t -> Keys.episode(t, t.episode(s, e)!!) }
        put(ep("nordlicht", 1, 1), Progress(48 * 60_000L, 48 * 60_000L, true, now - 3 * DAY, 48 * 60_000L))
        put(ep("nordlicht", 1, 2), Progress(22 * 60_000L, 48 * 60_000L, false, now - 1 * DAY, 22 * 60_000L))
        put(Keys.movie(byId.getValue("orbit")), Progress(64 * 60_000L, 118 * 60_000L, false, now - 2 * 3600_000L, 64 * 60_000L))
        put(Keys.movie(byId.getValue("tiefsee")), Progress(104 * 60_000L, 104 * 60_000L, true, now - 6 * DAY, 104 * 60_000L))
        put(ep("schattenland", 1, 1), Progress(48 * 60_000L, 48 * 60_000L, true, now - 4 * DAY, 48 * 60_000L))
    }

    /** Sehzeit je Tag der letzten Woche (Statistik in „Meins“). */
    val watchDays: Map<Long, Long> = run {
        val today = de.marne.streamplayer.ui.epochDay(nowMillis())
        mapOf(today to 64 * 60_000L, today - 1 to 22 * 60_000L, today - 3 to 48 * 60_000L, today - 4 to 48 * 60_000L, today - 6 to 104 * 60_000L)
    }
}
