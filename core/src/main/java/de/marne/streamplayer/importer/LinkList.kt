package de.marne.streamplayer.importer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Ein Link für den Massenimport, optional mit eigenem Namen. */
data class LinkItem(val url: String, val name: String? = null)

/**
 * Links aus beliebigem Text oder JSON lesen – eingefügt, geteilt oder als Datei. Jeder Link wird
 * ein eigener Titel. Erlaubt ist alles, was Links enthält:
 *
 *   https://…                                  (Text, ein Link pro Zeile oder wild gemischt)
 *   ["https://…", "https://…"]
 *   { "links": ["https://…", { "url": "https://…", "title": "Eigener Name" }] }
 *
 * In JSON zählt jedes Objekt mit „url“ (oder „link“, „href“) als ein Eintrag, „title“ oder „name“
 * daneben als Name. Doppelte Links (auch mit/ohne „www.“ oder Schrägstrich am Ende) fallen weg.
 */
object LinkList {
    const val MAX = 200

    fun parse(text: String): List<LinkItem> {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val json = if (trimmed.startsWith("[") || trimmed.startsWith("{")) runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() else null
        val items = if (json is JSONObject || json is JSONArray) fromJson(json) else fromText(text)
        return items.distinctBy { key(it.url) }
    }

    /** Ein Katalog im Streamplayer-Format ist keine Linkliste – der gehört über „Datei öffnen“ hinein. */
    fun isCatalog(text: String): Boolean {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!trimmed.startsWith("{")) return false
        val o = runCatching { JSONObject(trimmed) }.getOrNull() ?: return false
        return o.optString("format") == "streamplayer-catalog" || (o.has("titles") && !o.has("links"))
    }

    private val URL = Regex("""https?://[^\s"'<>\]\[{}|\\^`]+""")
    private val URL_KEYS = listOf("url", "link", "href", "page", "adresse")
    private val NAME_KEYS = listOf("title", "name", "titel")

    private fun fromText(text: String): List<LinkItem> =
        URL.findAll(text).map { LinkItem(clean(it.value)) }.filter { valid(it.url) }.toList()

    private fun fromJson(value: Any?): List<LinkItem> {
        val out = mutableListOf<LinkItem>()
        fun walk(v: Any?, depth: Int) {
            if (depth > 8) return
            when (v) {
                is String -> fromText(v).forEach { out += it }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    val url = URL_KEYS.firstNotNullOfOrNull { k -> v.optString(k).trim().takeIf { valid(it) } }
                    if (url != null) {
                        val name = NAME_KEYS.firstNotNullOfOrNull { k -> v.optString(k).trim().takeIf { it.isNotEmpty() } }
                        out += LinkItem(clean(url), name)
                    } else {
                        v.keys().forEach { k -> walk(v.opt(k), depth + 1) }
                    }
                }
            }
        }
        walk(value, 0)
        return out
    }

    private fun clean(u: String) = u.trim().trimEnd('.', ',', ';', ')', '!', '?')

    private fun valid(u: String) = (u.startsWith("http://") || u.startsWith("https://")) && '.' in u.substringAfter("://") && u.length > 10

    private fun key(u: String) = u.substringAfter("://").removePrefix("www.").substringBefore('#').trimEnd('/').lowercase()
}
