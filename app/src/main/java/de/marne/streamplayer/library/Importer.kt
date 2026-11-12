package de.marne.streamplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import de.marne.streamplayer.catalog.CatalogJson
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Liest Katalog-Dateien (aus dem Creator oder von Hand geschrieben) in die Mediathek.
 * Eingebettete Bilder (data:-URIs) werden als Dateien abgelegt; danach ergänzt TMDB.
 */
class Importer(private val context: Context, private val store: LibraryStore, private val enricher: Enricher) {

    data class Result(val added: Int, val updated: Int, val name: String?, val problems: List<String> = emptyList(), val adopted: Int = 0) {
        val summary: String get() = buildList {
            if (added > 0) add(tr("$added neu", "$added new"))
            // Standen schon in einer anderen Bibliothek: samt Cover und TMDB-Angaben übernommen.
            if (adopted > 0) add(tr("$adopted aus anderen Bibliotheken übernommen", "$adopted taken from other libraries"))
            if (updated > 0) add(tr("$updated aktualisiert", "$updated updated"))
            if (problems.isNotEmpty()) add(tr("${problems.size} übersprungen (${problems.first()})", "${problems.size} skipped (${problems.first()})"))
        }.joinToString(", ").ifEmpty { tr("Nichts Neues", "Nothing new") }
    }

    /** [libraryId]: Bibliothek für neue Titel (null: die gerade gezeigte). */
    suspend fun import(uri: Uri, libraryId: String? = null): Result = withContext(Dispatchers.IO) {
        // Titel für Titel lesen: Ein Katalog mit Tausenden Serien passt nicht als ein Text in den Speicher.
        val parsed = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { CatalogJson.read(it) }
            ?: throw IllegalStateException(tr("Datei lässt sich nicht lesen.", "Can’t read the file."))
        importCatalog(parsed, libraryId)
    }

    suspend fun importText(text: String, libraryId: String? = null): Result = importCatalog(CatalogJson.parseReport(text), libraryId)

    private suspend fun importCatalog(parsed: Pair<de.marne.streamplayer.catalog.Catalog, List<String>>, libraryId: String?): Result = withContext(Dispatchers.IO) {
        val (catalog, problems) = parsed
        if (catalog.titles.isEmpty() && problems.isNotEmpty()) throw IllegalArgumentException(problems.first())
        var added = 0
        var updated = 0
        var adopted = 0
        val ids = mutableListOf<String>()
        for (c in catalog.titles) {
            val poster = c.poster?.takeIf { it.startsWith("data:") }?.let { saveDataUri(it, "${c.id}-poster") }
            val backdrop = c.backdrop?.takeIf { it.startsWith("data:") }?.let { saveDataUri(it, "${c.id}-backdrop") }
            val (t, how) = store.upsert(c, poster, backdrop, libraryId)
            when (how) {
                LibraryStore.Upserted.NEW -> added++
                LibraryStore.Upserted.UPDATED -> updated++
                LibraryStore.Upserted.ADOPTED -> adopted++
            }
            // Was TMDB-Angaben schon hat, muss nicht erneut gesucht werden.
            if (t.tmdb == null) ids += t.id
        }
        enricher.enqueue(ids)
        Result(added, updated, catalog.name, problems, adopted)
    }

    /** data:image/…;base64,… → Datei in filesDir/posters, Rückgabe als file://-Adresse. */
    private fun saveDataUri(data: String, name: String): String? = runCatching {
        val b64 = data.substringAfter("base64,", "")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val ext = if (data.startsWith("data:image/png")) "png" else "jpg"
        val dir = File(context.filesDir, "posters").apply { mkdirs() }
        // Zeitstempel im Namen: ein neues Bild darf nicht aus dem Bild-Cache kommen.
        val f = File(dir, "$name-${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        Uri.fromFile(f).toString()
    }.getOrNull()

    /** Eigenes Cover aus der Galerie: verkleinert nach filesDir/posters kopieren. */
    suspend fun saveCustomPoster(source: Uri, titleId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Erst nur die Maße lesen, dann grob vorverkleinert dekodieren: ein 50-MP-Foto in voller
            // Größe braucht 200 MB Speicher. Die Zweierpotenz bleibt über dem Ziel, fein skaliert wird danach.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
            val scale = minOf(1f, 1800f / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val dir = File(context.filesDir, "posters").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("$titleId-custom") }?.forEach { it.delete() }
            val f = File(dir, "$titleId-custom-${System.currentTimeMillis()}.jpg")
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Uri.fromFile(f).toString()
        }.getOrNull()
    }
}
