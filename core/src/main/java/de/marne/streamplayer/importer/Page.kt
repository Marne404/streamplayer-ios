package de.marne.streamplayer.importer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/**
 * Ein Verweis auf der Seite, schon absolut und ohne #Anker. [text]: sichtbarer Text, sonst
 * title-Attribut oder Bild-Alt. [image]: ein Bild im Verweis – Kacheln einer Übersicht.
 */
data class Link(val url: String, val text: String, val image: Boolean = false)

/**
 * Was der Creator von einer geladenen Seite braucht: Titel, Meta-Angaben, JSON-LD und alle
 * Verweise. Keine Regeln für einzelne Seiten, nur Standards (og:, schema.org) und Links.
 */
class Page(
    val url: String,
    val title: String?,
    val h1: String?,
    /** og:*, twitter:*, description … – Schlüssel klein geschrieben, erster Wert gewinnt. */
    val meta: Map<String, String>,
    /** Alle JSON-LD-Objekte, @graph und Arrays schon aufgelöst. */
    val ld: List<JSONObject>,
    val links: List<Link>,
    /** Hinweise auf eine Vorschaltseite (Captcha, „Moment bitte“), ohne die echte Seite. */
    val challenge: Boolean,
    /** Nächste Seite einer Liste laut rel="next", sonst null. */
    val next: String? = null,
) {
    companion object {
        fun parse(html: String, url: String): Page {
            // Jsoup übernimmt <base href> selbst als Basis für absUrl.
            val doc = Jsoup.parse(html, url)
            val meta = LinkedHashMap<String, String>()
            doc.select("meta[content]").forEach { m ->
                val key = m.attr("property").ifBlank { m.attr("name") }.lowercase().trim()
                val v = m.attr("content").trim()
                if (key.isNotEmpty() && v.isNotEmpty() && key !in meta) meta[key] = v
            }
            val ld = doc.select("script[type*=ld+json]").flatMap { flattenLd(it.data()) }

            val links = LinkedHashMap<String, Link>()
            fun add(abs: String, text: String, image: Boolean = false) {
                val clean = abs.substringBefore('#').trim()
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
                val t = text.trim().take(200)
                val old = links[clean]
                // Kachel (nur Bild) und Titel-Link zeigen oft auf dieselbe Adresse: Text und Bild zusammenführen.
                links[clean] = if (old == null) Link(clean, t, image) else old.copy(text = old.text.ifEmpty { t }, image = old.image || image)
            }
            doc.select("a[href]").forEach { a ->
                val img = a.selectFirst("img")
                val text = a.text().ifBlank { a.attr("title") }.ifBlank { img?.attr("alt").orEmpty() }
                add(a.absUrl("href"), text, image = img != null || a.selectFirst("picture") != null)
            }
            // Staffel-Auswahl als Klappliste oder Knöpfe mit Ziel im data-Attribut: häufig bei Serienseiten.
            doc.select("[data-href]").forEach { add(it.absUrl("data-href"), it.text()) }
            doc.select("[data-url]").forEach { add(it.absUrl("data-url"), it.text()) }
            doc.select("option[value]").forEach { o ->
                val v = o.attr("value")
                if (v.startsWith("/") || v.startsWith("http")) add(o.absUrl("value"), o.text())
            }
            // Folgen, die die Seite selbst in JSON-LD auflistet, sind die verlässlichsten Verweise.
            ld.forEach { collectLdUrls(it, url) { u -> add(u, "") } }

            val next = doc.select("link[rel][href], a[rel][href]")
                .firstOrNull { e -> e.attr("rel").lowercase().split(' ').contains("next") }
                ?.absUrl("href")?.substringBefore('#')?.takeIf { it.startsWith("http") }

            val title = doc.title().trim().ifEmpty { null }
            val h1 = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val lower = html.take(200_000).lowercase()
            val challenge = CHALLENGE_MARKERS.any { it in lower } ||
                (title?.lowercase()?.let { t -> CHALLENGE_TITLES.any { it in t } } ?: false)
            return Page(url, title, h1, meta, ld, links.values.toList(), challenge, next)
        }

        private val CHALLENGE_MARKERS = listOf(
            "cf-chl", "challenge-platform", "cf_chl_opt", "g-recaptcha", "h-captcha", "hcaptcha.com/1/api",
            "turnstile/v0", "ddos-guard", "please enable javascript and cookies",
        )
        private val CHALLENGE_TITLES = listOf("just a moment", "einen moment", "attention required", "access denied", "ddos-guard")

        /** JSON-LD tolerant lesen: Seiten liefern oft Arrays, @graph oder kaputtes JSON. */
        fun flattenLd(raw: String): List<JSONObject> {
            val value = runCatching { JSONTokener(raw.trim()).nextValue() }.getOrNull() ?: return emptyList()
            val out = mutableListOf<JSONObject>()
            fun walk(v: Any?) {
                when (v) {
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                    is JSONObject -> {
                        out += v
                        v.optJSONArray("@graph")?.let { walk(it) }
                    }
                }
            }
            walk(value)
            return out
        }

        private fun collectLdUrls(o: JSONObject, base: String, sink: (String) -> Unit) {
            fun visit(v: Any?, depth: Int) {
                if (depth > 6) return
                when (v) {
                    is JSONArray -> for (i in 0 until v.length()) visit(v.opt(i), depth + 1)
                    is JSONObject -> {
                        val type = Ld.types(v)
                        if (type.any { it in EPISODE_TYPES }) {
                            Ld.str(v, "url")?.let { u -> runCatching { java.net.URI(base).resolve(u).toString() }.getOrNull()?.let(sink) }
                        }
                        listOf("containsSeason", "episode", "episodes", "hasPart").forEach { k -> v.opt(k)?.let { visit(it, depth + 1) } }
                    }
                }
            }
            visit(o, 0)
        }

        private val EPISODE_TYPES = setOf("tvepisode", "episode", "tvseason", "creativeworkseason")
    }
}

/** Kleine Helfer für schema.org in JSON-LD. */
object Ld {
    fun types(o: JSONObject): List<String> = when (val t = o.opt("@type")) {
        is String -> listOf(t.lowercase())
        is JSONArray -> List(t.length()) { t.optString(it).lowercase() }
        else -> emptyList()
    }

    fun str(o: JSONObject?, key: String): String? {
        if (o == null || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is String -> v.trim().takeIf { it.isNotEmpty() }
            is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { i -> (v.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            else -> null
        }
    }

    /** Bild als Adresse, als ImageObject oder als Liste von beidem. */
    fun image(o: JSONObject?): String? {
        if (o == null) return null
        fun of(v: Any?): String? = when (v) {
            is String -> v.trim().takeIf { it.isNotEmpty() }
            is JSONObject -> str(v, "url") ?: str(v, "contentUrl")
            is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { of(v.opt(it)) }
            else -> null
        }
        return of(o.opt("image")) ?: of(o.opt("thumbnailUrl"))
    }

    fun year(o: JSONObject?): Int? {
        if (o == null) return null
        return listOf("startDate", "datePublished", "dateCreated", "releaseDate", "uploadDate").firstNotNullOfOrNull { k ->
            str(o, k)?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }
        }
    }

    fun obj(o: JSONObject?, key: String): JSONObject? = when (val v = o?.opt(key)) {
        is JSONObject -> v
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { v.optJSONObject(it) }
        else -> null
    }
}
