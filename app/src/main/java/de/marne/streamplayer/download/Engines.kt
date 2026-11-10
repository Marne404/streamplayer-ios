package de.marne.streamplayer.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import de.marne.streamplayer.detect.MediaKind
import de.marne.streamplayer.net.Http
import de.marne.streamplayer.net.Http.headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Misst die Geschwindigkeit über ein gleitendes Fenster von etwa einer Sekunde. */
class SpeedMeter {
    private var windowStart = SystemClock.elapsedRealtime()
    private var windowBytes = 0L
    private var lastTotal = -1L
    var bytesPerSecond = 0L
        private set

    fun onTotal(total: Long) {
        if (lastTotal >= 0) windowBytes += (total - lastTotal).coerceAtLeast(0)
        lastTotal = total
        val now = SystemClock.elapsedRealtime()
        val dt = now - windowStart
        if (dt >= 1000) {
            bytesPerSecond = windowBytes * 1000 / dt
            windowStart = now
            windowBytes = 0
        }
    }
}

// ---------- Einzeldatei, mit Fortsetzen über Range ----------

class ProgressiveEngine(private val http: OkHttpClient) {

    suspend fun run(item: DownloadItem, part: File, onProgress: (done: Long, total: Long?, speed: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            var done = if (part.exists()) part.length() else 0L
            val request = Request.Builder()
                .url(item.url)
                .headers(Http.headersFor(item.url, item.referer, item.userAgent))
                .apply { if (done > 0) header("Range", "bytes=$done-") }
                .build()
            val call = http.newCall(request)
            val handle = coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { r ->
                    if (r.code == 416 && done > 0) return@withContext // Schon vollständig.
                    if (!r.isSuccessful) throw HttpStatusException(r.code)
                    if (done > 0 && r.code != 206) {
                        // Der Server kann keine Teilbereiche: von vorn.
                        done = 0
                        part.delete()
                    }
                    val body = r.body ?: throw IOException("Leere Antwort")
                    val total = if (r.code == 206) {
                        r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    } else {
                        body.contentLength().takeIf { it > 0 }
                    }
                    val meter = SpeedMeter()
                    var lastReport = 0L
                    FileOutputStream(part, done > 0).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            meter.onTotal(done)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= 400) {
                                lastReport = now
                                onProgress(done, total, meter.bytesPerSecond)
                            }
                        }
                    }
                    onProgress(done, total, 0)
                    if (total != null && done < total) throw IOException("Verbindung abgebrochen")
                }
            } finally {
                handle.dispose()
            }
        }
}

// ---------- HLS und DASH: Media3 lädt die Segmente, Transformer macht daraus eine MP4 ----------

object StreamCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            Workdir.streamCacheDir(context),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}

class StreamEngine(private val context: Context, private val http: OkHttpClient) {

    // Mehrere Segmente gleichzeitig: deutlich schneller als eins nach dem anderen.
    private val executor = Executors.newFixedThreadPool(4)

    private fun upstream(item: DownloadItem): DataSource.Factory {
        val okhttp: HttpDataSource.Factory = OkHttpDataSource.Factory(http)
            .setUserAgent(item.userAgent)
            .setDefaultRequestProperties(buildMap { item.referer?.let { put("Referer", it) } })
        // Cookies je Adresse frisch aus der WebView: Segment-Hosts setzen oft eigene.
        return ResolvingDataSource.Factory(okhttp) { spec ->
            val cookie = Http.cookiesFor(spec.uri.toString())
            if (cookie != null) spec.withAdditionalHeaders(mapOf("Cookie" to cookie)) else spec
        }
    }

    private fun cacheFactory(item: DownloadItem, readOnly: Boolean = false): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(StreamCache.get(context))
            .setUpstreamDataSourceFactory(upstream(item))
            .apply { if (readOnly) setCacheWriteDataSinkFactory(null) }

    private fun mediaItem(item: DownloadItem): MediaItem = MediaItem.Builder()
        .setUri(item.url)
        .setMimeType(if (item.kind == MediaKind.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
        .setStreamKeys(item.keys.map { it.toStreamKey() })
        .build()

    private fun downloader(item: DownloadItem): Downloader = when (item.kind) {
        MediaKind.HLS -> HlsDownloader(mediaItem(item), cacheFactory(item), executor)
        MediaKind.DASH -> DashDownloader(mediaItem(item), cacheFactory(item), executor)
        MediaKind.FILE -> error("Kein Stream")
    }

    /** Lädt alle Segmente der gewählten Qualität in den Cache. Bereits geladene werden übersprungen. */
    suspend fun download(item: DownloadItem, onProgress: (bytes: Long, percent: Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val d = downloader(item)
            val handle = coroutineContext.job.invokeOnCompletion { d.cancel() }
            try {
                runInterruptible {
                    d.download { _, bytes, percent -> onProgress(bytes, percent) }
                }
            } finally {
                handle.dispose()
            }
        }

    /**
     * Setzt die geladenen Segmente zu einer MP4 zusammen. Ohne Effekte kopiert Transformer
     * die Spuren nur um (Transmux), dekodiert also nichts neu.
     */
    suspend fun export(item: DownloadItem, out: File, onProgress: (Float) -> Unit) {
        // Erst der schnelle Weg (FastHls); passt der Strom nicht dazu, der Transformer.
        if (item.kind == MediaKind.HLS) {
            try {
                FastHls.export(context, cacheFactory(item, readOnly = true), item.url, item.keys.map { it.toStreamKey() }, out, out, onProgress)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.i("Download", "Schneller Weg nicht möglich (${e.message}) – Transformer")
                onProgress(0f)
            }
        }
        transform(item, out, onProgress)
    }

    private suspend fun transform(item: DownloadItem, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        out.delete()
        val sources = DefaultMediaSourceFactory(cacheFactory(item, readOnly = true))
        val loader = DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            sources,
            DataSourceBitmapLoader(context),
        )
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context)
                .setAssetLoaderFactory(loader)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 500)
                }
            }
            transformer.start(mediaItem(item), out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.removeCallbacks(poll)
                handler.post { transformer.cancel() }
            }
        }
    }

    /** Entfernt die Segmente aus dem Cache. Fehler sind egal: schlimmstenfalls bleibt Platz belegt. */
    fun purge(item: DownloadItem) {
        if (item.kind == MediaKind.FILE) return
        runCatching { downloader(item).remove() }
    }
}
