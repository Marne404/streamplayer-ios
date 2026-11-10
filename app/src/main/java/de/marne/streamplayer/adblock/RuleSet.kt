package de.marne.streamplayer.adblock

/**
 * Das, was von EasyList & Co. in einer WebView umsetzbar ist:
 *  - Host-Regeln (`||host^`, auch `$third-party`) – der Großteil aller Netzwerkregeln
 *  - Host-plus-Pfad-Regeln (`||host/pfad*`)
 *  - Popup- und Dokument-Regeln (`$popup`, `$document`) für Fenster und Umleitungen
 *  - Ausblende-Regeln (`##selektor`, `domain##selektor`) als CSS
 *
 * Bewusst ausgelassen: Regeln mit Ressourcentyp oder `$domain=` (WebView verrät den Typ
 * nicht zuverlässig), Skript-Injektionen (`##+js`) und prozedurale Selektoren.
 *
 * Unveränderlich nach dem Bauen, deshalb ohne Locks aus jedem Thread lesbar.
 */
class RuleSet internal constructor(
    private val block: Set<String>,
    private val blockThirdParty: Set<String>,
    private val important: Set<String>,
    private val allow: Set<String>,
    private val navBlock: Set<String>,
    private val pageAllow: Set<String>,
    private val noGeneric: Set<String>,
    private val pathRules: Map<String, List<PathRule>>,
    private val cssSpecific: Map<String, List<String>>,
    private val cssExceptions: Map<String, Set<String>>,
    private val generic: List<String>,
    val ruleCount: Int,
) {
    private val genericCss: String by lazy { css(generic) }

    /** Soll eine Unter-Ressource (Skript, Bild, iframe, XHR …) blockiert werden? */
    fun blocks(url: String, host: String, pageHost: String?): Boolean {
        if (host in important || suffixes(host).any { it in important }) return true
        if (pageHost != null && suffixes(pageHost).any { it in pageAllow }) return false
        val sfx = suffixes(host)
        if (sfx.any { it in allow }) return false
        val third = pageHost != null && site(host) != site(pageHost)
        val rules = sfx.mapNotNull { pathRules[it] }.flatten()
        if (rules.isNotEmpty()) {
            val rest = afterHost(url)
            if (rules.any { it.exception && (!it.thirdParty || third) && it.matches(rest) }) return false
            if (sfx.any { it in block } || (third && sfx.any { it in blockThirdParty })) return true
            return rules.any { !it.exception && (!it.thirdParty || third) && it.matches(rest) }
        }
        return sfx.any { it in block } || (third && sfx.any { it in blockThirdParty })
    }

    /** Navigation der Hauptseite: Popup-/Dokument-Ziele immer, Werbe-Hosts ohne Fingertipp. */
    fun blocksNavigation(host: String, gesture: Boolean): Boolean {
        val sfx = suffixes(host)
        if (sfx.any { it in navBlock }) return true
        if (sfx.any { it in allow }) return false
        return !gesture && sfx.any { it in block }
    }

    fun blocksPopup(host: String): Boolean {
        val sfx = suffixes(host)
        return sfx.any { it in navBlock || it in block }
    }

    /** CSS zum Ausblenden für einen Frame. Allgemeine Regeln nur im Hauptframe. */
    fun cssFor(host: String, mainFrame: Boolean): String {
        val sfx = suffixes(host)
        val exceptions = sfx.flatMap { cssExceptions[it].orEmpty() }.toSet()
        val specific = sfx.flatMap { cssSpecific[it].orEmpty() }.filterNot { it in exceptions }
        val sb = StringBuilder()
        if (specific.isNotEmpty()) sb.append(css(specific))
        if (mainFrame && sfx.none { it in noGeneric }) {
            sb.append(if (exceptions.isEmpty()) genericCss else css(generic.filterNot { it in exceptions }))
        }
        return sb.toString()
    }

    private fun css(selectors: List<String>): String {
        // In Blöcken: ein ungültiger Selektor kippt nur seinen Block, nicht alles.
        val sb = StringBuilder()
        selectors.chunked(300).forEach { chunk ->
            chunk.joinTo(sb, ",")
            sb.append("{display:none!important}\n")
        }
        return sb.toString()
    }

    companion object {
        /** a.b.example.com → a.b.example.com, b.example.com, example.com, com */
        fun suffixes(host: String): List<String> {
            val out = ArrayList<String>(4)
            var h = host
            while (true) {
                out += h
                val dot = h.indexOf('.')
                if (dot < 0) break
                h = h.substring(dot + 1)
            }
            return out
        }

        /** Grobe registrierbare Domain: example.com, example.co.uk. Reicht für „Drittanbieter?“. */
        fun site(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val n = if (parts.last().length == 2 && parts[parts.size - 2].length <= 3) 3 else 2
            return parts.takeLast(n).joinToString(".")
        }

        fun hostOf(url: String): String? {
            val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
            var end = start
            while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#' && url[end] != ':') end++
            val hostPart = url.substring(start, end)
            return hostPart.substringAfterLast('@').lowercase().takeIf { it.isNotEmpty() }
        }

        private fun afterHost(url: String): String {
            val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
            var i = start
            while (i < url.length && url[i] != '/' && url[i] != '?' && url[i] != '#' && url[i] != ':') i++
            return url.substring(i)
        }
    }
}

/** `||host/pfad*` – Muster nach dem Host als Regex, erst bei Bedarf kompiliert. */
class PathRule(private val pattern: String, val thirdParty: Boolean, val exception: Boolean) {
    private val regex: Regex? by lazy { compile(pattern) }

    fun matches(afterHost: String): Boolean = regex?.containsMatchIn(afterHost) == true

    private fun compile(p: String): Regex? {
        var body = p
        var endAnchor = false
        if (body.endsWith("|")) {
            endAnchor = true
            body = body.dropLast(1)
        }
        val sb = StringBuilder("^")
        for (c in body) when (c) {
            '*' -> sb.append(".*")
            '^' -> sb.append("(?:[^A-Za-z0-9_.%-]|$)")
            '\\', '.', '+', '?', '(', ')', '[', ']', '{', '}', '|', '$' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        if (endAnchor) sb.append('$')
        return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }
}
