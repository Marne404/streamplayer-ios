package de.marne.streamplayer.download

import android.content.Context
import android.net.Uri
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.Variant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Die Download-Liste. Liegt als JSON in filesDir, geschrieben mit kurzer Verzögerung,
 * damit Fortschritts-Updates nicht jede halbe Sekunde auf den Speicher gehen.
 */
@OptIn(FlowPreview::class)
class DownloadStore(private val context: Context) {

    private val file = File(context.filesDir, "downloads.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    init {
        scope.launch {
            _items.drop(1).debounce(800).collect { save(it) }
        }
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun update(id: String, change: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** Legt einen Download an und gibt seine ID zurück. */
    fun enqueue(c: Candidate, variant: Variant?, title: String, thumbnail: String?, userAgent: String): String {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = c.url,
            kind = c.kind,
            title = title,
            thumbnail = thumbnail,
            pageUrl = c.pageUrl,
            referer = c.referer ?: c.pageUrl,
            userAgent = userAgent,
            quality = variant?.label?.takeIf { it != "Original" },
            keys = variant?.keys.orEmpty(),
            mime = c.mime,
            durationMs = c.durationMs,
            bytesTotal = variant?.estimatedBytes ?: c.sizeBytes,
        )
        _items.update { listOf(item) + it }
        return item.id
    }

    fun remove(id: String) {
        val item = get(id) ?: return
        item.fileUri?.let { uri ->
            runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            de.marne.streamplayer.App.instance.library.removeFile(uri)
        }
        Workdir.partFile(context, id).delete()
        Workdir.outFile(context, id).delete()
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private fun load(): List<DownloadItem> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { DownloadItem.fromJson(arr.getJSONObject(it)) }
            // Nach einem Neustart läuft nichts mehr: alles Unfertige gilt als pausiert.
            .map { if (it.active) it.copy(state = DlState.PAUSED, speedBps = 0) else it }
    }.getOrDefault(emptyList())

    private fun save(list: List<DownloadItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}

/** Arbeitsdateien eines Downloads, bevor er in die Mediathek wandert. */
object Workdir {
    fun dir(context: Context): File = File(context.filesDir, "work").apply { mkdirs() }
    fun partFile(context: Context, id: String) = File(dir(context), "$id.part")
    fun outFile(context: Context, id: String) = File(dir(context), "$id.mp4")
    fun streamCacheDir(context: Context) = File(context.filesDir, "stream-cache")
}
