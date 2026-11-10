package de.marne.streamplayer.importer

/**
 * robots.txt einer Seite, gelesen wie es Suchmaschinen tun: Gruppe für unsere Kennung, sonst
 * „*“. Die längste passende Regel gewinnt, bei Gleichstand „Allow“. `*` steht für beliebig
 * viele Zeichen, `$` für das Ende. Gilt nur für den Übersichts-Import, der viele Seiten liest –
 * ein einzelner Link ist wie ein Aufruf im Browser.
 */
class Robots private constructor(private val rules: List<Rule>, val crawlDelayMs: Long?) {
    private class Rule(val allow: Boolean, val pattern: String) {
        private val regex: Regex = Regex(
            buildString {
                append('^')
                pattern.forEachIndexed { i, c ->
                    when {
                        c == '*' -> append(".*")
                        c == '$' && i == pattern.lastIndex -> append('$')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            },
        )
        val length = pattern.length
        fun matches(path: String) = regex.containsMatchIn(path)
    }

    /** Darf die Adresse gelesen werden? Gemeint ist nur Pfad und Query. */
    fun allowed(url: String): Boolean {
        val path = pathOf(url)
        val hit = rules.filter { it.matches(path) }.maxWithOrNull(compareBy<Rule> { it.length }.thenBy { it.allow }) ?: return true
        return hit.allow
    }

    companion object {
        const val AGENT = "streamplayer"
        val ALL = Robots(emptyList(), null)

        fun parse(text: String, agent: String = AGENT): Robots {
            data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Rule> = mutableListOf(), var delay: Long? = null)
            val groups = mutableListOf<Group>()
            var current: Group? = null
            var lastWasAgent = false
            text.lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@forEach
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "user-agent" -> {
                        // Mehrere User-agent-Zeilen hintereinander teilen sich eine Gruppe.
                        if (!lastWasAgent || current == null) current = Group().also { groups += it }
                        current!!.agents += value.lowercase()
                        lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        lastWasAgent = false
                        val g = current ?: return@forEach
                        // Leeres „Disallow:“ verbietet nichts.
                        if (value.isNotEmpty()) g.rules += Rule(key == "allow", value)
                    }
                    "crawl-delay" -> {
                        lastWasAgent = false
                        val g = current ?: return@forEach
                        value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { g.delay = (it * 1000).toLong() }
                    }
                    else -> lastWasAgent = false
                }
            }
            val a = agent.lowercase()
            val mine = groups.filter { g -> g.agents.any { it != "*" && (a.startsWith(it) || it.startsWith(a)) } }
            val chosen = mine.ifEmpty { groups.filter { "*" in it.agents } }
            if (chosen.isEmpty()) return ALL
            return Robots(chosen.flatMap { it.rules }, chosen.mapNotNull { it.delay }.maxOrNull())
        }

        private fun pathOf(url: String): String {
            val afterHost = url.substringAfter("://", "").let { r -> r.indexOf('/').let { if (it < 0) "" else r.substring(it) } }
            return afterHost.substringBefore('#').ifEmpty { "/" }
        }
    }
}
