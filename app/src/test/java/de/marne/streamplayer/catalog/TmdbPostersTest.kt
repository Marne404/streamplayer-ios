package de.marne.streamplayer.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Anlass (1.11.0): Cover ändern zeigt alle TMDB-Poster – Wunschsprache zuerst, dann Englisch, dann ohne Schrift. */
class TmdbPostersTest {
    @Test fun reihenfolge() {
        val o = JSONObject(
            """{"posters":[
              {"file_path":"/fr.jpg","iso_639_1":"fr","vote_average":9},
              {"file_path":"/none.jpg","iso_639_1":null,"vote_average":5},
              {"file_path":"/en.jpg","iso_639_1":"en","vote_average":4},
              {"file_path":"/de2.jpg","iso_639_1":"de","vote_average":3},
              {"file_path":"/de1.jpg","iso_639_1":"de","vote_average":6},
              {"iso_639_1":"de"}
            ]}"""
        )
        assertEquals(listOf("/de1.jpg", "/de2.jpg", "/en.jpg", "/none.jpg", "/fr.jpg"), Tmdb.parsePosters(o, "de"))
    }
}
