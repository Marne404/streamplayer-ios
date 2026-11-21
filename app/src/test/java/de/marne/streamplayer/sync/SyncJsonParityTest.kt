package de.marne.streamplayer.sync

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.TitleCollection
import de.marne.streamplayer.account.fromJson
import de.marne.streamplayer.account.toJson
import de.marne.streamplayer.catalog.EpisodeOverride
import de.marne.streamplayer.catalog.SeasonSpec
import de.marne.streamplayer.library.EpInfo
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.ProgressMerge
import de.marne.streamplayer.library.TitleKind
import de.marne.streamplayer.library.TmdbInfo
import de.marne.streamplayer.library.fromJson
import de.marne.streamplayer.library.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Der iPad-Leser (SyncJson, ohne org.json) muss genau das ergeben, was Android liest. */
class SyncJsonParityTest {
    private val full = LibTitle(
        id = "t1", kind = TitleKind.SERIES, title = "Schattenland", pageUrl = "https://example.org/a",
        template = "https://example.org/s{s}e{e}",
        seasons = listOf(SeasonSpec(1, 6), SeasonSpec(2, 8, start = 7, template = "https://x/{e}", title = "Zwei")),
        overrides = listOf(EpisodeOverride(1, 3, url = "https://x/3", title = "Drei")),
        poster = "https://image.tmdb.org/t/p/w780/a.jpg", backdrop = "https://site/b.jpg", customPoster = "file:///x/p.jpg",
        description = "Text mit „Anführungszeichen“ und \\ Backslash", durationMs = 123456789L, year = 2022,
        tmdbId = 42, tmdbQuery = "schatten",
        tmdb = TmdbInfo(42, "Shadow", "/p.jpg", "/b.jpg", "Übersicht", 2022, 48, listOf("Drama", "Mystery"), 7.9, 120, "Tagline", listOf("A", "B"), listOf("C")),
        sourceId = "cat-1",
        episodeInfo = mapOf("1:1" to EpInfo("Eins", "Inhalt", "/s.jpg", 47), "2:7" to EpInfo(null, null, null, null)),
        offline = mapOf("e:t1:1:1" to "content://x/1"),
        addedAt = 1_700_000_000_000L, libraryId = "anime", alsoIn = listOf("main", "x"),
    )
    private val bare = LibTitle(id = "t2", kind = TitleKind.MOVIE, title = "", pageUrl = null, addedAt = 5)

    @Test fun titlesRoundTrip() {
        for (t in listOf(full, bare, full.copy(tmdb = full.tmdb!!.copy(rating = null, genres = null, votes = null)))) {
            val text = t.toJson().toString()
            assertEquals(LibTitle.fromJson(JSONObject(text)), SyncJson.title(text))
            assertEquals(t, SyncJson.title(text))
        }
    }

    @Test fun legacyTitleFormats() {
        val old = """{"id":"o","kind":"SERIES","title":"Alt","seasons":[10,8],"image":"https://i/b.jpg","year":"2001","tmdb":{"id":7,"rating":8.5,"votes":3},"addedAt":12}"""
        assertEquals(LibTitle.fromJson(JSONObject(old)), SyncJson.title(old))
        val nulls = """{"id":"n","kind":"MOVIE","title":"N","pageUrl":null,"poster":"","tmdb":null,"seasons":[],"library":null}"""
        assertEquals(LibTitle.fromJson(JSONObject(nulls)), SyncJson.title(nulls))
    }

    @Test fun profilesLibrariesProgress() {
        val p = Profile("main", "Marne", 3, 9, listOf("a", "b"), "pbkdf2|20000|x|y", listOf(TitleCollection("c", "Abend", listOf("a"), 4)))
        val pt = p.toJson().toString()
        assertEquals(Profile.fromJson(JSONObject(pt)), SyncJson.profile(pt))
        val open = p.copy(lock = null, collections = emptyList()).toJson().toString()
        assertEquals(Profile.fromJson(JSONObject(open)), SyncJson.profile(open))

        for (l in listOf(Library(Library.MAIN, ""), Library("x", "Anime", 2, 7, shared = true, invite = "ABC"), Library("y", "Freund", joined = true, owner = "Kim"))) {
            val lt = l.toJson().toString()
            assertEquals(Library.fromJson(JSONObject(lt)), SyncJson.library(lt))
        }

        val prog = mapOf("e:t1:1:1" to Progress(1000, 2000, false, 5, 900), "m:t2" to Progress(7, 8, true, 9))
        val text = ProgressMerge.toJson(prog).toString()
        assertEquals(ProgressMerge.fromJson(JSONObject(text)), SyncJson.progress(text))
    }
}
