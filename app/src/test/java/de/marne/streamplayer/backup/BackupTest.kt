package de.marne.streamplayer.backup

import de.marne.streamplayer.library.Progress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    // Anlass: Beim Zusammenführen wird ein Titel aus der Datei als schon vorhandener erkannt (gleiche
    // Katalog-ID, andere ID). Sein Fortschritt muss mit umziehen, sonst hinge er an einem Titel, den es nicht gibt.
    @Test fun fortschrittZiehtZurHiesigenIdUm() {
        val p = Progress(10, 100, false, 1, 5)
        val out = Backup.rekey(mapOf("m:alt" to p, "e:serie:1:2" to p, "m:bleibt" to p), mapOf("alt" to "neu", "serie" to "s2"))
        assertEquals(setOf("m:neu", "e:s2:1:2", "m:bleibt"), out.keys)
    }

    @Test fun fremdeDateiWirdAbgelehnt() {
        assertNull(Backup.read("""{"app":"anders","kind":"backup"}"""))
        assertNull(Backup.read("kein json"))
        val c = Backup.read(JSONObject().put("app", "streamplayer").put("kind", "backup").put("parts", org.json.JSONArray(listOf("list", "quatsch"))).toString())!!
        assertEquals(setOf(Backup.Part.LIST), c.parts)
    }
}
