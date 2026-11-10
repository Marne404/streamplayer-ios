package de.marne.streamplayer.download

import de.marne.streamplayer.detect.Key
import de.marne.streamplayer.detect.MediaKind
import org.json.JSONArray
import org.json.JSONObject

enum class DlState { QUEUED, RUNNING, MERGING, WAITING_WIFI, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val thumbnail: String?,
    val pageUrl: String,
    val referer: String?,
    val userAgent: String,
    val quality: String?,
    val keys: List<Key>,
    val mime: String?,
    val durationMs: Long?,
    val state: DlState = DlState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    /** 0..1 über alle Phasen (Laden und Zusammenfügen). */
    val progress: Float = 0f,
    val speedBps: Long = 0,
    val error: String? = null,
    /** content://-Adresse in der Mediathek, sobald fertig. */
    val fileUri: String? = null,
    val fileName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** Fortschritts-Schlüssel des Films/der Folge in der Bibliothek; die fertige Datei hängt sich dort an. */
    val libraryKey: String? = null,
) {
    val active: Boolean get() = state == DlState.QUEUED || state == DlState.RUNNING || state == DlState.MERGING || state == DlState.WAITING_WIFI

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("kind", kind.name); put("title", title)
        put("thumbnail", thumbnail); put("pageUrl", pageUrl); put("referer", referer); put("ua", userAgent)
        put("quality", quality); put("mime", mime); put("durationMs", durationMs)
        put("keys", JSONArray().apply { keys.forEach { put(JSONArray(listOf(it.period, it.group, it.stream))) } })
        put("state", state.name); put("bytesDone", bytesDone); put("bytesTotal", bytesTotal); put("progress", progress.toDouble())
        put("error", error); put("fileUri", fileUri); put("fileName", fileName)
        put("createdAt", createdAt); put("finishedAt", finishedAt); put("libraryKey", libraryKey)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val keysArr = o.optJSONArray("keys") ?: JSONArray()
            return DownloadItem(
                id = o.getString("id"),
                url = o.getString("url"),
                kind = MediaKind.valueOf(o.getString("kind")),
                title = o.optString("title"),
                thumbnail = o.optStringOrNull("thumbnail"),
                pageUrl = o.optString("pageUrl"),
                referer = o.optStringOrNull("referer"),
                userAgent = o.optString("ua"),
                quality = o.optStringOrNull("quality"),
                keys = List(keysArr.length()) { i -> keysArr.getJSONArray(i).let { Key(it.getInt(0), it.getInt(1), it.getInt(2)) } },
                mime = o.optStringOrNull("mime"),
                durationMs = o.optLongOrNull("durationMs"),
                state = DlState.valueOf(o.getString("state")),
                bytesDone = o.optLong("bytesDone"),
                bytesTotal = o.optLongOrNull("bytesTotal"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                error = o.optStringOrNull("error"),
                fileUri = o.optStringOrNull("fileUri"),
                fileName = o.optStringOrNull("fileName"),
                createdAt = o.optLong("createdAt"),
                finishedAt = o.optLongOrNull("finishedAt"),
                libraryKey = o.optStringOrNull("libraryKey"),
            )
        }

        private fun JSONObject.optStringOrNull(k: String): String? =
            if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        private fun JSONObject.optLongOrNull(k: String): Long? =
            if (isNull(k) || !has(k)) null else optLong(k)
    }
}
