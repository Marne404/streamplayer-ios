package de.marne.streamplayer.update

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Verbindung der Store-Variante zu Atelier (Marnes App-Store). Die App lädt selbst nichts: sie fragt
 * Atelier über dessen ContentProvider, ob es eine neuere Version gibt, und schickt zum Aktualisieren
 * dorthin. Ohne Atelier passiert nichts – dann gibt es auch kein Update anzuzeigen.
 *
 * Vorlage für alle Apps im Atelier (gleiche Datei in Counter und Sticker-Maker).
 */
object AtelierLink {
    private const val AUTHORITY = "de.marne.atelier.updates"
    private const val WEB = "https://marne-appstore.marne-gamingx.workers.dev"

    class Latest(val code: Long, val name: String)

    /** Neueste Version laut Atelier, oder null (Atelier fehlt, App nicht im Verzeichnis). Nicht im UI-Thread. */
    fun latest(context: Context): Latest? = runCatching {
        val uri = Uri.parse("content://$AUTHORITY/app/${context.packageName}")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) Latest(c.getLong(c.getColumnIndexOrThrow("version_code")), c.getString(c.getColumnIndexOrThrow("version_name"))) else null
        }
    }.getOrNull()

    /** Öffnet den Eintrag dieser App in Atelier; ohne Atelier die Website des Stores. */
    fun open(context: Context) {
        val pkg = context.packageName
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("atelier://app/$pkg")).setPackage("de.marne.atelier").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(app) }.isFailure) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$WEB/#$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
