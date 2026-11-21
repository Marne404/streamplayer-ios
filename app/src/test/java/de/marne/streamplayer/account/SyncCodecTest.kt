package de.marne.streamplayer.account

import de.marne.streamplayer.library.fromJson

import de.marne.streamplayer.library.toJson

import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.ProgressMerge
import de.marne.streamplayer.library.TitleKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCodecTest {
    private fun flat(o: JSONObject): Map<String, String> = o.keys().asSequence().associateWith { o.get(it).toString() }

    private val t = LibTitle(
        id = "t1", kind = TitleKind.SERIES, title = "Serie", pageUrl = null,
        poster = "file:///data/user/0/de.marne.streamplayer/files/posters/cat-poster-1.jpg",
        backdrop = "content://media/external/video/7",
        customPoster = "https://image.tmdb.org/t/p/w780/x.jpg",
        offline = mapOf("e:t1:1:1" to "content://media/external/video/7"),
    )

    // Anlass: Gerätepfade und Downloads dürfen nicht ins Konto – auf dem anderen Gerät zeigen sie ins Leere.
    @Test fun titleJsonDropsDeviceThings() {
        val o = JSONObject(SyncCodec.titleJson(t))
        assertEquals("cover:cat-poster-1.jpg", o.getString("poster"))
        assertFalse(o.has("backdrop"))
        assertEquals("https://image.tmdb.org/t/p/w780/x.jpg", o.getString("customPoster"))
        assertFalse(o.has("offline"))
        assertEquals(listOf("cat-poster-1.jpg"), SyncCodec.coverNames(o.toString()))
    }

    // Anlass: ein noch nicht geholtes Bild darf beim Rückweg das Bild im Konto nicht löschen.
    @Test fun missingCoverKeepsPlaceholder() {
        val json = SyncCodec.titleJson(t)
        assertEquals("cover:cat-poster-1.jpg", SyncCodec.resolveCovers(json) { null }.getString("poster"))
        assertEquals("file:///x/cat-poster-1.jpg", SyncCodec.resolveCovers(json) { "file:///x/$it" }.getString("poster"))
    }

    @Test fun roundTripIsStable() {
        val local = t.copy(backdrop = null, offline = emptyMap())
        val json = SyncCodec.titleJson(local)
        val back = LibTitle.fromJson(SyncCodec.resolveCovers(json) { "file:///data/user/0/de.marne.streamplayer/files/posters/$it" })
        assertEquals(flat(JSONObject(json)), flat(JSONObject(SyncCodec.titleJson(back))))
    }

    // Anlass: „nicht gesehen“ auf einem Gerät muss den älteren „gesehen“-Stand des anderen schlagen.
    @Test fun progressMergeNewerWins() {
        val local = mapOf("e:t1:1:1" to Progress(100, 1000, true, 10), "e:t1:1:2" to Progress(5, 1000, false, 50))
        val remote = mapOf("e:t1:1:1" to Progress(0, 1000, false, 20), "e:t1:1:3" to Progress(7, 1000, false, 5))
        val m = ProgressMerge.merge(local, remote)
        assertFalse(m.getValue("e:t1:1:1").finished)
        assertEquals(5, m.getValue("e:t1:1:2").positionMs)
        assertTrue("e:t1:1:3" in m)
    }

    @Test fun progressGroupsByTitle() {
        val g = ProgressMerge.byTitle(mapOf("e:a:1:1" to Progress(1, 2, false, 3), "e:a:1:2" to Progress(1, 2, false, 3), "m:b" to Progress(1, 2, false, 3)))
        assertEquals(setOf("a", "b"), g.keys)
        assertEquals(2, g.getValue("a").size)
    }

    // Anlass: derselbe Katalog auf zwei Geräten importiert – der Fortschritt muss mit zur gemeinsamen ID.
    @Test fun rekeyMovesOnlyThatTitle() {
        assertEquals("e:new:2:5", SyncCodec.rekey("e:old:2:5", "old", "new"))
        assertEquals("m:new", SyncCodec.rekey("m:old", "old", "new"))
        assertNull(SyncCodec.rekey("e:other:1:1", "old", "new"))
    }
}
