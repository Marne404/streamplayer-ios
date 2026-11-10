package de.marne.streamplayer.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleSetTest {

    private val rules = FilterParser().add(
        """
        [Adblock Plus 2.0]
        ! Kommentar
        ||ads.example^
        ||tracker.example^${'$'}third-party
        ||popunder.example^${'$'}popup
        ||scam.example^${'$'}document
        ||cdn.example/ads/*.js|
        @@||cdn.example/ads/allowed.js|
        @@||good.ads.example^
        ||typed.example^${'$'}script
        ||restricted.example^${'$'}domain=foo.com
        0.0.0.0 hosts.example
        ##.ad-banner
        news.example##.sponsor
        ~shop.example##.promo
        news.example#@#.ad-banner
        example.org##+js(nobab)
        example.org#?#.x:has-text(Werbung)
        """.trimIndent()
    ).build()

    private fun blocks(url: String, page: String = "https://site.example/") =
        rules.blocks(url, RuleSet.hostOf(url)!!, RuleSet.hostOf(page))

    @Test fun hostRules() {
        assertTrue(blocks("https://ads.example/banner.js"))
        assertTrue(blocks("https://sub.ads.example/x.gif"))
        assertTrue(blocks("http://hosts.example/a"))
        assertFalse(blocks("https://notads.example/x"))
        assertFalse("Ausnahme schlägt Blockregel", blocks("https://good.ads.example/x"))
    }

    @Test fun thirdParty() {
        assertTrue(blocks("https://tracker.example/p", page = "https://site.example/"))
        assertFalse(blocks("https://tracker.example/p", page = "https://www.tracker.example/"))
    }

    @Test fun pathRules() {
        assertTrue(blocks("https://cdn.example/ads/banner.js"))
        assertFalse(blocks("https://cdn.example/ads/allowed.js"))
        assertFalse(blocks("https://cdn.example/app/main.js"))
    }

    @Test fun skippedOptions() {
        assertFalse(blocks("https://typed.example/x.js"))
        assertFalse(blocks("https://restricted.example/x.js"))
    }

    @Test fun navigation() {
        assertTrue(rules.blocksNavigation("popunder.example", gesture = true))
        assertTrue(rules.blocksNavigation("scam.example", gesture = true))
        assertTrue(rules.blocksNavigation("ads.example", gesture = false))
        assertFalse(rules.blocksNavigation("ads.example", gesture = true))
        assertFalse(rules.blocksNavigation("site.example", gesture = false))
        assertFalse("reine Popup-Regel blockt keine Unter-Ressourcen", blocks("https://popunder.example/x.js"))
    }

    @Test fun cosmetic() {
        val news = rules.cssFor("news.example", mainFrame = true)
        assertTrue(news.contains(".sponsor"))
        assertFalse("Ausnahme für news.example", news.contains(".ad-banner"))
        val other = rules.cssFor("other.example", mainFrame = true)
        assertTrue(other.contains(".ad-banner"))
        assertTrue(other.contains(".promo"))
        assertFalse(rules.cssFor("shop.example", mainFrame = true).contains(".promo"))
        assertEquals("allgemeine Regeln nur im Hauptframe", "", rules.cssFor("other.example", mainFrame = false))
        assertFalse(other.contains("has-text") || other.contains("nobab"))
    }

    @Test fun realListsBundled() {
        val dir = File("src/main/assets/adblock")
        val parser = FilterParser()
        val t0 = System.nanoTime()
        listOf("easylist", "easyprivacy", "peterlowe").forEach { parser.add(File(dir, "$it.txt").readText()) }
        val real = parser.build()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Regeln: ${real.ruleCount}, Parsen: $ms ms")
        assertTrue(real.ruleCount > 80_000)
        fun b(url: String, page: String) = real.blocks(url, RuleSet.hostOf(url)!!, RuleSet.hostOf(page))
        val page = "https://news.example.de/artikel"
        assertTrue(b("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page))
        assertTrue(b("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page))
        assertTrue(b("https://www.google-analytics.com/analytics.js", page))
        // Was Seiten zum Laufen brauchen, bleibt durch
        assertFalse(b("https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js", page))
        assertFalse(b("https://fonts.googleapis.com/css2?family=Inter", page))
        assertFalse(b("https://upload.wikimedia.org/wikipedia/commons/video.webm", page))
        assertFalse(b("https://vod.example-cdn.net/hls/master.m3u8", page))
    }
}
