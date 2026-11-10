package de.marne.streamplayer.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.detect.UrlClassifier
import java.io.File
import java.io.IOException

/**
 * Legt eine fertige Datei in die Mediathek: Videos nach Filme/Streamplayer, Ton nach
 * Musik/Streamplayer, alles andere nach Download/Streamplayer. Braucht ab Android 10 keine
 * Speicher-Berechtigung, weil die App nur ihre eigenen Einträge schreibt.
 */
object MediaStoreSaver {

    data class Saved(val uri: Uri, val name: String, val bytes: Long)

    fun mimeFor(item: DownloadItem): String {
        if (item.kind != MediaKind.FILE) return "video/mp4"
        item.mime?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }?.let { return it }
        val ext = UrlClassifier.extension(item.url)
        return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "video/mp4"
    }

    fun save(context: Context, src: File, title: String, mime: String): Saved {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: when {
                mime == "video/x-matroska" -> "mkv"
                mime.startsWith("audio/") -> "m4a"
                else -> "mp4"
            }
        val base = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().take(80)
        val name = base.ifBlank { "Video" } + "." + ext

        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, dir) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to "${Environment.DIRECTORY_MOVIES}/Streamplayer"
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume) to "${Environment.DIRECTORY_MUSIC}/Streamplayer"
            else -> MediaStore.Downloads.getContentUri(volume) to "${Environment.DIRECTORY_DOWNLOADS}/Streamplayer"
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Die Mediathek nimmt die Datei nicht an")
        val bytes = src.length()
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Die Mediathek nimmt die Datei nicht an")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        src.delete()
        // Gibt es den Namen schon, hängt die Mediathek eine Nummer an.
        val finalName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: name
        return Saved(uri, finalName, bytes)
    }
}
