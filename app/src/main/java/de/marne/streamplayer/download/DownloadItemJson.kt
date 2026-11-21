package de.marne.streamplayer.download

import de.marne.streamplayer.detect.Key
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.subtitles.SubTrack
import de.marne.streamplayer.subtitles.listFromJson
import de.marne.streamplayer.subtitles.listToJson
import org.json.JSONArray
import org.json.JSONObject

// DownloadItem liegt im gemeinsamen Modul :ui; Speichern und Lesen (org.json) bleibt auf Android.

fun DownloadItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("url", url); put("kind", kind.name); put("title", title)
    put("thumbnail", thumbnail); put("pageUrl", pageUrl); put("referer", referer); put("ua", userAgent)
    put("quality", quality); put("mime", mime); put("durationMs", durationMs)
    put("keys", JSONArray().apply { keys.forEach { put(JSONArray(listOf(it.period, it.group, it.stream))) } })
    put("state", state.name); put("bytesDone", bytesDone); put("bytesTotal", bytesTotal); put("progress", progress.toDouble())
    put("error", error); put("fileUri", fileUri); put("fileName", fileName)
    put("createdAt", createdAt); put("finishedAt", finishedAt); put("libraryKey", libraryKey)
    if (subtitles.isNotEmpty()) put("subs", SubTrack.listToJson(subtitles))
    }

fun DownloadItem.Companion.fromJson(o: JSONObject): DownloadItem {
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
    subtitles = SubTrack.listFromJson(o.optJSONArray("subs")),
    )
}

private fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(k: String): Long? =
    if (isNull(k) || !has(k)) null else optLong(k)
