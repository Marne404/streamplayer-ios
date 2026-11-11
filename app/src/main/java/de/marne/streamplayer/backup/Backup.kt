package de.marne.streamplayer.backup

import de.marne.streamplayer.App
import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.SyncCodec
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.library.ProgressMerge
import de.marne.streamplayer.ui.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * Die ganze Einrichtung als eine JSON-Datei – zum Umziehen aufs nächste Handy oder als Sicherung.
 * Welche Teile hinein- und wieder herauskommen, wählt man frei ([Part]). Bilder und Downloads
 * bleiben draußen: Sie sind Dateien dieses Geräts, die Datei soll klein und teilbar bleiben.
 */
object Backup {
    enum class Part(val key: String) {
        SETTINGS("settings"), TMDB("tmdb"), LIBRARY("library"), PROFILES("profiles"),
        LIST("list"), COLLECTIONS("collections"), PROGRESS("progress"), HISTORY("history");

        val label: String get() = when (this) {
            SETTINGS -> tr("Einstellungen", "Settings")
            TMDB -> tr("TMDB-Schlüssel", "TMDB key")
            LIBRARY -> tr("Mediathek", "Library")
            PROFILES -> tr("Profile", "Profiles")
            LIST -> tr("Meine Liste", "My List")
            COLLECTIONS -> tr("Sammlungen", "Collections")
            PROGRESS -> tr("Verlauf und Sehzeit", "Watch history and time")
            HISTORY -> tr("Browser-Verlauf", "Browser history")
        }

        val hint: String get() = when (this) {
            SETTINGS -> tr("Aussehen, Sprache, Downloads, Werbeblocker, Startseite.", "Appearance, language, downloads, ad blocker, home screen.")
            TMDB -> tr("Dein persönlicher Schlüssel – nur mitnehmen, wenn die Datei bei dir bleibt.", "Your personal key – only include it if the file stays with you.")
            LIBRARY -> tr("Alle Filme und Serien, auch die aus dem Creator. Ohne Downloads.", "All movies and shows, including those from Creator. No downloads.")
            PROFILES -> tr("Namen, Farben und PIN-Sperren.", "Names, colors and PIN locks.")
            LIST -> tr("Vorgemerkte Titel je Profil.", "Saved titles per profile.")
            COLLECTIONS -> tr("Eigene Sammlungen je Profil.", "Your collections per profile.")
            PROGRESS -> tr("Wo du stehst, was gesehen ist, wie lange.", "Where you left off, what's watched, for how long.")
            HISTORY -> tr("Zuletzt besuchte Seiten.", "Recently visited pages.")
        }

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key }
            /** Vorauswahl beim Export: alles außer dem Schlüssel. */
            val defaults: Set<Part> get() = entries.toSet() - TMDB
        }
    }

    // Teile, die je Profil gespeichert sind: Ohne das Profil-Gerüst wüsste der Import nicht, wem sie gehören.
    private val perProfile = setOf(Part.PROFILES, Part.LIST, Part.COLLECTIONS, Part.PROGRESS)

    /**
     * Sicherung in [w] schreiben. Die Titel kommen Stück für Stück dazu – als ein JSON-Text am Stück
     * sprengte eine große Bibliothek den Speicher (siehe LibraryStore.writeTitles).
     */
    fun write(parts: Set<Part>, w: java.io.Writer, now: Long = System.currentTimeMillis()) {
        val text = export(parts, now, withTitles = false).toString()
        if (Part.LIBRARY !in parts) { w.write(text); return }
        w.write(text.dropLast(1)) // ohne die schließende Klammer
        w.write(",\"titles\":")
        de.marne.streamplayer.library.LibraryStore.writeTitles(App.instance.library.titles.value, w)
        w.write("}")
    }

    fun export(parts: Set<Part>, now: Long = System.currentTimeMillis(), withTitles: Boolean = true): JSONObject {
        val app = App.instance
        val o = JSONObject()
            .put("app", "streamplayer").put("kind", "backup").put("v", 1).put("created", now)
            .put("parts", JSONArray(parts.map { it.key }))
        if (Part.SETTINGS in parts) o.put("settings", app.settings.exportPrefs())
        if (Part.TMDB in parts) o.put("tmdbKey", app.settings.tmdbKey.value)
        if (Part.HISTORY in parts) o.put("history", app.settings.exportHistory())
        if (Part.LIBRARY in parts) {
            if (withTitles) o.put("titles", JSONArray().apply { app.library.titles.value.forEach { put(it.toJson()) } })
            o.put("libraries", JSONArray().apply { app.library.libraries.value.forEach { put(it.toJson()) } })
        }
        if (parts.any { it in perProfile }) {
            o.put("profiles", JSONArray().apply {
                app.profiles.profiles.value.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("createdAt", p.createdAt).apply {
                        if (Part.PROFILES in parts) put("lock", p.lock)
                        if (Part.LIST in parts) put("list", JSONArray(p.list))
                        if (Part.COLLECTIONS in parts) put("collections", JSONArray().apply { p.collections.forEach { put(it.toJson()) } })
                    })
                }
            })
        }
        if (Part.PROGRESS in parts) {
            o.put("progress", JSONObject().apply { app.progress.byProfile.value.forEach { (pid, m) -> put(pid, ProgressMerge.toJson(m)) } })
            o.put("watch", JSONObject().apply { app.progress.daysByProfile.value.forEach { (pid, d) -> put(pid, ProgressMerge.daysToJson(d)) } })
        }
        return o
    }

    /** Eine gelesene Datei: was drin ist und wie viel. */
    class Contents(val json: JSONObject, val parts: Set<Part>, val created: Long) {
        fun count(p: Part): Int? = when (p) {
            Part.LIBRARY -> json.optJSONArray("titles")?.length()
            Part.PROFILES -> json.optJSONArray("profiles")?.length()
            Part.HISTORY -> json.optJSONArray("history")?.length()
            else -> null
        }
    }

    /** null: keine Sicherung dieser App (oder kaputt). */
    fun read(text: String): Contents? = runCatching {
        val o = JSONObject(text)
        if (o.optString("app") != "streamplayer" || o.optString("kind") != "backup") return null
        val a = o.optJSONArray("parts") ?: JSONArray()
        Contents(o, List(a.length()) { Part.of(a.optString(it)) }.filterNotNull().toSet(), o.optLong("created"))
    }.getOrNull()

    /**
     * Einspielen. Reihenfolge zählt: erst die Mediathek (Titel-IDs können sich beim Zusammenführen
     * ändern), dann die Profile (Listen zeigen auf Titel), dann der Verlauf (gehört Profil und Titel).
     */
    fun apply(c: Contents, parts: Set<Part>, replace: Boolean) {
        val app = App.instance
        val o = c.json
        val use = parts intersect c.parts
        if (Part.SETTINGS in use) o.optJSONObject("settings")?.let { app.settings.importPrefs(it, replace) }
        if (Part.TMDB in use) app.settings.importTmdbKey(o.optString("tmdbKey"), replace)
        if (Part.HISTORY in use) o.optJSONArray("history")?.let { app.settings.importHistory(it, replace) }

        val titleIds = if (Part.LIBRARY in use) {
            val a = o.optJSONArray("titles") ?: JSONArray()
            // Erst die Bibliotheken, dann die Titel – sonst zeigten Titel auf Bibliotheken, die es (noch) nicht gibt.
            o.optJSONArray("libraries")?.let { l -> app.library.importLibraries(List(l.length()) { de.marne.streamplayer.library.Library.fromJson(l.getJSONObject(it)) }, replace) }
            app.library.importBackup(List(a.length()) { LibTitle.fromJson(a.getJSONObject(it)) }, replace)
        } else emptyMap()

        var pids = emptyMap<String, String>()
        if (use.any { it in perProfile }) {
            val a = o.optJSONArray("profiles") ?: JSONArray()
            val incoming = List(a.length()) { Profile.fromJson(a.getJSONObject(it)) }
            pids = app.profiles.importBackup(
                incoming, replace,
                identity = Part.PROFILES in use, list = Part.LIST in use, collections = Part.COLLECTIONS in use,
                titleIds = titleIds,
            )
        }

        if (Part.PROGRESS in use) {
            val po = o.optJSONObject("progress") ?: JSONObject()
            val wo = o.optJSONObject("watch") ?: JSONObject()
            val progress = po.keys().asSequence().associate { pid ->
                (pids[pid] ?: pid) to rekey(ProgressMerge.fromJson(po.getJSONObject(pid)), titleIds)
            }
            val days = wo.keys().asSequence().associate { pid -> (pids[pid] ?: pid) to ProgressMerge.daysFromJson(wo.getJSONObject(pid)) }
            app.progress.importBackup(progress, days, replace)
        }
    }

    /** Fortschritt auf die hiesigen Titel-IDs umschreiben (beim Zusammenführen wiedererkannte Titel). */
    fun rekey(map: Map<String, Progress>, titleIds: Map<String, String>): Map<String, Progress> {
        if (titleIds.isEmpty()) return map
        return map.mapKeys { (k, _) ->
            val id = Keys.parse(k)?.first
            val to = id?.let(titleIds::get)
            if (to != null) SyncCodec.rekey(k, id, to) ?: k else k
        }
    }

    fun fileName(now: java.time.LocalDate = java.time.LocalDate.now()) = "streamplayer-$now.json"
}
