package de.marne.streamplayer.admin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Liest echte Antworten des Statistik-Workers (src/test/resources, aufgenommen mit einer
 * Test-Installation, die danach wieder gelöscht wurde). Anlass: Die Feldnamen stehen zweimal – in
 * stats/src/admin.js und hier. Ein Tippfehler zeigte in der App still eine 0 statt eines Fehlers.
 */
class ModelTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json").readText())

    @Test fun overview() {
        val o = parseOverview(fixture("overview"))
        assertEquals(1, o.activeToday)
        assertEquals(1, o.active7)
        assertEquals(1, o.active30)
        assertEquals(3_660_000L, o.watch7)
        assertEquals(6, o.plays30)
        assertEquals(1, o.installs)
        assertEquals(1, o.withAccount)
        assertEquals(0, o.withoutAccount)
        assertEquals(30, o.days.size)
        assertEquals(1, o.days[28].dlFailed)
        assertEquals(1, o.days[28].crashes)
        assertEquals(listOf(VersionShare("1.12.0", 24, 1)), o.versions)
    }

    @Test fun users() {
        val u = parseUsers(fixture("users")).single()
        assertEquals("Testkonto", u.accountName)
        assertEquals("Testkonto", u.label)
        assertEquals(123_456_789L, u.counts.bytesStored)
        assertEquals(3_660_000L, u.watch7)
        assertEquals(4, u.sessions7)
    }

    @Test fun user() {
        val d = parseUser(fixture("user"))
        assertEquals(36, d.user.sdk)
        assertEquals(30, d.days.size)
        assertEquals(3, d.days[28].sessions)
        assertEquals("example.org", d.domains.single().domain)
        assertEquals(listOf("1.12.0"), d.crashes.single().versions)
    }

    @Test fun problems() {
        val p = parseProblems(fixture("problems"))
        assertEquals(0.5, p.dlRate!!, 1e-9)
        assertEquals(30, p.dlDays.size)
        // Ohne beendete Downloads gibt es keine Quote – nicht 0.
        assertNull(p.dlDays.first().rate)
        assertEquals(0.5, p.dlDays[28].rate!!, 1e-9)
        assertEquals(1, p.domains.single().installs)
        assertEquals(1, p.crashes.single().installs)
    }

    @Test fun ohneKontoZeigtDieId() {
        val u = parseUsers(JSONObject("""{"users":[{"install":"3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b","account":null}]}""")).single()
        assertNull(u.accountName)
        assertEquals("Installation 3f2b8c1e", u.label)
    }

    @Test fun formate() {
        assertEquals("45 Min", duration(45 * 60_000L))
        assertEquals("1 Std", duration(60 * 60_000L))
        assertEquals("3 Std 20 Min", duration(200 * 60_000L))
        assertEquals("1.234 Std", duration(1234 * 3_600_000L))
        assertEquals("1,0 GB", bytes(1024L * 1024 * 1024))
        assertEquals("12 %", percent(0.123))
        assertEquals("5,0 %", percent(0.05))
        assertEquals("–", percent(null))
    }
}
