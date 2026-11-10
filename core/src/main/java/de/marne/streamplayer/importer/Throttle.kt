package de.marne.streamplayer.importer

import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Gemeinsame Bremse für den Übersichts-Import, der viele Seiten einer Seite liest:
 * - Mindestabstand zwischen zwei Abrufen, gemessen vom Ende des letzten – auch über Titel hinweg
 * - Budget an Abrufen pro Durchgang; danach wirft jeder weitere Abruf, bis [refill]
 * - robots.txt je Host (Disallow/Allow, Crawl-delay hebt die Pause an)
 * - Antwortet die Seite mit 429, wird die Pause verdoppelt, einmal gewartet und neu versucht;
 *   kommt wieder 429, ist für diesen Durchgang Schluss ([stop])
 *
 * Alle Abrufe eines Durchgangs – schlicht, im Browser, robots.txt – laufen über dieselbe Instanz.
 */
class Throttle(
    /** Für robots.txt: schlichter Abruf ohne Browser. */
    private val http: Fetcher,
    budget: Int,
    pauseMs: Long,
    private val backoffMs: Long = 20_000,
    private val respectRobots: Boolean = true,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    enum class Stop { BUDGET, SLOW_DOWN }

    var budget: Int = budget
        private set
    var used: Int = 0
        private set
    var pauseMs: Long = pauseMs
        private set
    var stop: Stop? = null
        private set
    val remaining: Int get() = (budget - used).coerceAtLeast(0)

    private val robots = mutableMapOf<String, Robots>()
    private var last: Long? = null

    /** Nächster Durchgang: Budget von vorn, Stopp gelöst. Pause und robots.txt bleiben. */
    fun refill(budget: Int) {
        this.budget = budget
        used = 0
        stop = null
    }

    fun wrap(f: Fetcher): Fetcher = Fetcher { url -> fetch(f, url) }

    /** Erlaubt robots.txt die Adresse? Lädt robots.txt beim ersten Mal (zählt als Abruf). */
    suspend fun allowed(url: String): Boolean = !respectRobots || robotsFor(url).allowed(url)

    private suspend fun fetch(f: Fetcher, url: String): Fetched {
        stop?.let { throw FetchException(message(it)) }
        if (!allowed(url)) throw FetchException(DISALLOWED)
        var r = raw(f, url)
        if (r.status == 429) {
            pauseMs = (pauseMs * 2).coerceIn(2_000, MAX_PAUSE)
            sleep(backoffMs)
            r = raw(f, url)
            if (r.status == 429) stop = Stop.SLOW_DOWN
        }
        return r
    }

    private suspend fun raw(f: Fetcher, url: String): Fetched {
        if (used >= budget) {
            stop = Stop.BUDGET
            throw FetchException(message(Stop.BUDGET))
        }
        last?.let { l -> (l + pauseMs - now()).takeIf { it > 0 }?.let { sleep(it) } }
        used++
        try {
            return f.fetch(url)
        } finally {
            last = now()
        }
    }

    private suspend fun robotsFor(url: String): Robots {
        val origin = originOf(url) ?: return Robots.ALL
        robots[origin]?.let { return it }
        // Ohne Budget gar nicht erst fragen – der eigentliche Abruf scheitert dann ohnehin.
        if (used >= budget) return Robots.ALL
        val r = try {
            val res = raw(http, "$origin/robots.txt")
            // 4xx: keine Regeln. Eine HTML-Seite statt robots.txt (weiche 404) ergibt ebenfalls keine.
            if (res.status in 200..299) Robots.parse(res.html) else Robots.ALL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Robots.ALL
        }
        r.crawlDelayMs?.let { d -> pauseMs = maxOf(pauseMs, d.coerceAtMost(MAX_PAUSE)) }
        robots[origin] = r
        return r
    }

    companion object {
        const val MAX_PAUSE = 30_000L
        val DISALLOWED: String get() = tr("Laut robots.txt der Seite nicht erlaubt.", "Not allowed by the site’s robots.txt.")

        fun message(stop: Stop) = when (stop) {
            Stop.BUDGET -> tr("Abrufgrenze dieses Durchgangs erreicht.", "Fetch limit for this run reached.")
            Stop.SLOW_DOWN -> tr("Die Seite bremst (429, zu viele Anfragen).", "The site is throttling requests (429, too many requests).")
        }

        private fun originOf(url: String): String? {
            val scheme = url.substringBefore("://", "").lowercase().takeIf { it == "http" || it == "https" } ?: return null
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
            return if (host.isEmpty()) null else "$scheme://$host"
        }
    }
}
