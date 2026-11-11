package de.marne.streamplayer.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.marne.streamplayer.App
import de.marne.streamplayer.detect.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-Service mit eigener Warteschlange. Läuft nur, solange etwas zu tun ist.
 * Alle Zustandswechsel passieren im Hauptthread; geladen wird in den Engines auf IO.
 */
@OptIn(FlowPreview::class)
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = HashMap<String, Job>()
    private val store get() = App.instance.downloads
    private val settings get() = App.instance.settings
    private val progressive by lazy { ProgressiveEngine(App.instance.http) }
    private val streams by lazy { StreamEngine(applicationContext, App.instance.http) }
    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground()
        scope.launch {
            store.items.sample(1000).collect { items ->
                if (!destroyed) runCatching {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.ID_FOREGROUND, Notifications.progress(this@DownloadService, items))
                }
            }
        }
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_PAUSE -> id?.let(::pause)
            ACTION_REMOVE -> id?.let(::remove)
        }
        pump()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        // Laufendes gilt als pausiert; beim nächsten Start geht es mit Range bzw. Cache weiter.
        jobs.keys.toList().forEach { id -> store.update(id) { it.copy(state = DlState.PAUSED, speedBps = 0) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_FOREGROUND,
            Notifications.progress(this, store.items.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    // ---------- Warteschlange ----------

    private fun pump() {
        if (destroyed) return
        val wifiOk = !settings.wifiOnly.value || isUnmetered()
        val limit = settings.parallel.value
        for (item in store.items.value.sortedBy { it.createdAt }) {
            val waiting = item.state == DlState.QUEUED || item.state == DlState.WAITING_WIFI
            if (!waiting || item.id in jobs) continue
            if (!wifiOk) {
                if (item.state != DlState.WAITING_WIFI) store.update(item.id) { it.copy(state = DlState.WAITING_WIFI) }
                continue
            }
            if (jobs.size >= limit) {
                if (item.state != DlState.QUEUED) store.update(item.id) { it.copy(state = DlState.QUEUED) }
                continue
            }
            start(item)
        }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        if (store.items.value.any { it.state == DlState.WAITING_WIFI || it.state == DlState.QUEUED }) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun start(item: DownloadItem) {
        store.update(item.id) { it.copy(state = DlState.RUNNING, error = null, speedBps = 0) }
        val job = scope.launch {
            try {
                val (file, mime) = when (item.kind) {
                    MediaKind.FILE -> {
                        val part = Workdir.partFile(this@DownloadService, item.id)
                        progressive.run(item, part) { done, total, speed ->
                            store.update(item.id) {
                                it.copy(
                                    bytesDone = done,
                                    bytesTotal = total ?: it.bytesTotal,
                                    progress = if (total != null && total > 0) done.toFloat() / total else it.progress,
                                    speedBps = speed,
                                )
                            }
                        }
                        part to MediaStoreSaver.mimeFor(item)
                    }
                    MediaKind.HLS, MediaKind.DASH -> {
                        val meter = SpeedMeter()
                        val tLoad = android.os.SystemClock.elapsedRealtime()
                        streams.download(item) { bytes, percent ->
                            meter.onTotal(bytes)
                            store.update(item.id) {
                                it.copy(
                                    bytesDone = bytes,
                                    // Laden ist der Großteil, Zusammenfügen die letzten 10 %.
                                    progress = if (percent >= 0) percent / 100f * 0.9f else it.progress,
                                    speedBps = meter.bytesPerSecond,
                                )
                            }
                        }
                        android.util.Log.i("Download", "Segmente laden ${android.os.SystemClock.elapsedRealtime() - tLoad} ms")
                        store.update(item.id) { it.copy(state = DlState.MERGING, speedBps = 0, progress = 0.9f) }
                        val out = Workdir.outFile(this@DownloadService, item.id)
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        streams.export(item, out) { p ->
                            store.update(item.id) { it.copy(progress = 0.9f + 0.1f * p) }
                        }
                        android.util.Log.i("Download", "Zusammenfügen ${android.os.SystemClock.elapsedRealtime() - t0} ms, ${out.length() / 1_000_000} MB")
                        withContext(Dispatchers.IO) { streams.purge(item) }
                        out to MediaStoreSaver.mimeFor(item)
                    }
                }
                val tSave = android.os.SystemClock.elapsedRealtime()
                val saved = withContext(Dispatchers.IO) {
                    MediaStoreSaver.save(this@DownloadService, file, item.title, mime)
                }
                android.util.Log.i("Download", "In die Mediathek ${android.os.SystemClock.elapsedRealtime() - tSave} ms")
                // Einzeldateien kennen ihre Dauer erst jetzt; Streams brachten sie aus dem Manifest mit.
                val duration = item.durationMs ?: withContext(Dispatchers.IO) { readDuration(saved.uri) }
                store.update(item.id) {
                    it.copy(
                        state = DlState.DONE,
                        progress = 1f,
                        speedBps = 0,
                        bytesDone = saved.bytes,
                        bytesTotal = saved.bytes,
                        fileUri = saved.uri.toString(),
                        fileName = saved.name,
                        mime = mime,
                        durationMs = duration,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                // Untertitel der Seite neben die Datei legen – offline gibt es sonst keine.
                if (item.subtitles.isNotEmpty()) {
                    val local = withContext(Dispatchers.IO) {
                        de.marne.streamplayer.subtitles.Subtitles.fetch(this@DownloadService, App.instance.http, item.subtitles, item.id, item.userAgent)
                    }
                    App.instance.subtitles.add(saved.uri.toString(), local)
                }
                item.libraryKey?.let { App.instance.library.attachOffline(it, saved.uri.toString()) }
                store.get(item.id)?.let { Notifications.done(this@DownloadService, it) }
                de.marne.streamplayer.stats.Usage.downloadDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                store.update(item.id) { it.copy(state = DlState.FAILED, speedBps = 0, error = Errors.describe(e)) }
                store.get(item.id)?.let { Notifications.failed(this@DownloadService, it) }
                de.marne.streamplayer.stats.Usage.downloadFailed()
            }
        }
        jobs[item.id] = job
        job.invokeOnCompletion {
            main.post {
                if (jobs[item.id] === job) jobs.remove(item.id)
                pump()
            }
        }
    }

    private fun readDuration(uri: android.net.Uri): Long? = runCatching {
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(this, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    private fun pause(id: String) {
        jobs.remove(id)?.cancel()
        store.update(id) { if (it.state == DlState.DONE) it else it.copy(state = DlState.PAUSED, speedBps = 0) }
    }

    private fun remove(id: String) {
        val item = store.get(id) ?: return
        jobs.remove(id)?.cancel()
        store.remove(id)
        scope.launch(Dispatchers.IO) { streams.purge(item) }
    }

    // ---------- Netz ----------

    private fun isUnmetered(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post { onNetworkChanged() }
            }

            override fun onLost(network: Network) {
                main.post { onNetworkChanged() }
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
    }

    private fun onNetworkChanged() {
        if (destroyed) return
        if (settings.wifiOnly.value && !isUnmetered()) {
            // Mobilfunk: Laufendes anhalten und warten lassen.
            jobs.keys.toList().forEach { id ->
                jobs.remove(id)?.cancel()
                store.update(id) { it.copy(state = DlState.WAITING_WIFI, speedBps = 0) }
            }
        }
        pump()
    }

    companion object {
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_REMOVE = "remove"
        private const val EXTRA_ID = "id"

        @Volatile
        var running = false
            private set

        /** Startet die Warteschlange (oder weckt sie). */
        fun kick(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun resume(context: Context, id: String) {
            App.instance.downloads.update(id) { it.copy(state = DlState.QUEUED, error = null) }
            kick(context)
        }

        fun pause(context: Context, id: String) {
            if (running) {
                context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, id))
            } else {
                App.instance.downloads.update(id) { it.copy(state = DlState.PAUSED, speedBps = 0) }
            }
        }

        fun remove(context: Context, id: String) {
            if (running) {
                context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_REMOVE).putExtra(EXTRA_ID, id))
            } else {
                val item = App.instance.downloads.get(id) ?: return
                App.instance.downloads.remove(id)
                if (item.kind != MediaKind.FILE) {
                    Thread { StreamEngine(context.applicationContext, App.instance.http).purge(item) }.start()
                }
            }
        }
    }
}
