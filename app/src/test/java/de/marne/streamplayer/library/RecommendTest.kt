package de.marne.streamplayer.library

import de.marne.streamplayer.catalog.SeasonSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anlass: Empfehlungen und Regale der Startseite (1.7.0) sollen ohne Gerät nachprüfbar sein. */
class RecommendTest {

    private fun movie(id: String, genres: List<String>?, rating: Double? = null, votes: Int = 500, added: Long = 0) = LibTitle(
        id = id, kind = TitleKind.MOVIE, title = id.replaceFirstChar { it.uppercase() }, pageUrl = "https://x/$id",
        tmdb = TmdbInfo(1, null, null, null, null, null, null, genres = genres, rating = rating, votes = votes), addedAt = added,
    )

    private fun series(id: String, genres: List<String>, episodes: Int = 3) = LibTitle(
        id = id, kind = TitleKind.SERIES, title = id, pageUrl = null, template = "https://x/$id/{s}/{e}",
        seasons = listOf(SeasonSpec(1, episodes)), tmdb = TmdbInfo(2, null, null, null, null, null, null, genres = genres, votes = 500),
    )

    private fun done(at: Long = 1) = Progress(100_000, 100_000, true, at)
    private fun started(at: Long = 1) = Progress(60_000, 100_000, false, at)

    private val krimi = movie("krimi", listOf("Krimi", "Drama"), 7.0)
    private val krimi2 = movie("krimi2", listOf("Krimi"), 6.0)
    private val komoedie = movie("komoedie", listOf("Komödie"), 9.0)
    private val doku = movie("doku", listOf("Dokumentarfilm"), 8.0)
    private val all = listOf(krimi, krimi2, komoedie, doku)

    @Test fun ohneVerlaufEntscheidetDieBewertung() {
        assertEquals(listOf("komoedie", "doku", "krimi", "krimi2"), Recommend.forYou(all, emptyMap(), emptyList()).map { it.id })
    }

    @Test fun geschmackSchlaegtBewertung() {
        // Ein Krimi gesehen: der andere Krimi kommt vor die besser bewertete Komödie.
        val p = mapOf(Keys.movie(krimi) to done())
        val list = Recommend.forYou(all, p, emptyList()).map { it.id }
        assertEquals("krimi2", list.first())
        assertTrue("Fertig Gesehenes wird nicht empfohlen", "krimi" !in list)
    }

    @Test fun meineListeZaehltAlsVorliebe() {
        val list = Recommend.forYou(all, emptyMap(), listOf("doku")).map { it.id }
        assertEquals("doku", list.first())
    }

    @Test fun weilDuGeschautHast() {
        val p = mapOf(Keys.movie(krimi) to done(at = 10), Keys.movie(komoedie) to done(at = 5))
        val (anchor, list) = Recommend.becauseYouWatched(all + movie("krimi3", listOf("Krimi")), p)!!
        assertEquals("krimi", anchor.id)
        assertEquals(setOf("krimi2", "krimi3"), list.map { it.id }.toSet())
    }

    @Test fun weilDuGeschautHastBrauchtZweiTreffer() {
        assertNull(Recommend.becauseYouWatched(all, mapOf(Keys.movie(krimi) to done())))
    }

    @Test fun genreRegaleNurAbZweiTiteln() {
        val rows = Recommend.genreRows(all, emptyMap(), emptyList())
        assertEquals(listOf("Krimi"), rows.map { it.first })
        assertEquals(listOf("krimi", "krimi2"), rows.single().second.map { it.id })
    }

    @Test fun serieIstFertigErstMitAllenFolgen() {
        val s = series("s", listOf("Drama"), episodes = 2)
        val e = s.episodes()
        assertEquals(Recommend.Seen.STARTED, Recommend.seen(s, mapOf(Keys.episode(s, e[0]) to done())))
        assertEquals(Recommend.Seen.DONE, Recommend.seen(s, e.associate { Keys.episode(s, it) to done() }))
        assertEquals(Recommend.Seen.NONE, Recommend.seen(s, emptyMap()))
    }

    @Test fun bewertungMitZuWenigStimmenZaehltNicht() {
        val t = movie("x", listOf("Drama"), 10.0, votes = 3)
        assertNull(t.rating)
        assertEquals(emptyList<LibTitle>(), Recommend.topRated(listOf(t)))
    }

    @Test fun sucheFindetTitelUndBesetzungOhneAkzente() {
        val t = movie("amelie", listOf("Komödie")).let { it.copy(title = "Die fabelhafte Welt der Amélie", tmdb = it.tmdb!!.copy(cast = listOf("Audrey Tautou"))) }
        assertEquals(listOf(t), Recommend.search(listOf(t, doku), "amelie"))
        assertEquals(listOf(t), Recommend.search(listOf(t, doku), "tautou"))
        assertEquals(listOf(t), Recommend.search(listOf(t, doku), "komodie"))
        assertEquals(emptyList<LibTitle>(), Recommend.search(listOf(t, doku), "  "))
    }

    @Test fun auswertung() {
        val p = mapOf(Keys.movie(krimi) to done(at = System.currentTimeMillis()), Keys.movie(doku) to started(at = 0))
        val s = Recommend.stats(all, p, emptyList())
        assertEquals(160_000L, s.watchedMs)
        assertEquals(100_000L, s.weekMs)
        assertEquals(1, s.movies)
        // Der gesehene Film ist Krimi und Drama: beide gleich auf, vor der nur angefangenen Doku.
        assertEquals(listOf("Drama", "Krimi", "Dokumentarfilm"), s.topGenres.map { it.first })
    }

    // Anlass: Wer bis zum Abspann sprang, bekam die ganze Laufzeit als Sehzeit gutgeschrieben.
    @Test fun sehzeitZaehltNurGesehenes() {
        val now = System.currentTimeMillis()
        val skipped = Progress(100_000, 100_000, true, now, watchedMs = 5_000)
        val today = java.time.LocalDate.now().toEpochDay()
        val s = Recommend.stats(all, mapOf(Keys.movie(krimi) to skipped), emptyList(), days = mapOf(today to 5_000L, today - 10 to 40_000L), now = now)
        assertEquals(5_000L, s.watchedMs)
        assertEquals(5_000L, s.weekMs)
    }

    @Test fun sehzeitUeberlebtJsonUndAbgleich() {
        val a = Progress(10, 100, false, 2, watchedMs = 7)
        val back = ProgressMerge.fromJson(ProgressMerge.toJson(mapOf("m:x" to a)))
        assertEquals(a, back["m:x"])
        val old = ProgressMerge.fromJson(org.json.JSONObject("""{"m:x":{"p":10,"d":100,"f":false,"u":1}}"""))
        assertEquals(-1L, old["m:x"]!!.watchedMs)
        // Neuere Position, aber die größere Sehzeit bleibt.
        val merged = ProgressMerge.merge(mapOf("m:x" to Progress(50, 100, false, 1, 60)), mapOf("m:x" to a))
        assertEquals(10L, merged["m:x"]!!.positionMs)
        assertEquals(60L, merged["m:x"]!!.watchedMs)
    }

    @Test fun neueFelderUeberlebenJson() {
        val t = movie("k", listOf("Krimi", "Drama"), 7.5).let { it.copy(tmdb = it.tmdb!!.copy(tagline = "Ein Satz.", cast = listOf("A", "B"), creators = listOf("C"))) }
        val back = LibTitle.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t.tmdb, back.tmdb)
        // Alter Stand ohne Genres bleibt „noch nicht geholt“ (null), nicht „keine Genres“.
        val old = JSONObject(t.toJson().toString()).also { it.getJSONObject("tmdb").remove("genres") }
        assertNull(LibTitle.fromJson(old).tmdb!!.genres)
    }
}
