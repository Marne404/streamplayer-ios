package de.marne.streamplayer.adblock

/** Liest Filterlisten im Adblock-Plus-/uBlock-Format (und Hosts-Dateien) in ein [RuleSet]. */
class FilterParser {
    private val block = HashSet<String>()
    private val blockThirdParty = HashSet<String>()
    private val important = HashSet<String>()
    private val allow = HashSet<String>()
    private val navBlock = HashSet<String>()
    private val pageAllow = HashSet<String>()
    private val noGeneric = HashSet<String>()
    private val pathRules = HashMap<String, MutableList<PathRule>>()
    private val cssSpecific = HashMap<String, MutableList<String>>()
    private val cssExceptions = HashMap<String, MutableSet<String>>()
    private val generic = LinkedHashSet<String>()
    private val genericExceptions = HashSet<String>()
    private var count = 0

    fun add(text: String): FilterParser {
        text.lineSequence().forEach { line(it.trim()) }
        return this
    }

    fun build(): RuleSet = RuleSet(
        block = block,
        blockThirdParty = blockThirdParty,
        important = important,
        allow = allow,
        navBlock = navBlock,
        pageAllow = pageAllow,
        noGeneric = noGeneric,
        pathRules = pathRules,
        cssSpecific = cssSpecific,
        cssExceptions = cssExceptions,
        generic = generic.filterNot { it in genericExceptions },
        ruleCount = count,
    )

    private fun line(l: String) {
        if (l.isEmpty() || l[0] == '!' || l[0] == '[') return
        if (l.contains('#') && cosmetic(l)) return
        if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) {
            val host = l.substringAfter(' ').trim().substringBefore(' ').substringBefore('\t').lowercase()
            if (validHost(host) && host != "localhost") { block += host; count++ }
            return
        }
        network(l)
    }

    // ---------- Netzwerkregeln ----------

    private fun network(line: String) {
        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)
        if (!s.startsWith("||")) return // Nur domain-verankerte Regeln: schnell und treffsicher.
        s = s.substring(2)

        val dollar = s.lastIndexOf('$')
        val options = if (dollar >= 0) s.substring(dollar + 1) else ""
        val pattern = if (dollar >= 0) s.substring(0, dollar) else s

        var i = 0
        while (i < pattern.length && (pattern[i].isLetterOrDigit() || pattern[i] == '.' || pattern[i] == '-' || pattern[i] == '_')) i++
        val host = pattern.substring(0, i).lowercase()
        val rest = pattern.substring(i)
        if (!validHost(host) || rest.startsWith("*")) return

        var thirdParty = false
        var popup = false
        var document = false
        var genericHide = false
        var isImportant = false
        var all = false
        if (options.isNotEmpty()) {
            for (raw in options.split(',')) {
                when (raw.trim()) {
                    "third-party", "3p" -> thirdParty = true
                    "popup" -> popup = true
                    "document", "doc" -> document = true
                    "generichide", "ghide", "elemhide", "ehide" -> genericHide = true
                    "important" -> isImportant = true
                    "all" -> all = true
                    "match-case" -> Unit
                    else -> return // Typ-, Domain-, Redirect-Optionen u. ä.: nicht sicher umsetzbar
                }
            }
        }
        val hostOnly = rest.isEmpty() || rest == "^" || rest == "^|" || rest == "|"
        count++

        if (exception) {
            when {
                genericHide -> noGeneric += host
                document || all -> pageAllow += host
                popup -> Unit
                hostOnly && !thirdParty -> allow += host
                !hostOnly -> pathRules.getOrPut(host) { ArrayList(2) } += PathRule(rest, thirdParty, exception = true)
            }
            return
        }
        if (genericHide) return
        if (popup || document || all) navBlock += host
        if (popup && !all && !document) return // Reine Popup-Regel: nur Fenster, keine Unter-Ressourcen
        when {
            hostOnly && isImportant -> important += host
            hostOnly && thirdParty -> blockThirdParty += host
            hostOnly -> block += host
            else -> pathRules.getOrPut(host) { ArrayList(2) } += PathRule(rest, thirdParty, exception = false)
        }
    }

    // ---------- Ausblende-Regeln ----------

    /** true, wenn die Zeile eine kosmetische Regel war (auch eine übersprungene). */
    private fun cosmetic(l: String): Boolean {
        val unsupported = arrayOf("#?#", "#$#", "#@?#", "#@$#", "#%#", "#@%#", "\$\$")
        if (unsupported.any { l.contains(it) }) return true
        val isException = l.contains("#@#")
        val sep = if (isException) "#@#" else if (l.contains("##")) "##" else return false
        val idx = l.indexOf(sep)
        val domains = l.substring(0, idx)
        val selector = l.substring(idx + sep.length).trim()
        if (selector.isEmpty() || selector.startsWith("+js") || selector.startsWith("^") ||
            selector.contains('{') || selector.contains('}') || PROCEDURAL.containsMatchIn(selector)
        ) return true

        val list = if (domains.isEmpty()) emptyList() else domains.split(',').map { it.trim().lowercase() }
        val positive = list.filter { !it.startsWith("~") && !it.endsWith(".*") && validHost(it) }
        val negative = list.filter { it.startsWith("~") }.map { it.drop(1) }.filter(::validHost)
        if (domains.isNotEmpty() && positive.isEmpty() && negative.isEmpty()) return true
        count++

        if (isException) {
            if (positive.isEmpty()) genericExceptions += selector
            else positive.forEach { cssExceptions.getOrPut(it) { HashSet() } += selector }
            return true
        }
        if (positive.isEmpty()) {
            generic += selector
            negative.forEach { cssExceptions.getOrPut(it) { HashSet() } += selector }
        } else {
            positive.forEach { cssSpecific.getOrPut(it) { ArrayList(2) } += selector }
        }
        return true
    }

    private fun validHost(h: String) = h.length in 3..253 && h.contains('.') && !h.startsWith('.') && !h.endsWith('.')

    private companion object {
        val PROCEDURAL = Regex(""":(-abp-|has-text|matches-|xpath|upward|remove|style\(|contains|min-text-length|watch-attr|others|if\(|if-not)""")
    }
}
