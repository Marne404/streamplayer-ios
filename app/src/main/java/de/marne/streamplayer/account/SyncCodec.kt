package de.marne.streamplayer.account

import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import org.json.JSONObject
import java.security.MessageDigest

/** Reine Umwandlungen für den Abgleich – ohne Firebase, damit sie testbar sind. */
object SyncCodec {
    /** Platzhalter für ein Bild, das als eigenes Dokument (covers/{name}) mitreist. */
    const val COVER = "cover:"
    private val IMAGE_FIELDS = listOf("poster", "backdrop", "customPoster")

    /**
     * Ein Titel, wie er ins Konto geht: ohne Downloads (die gehören dem Gerät) und mit
     * Platzhaltern statt Gerätepfaden für eigene Bilder. content://-Bilder (Standbild eines
     * Downloads) lassen sich nicht übertragen und fallen weg.
     */
    fun titleJson(t: LibTitle): String {
        val o = t.copy(offline = emptyMap()).toJson()
        o.remove("offline")
        for (f in IMAGE_FIELDS) {
            val v = if (o.isNull(f)) null else o.optString(f)
            when {
                v == null -> Unit
                v.startsWith("file:") -> o.put(f, COVER + v.substringAfterLast('/'))
                v.startsWith("content:") -> o.remove(f)
            }
        }
        return o.toString()
    }

    /**
     * Ein Titel, wie ihn Freunde sehen (geteilte Bibliothek): ohne Downloads, ohne Bibliothek und
     * ohne eigene Bilder vom Gerät – die liegen nur im eigenen Konto. Bild-Adressen aus dem Netz bleiben.
     */
    fun sharedTitleJson(t: LibTitle): String {
        val o = t.copy(offline = emptyMap(), libraryId = null).toJson()
        o.remove("offline")
        for (f in IMAGE_FIELDS) {
            val v = if (o.isNull(f)) null else o.optString(f)
            if (v != null && !v.startsWith("http")) o.remove(f)
        }
        return o.toString()
    }

    /** Alle Bild-Platzhalter in einem Titel. */
    fun coverNames(json: String): List<String> {
        val o = JSONObject(json)
        return IMAGE_FIELDS.mapNotNull { f -> if (o.isNull(f)) null else o.optString(f).takeIf { it.startsWith(COVER) }?.removePrefix(COVER) }
    }

    /** Platzhalter durch lokale Adressen ersetzen; [resolve] gibt null, wenn das Bild (noch) fehlt. */
    fun resolveCovers(json: String, resolve: (String) -> String?): JSONObject {
        val o = JSONObject(json)
        for (f in IMAGE_FIELDS) {
            val v = if (o.isNull(f)) null else o.optString(f)
            // Fehlt das Bild, bleibt der Platzhalter: Er zeigt nichts an, geht aber beim nächsten
            // Abgleich unverändert zurück und überschreibt das Bild im Konto nicht mit „kein Bild“.
            if (v != null && v.startsWith(COVER)) resolve(v.removePrefix(COVER))?.let { o.put(f, it) }
        }
        return o
    }

    /** Fortschritts-Schlüssel auf eine andere Titel-ID umschreiben (zusammengeführte Doppel). */
    fun rekey(key: String, from: String, to: String): String? {
        val p = Keys.parse(key) ?: return null
        if (p.first != from) return null
        return if (p.second == null) "m:$to" else "e:$to:${p.second}:${p.third}"
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
