package de.marne.streamplayer.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.marne.streamplayer.App
import de.marne.streamplayer.MainActivity
import de.marne.streamplayer.core.R
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Hält den Player am Leben, solange Bibliotheksarbeit läuft: Bilder vorab laden ([Artwork]) und der
 * TMDB-Abgleich ([Enricher]). Die Arbeit selbst machen die beiden – der Dienst ist nur der Grund für
 * Android, den Prozess nicht einzufrieren, sobald die App zu ist (Anlass: der Bilder-Download brach
 * beim Schließen der App einfach ab). Er beendet sich, wenn nichts mehr zu tun ist.
 */
class LibraryWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        ServiceCompat.startForeground(this, ID, notification(this, Artwork.run.value, App.instance.enricher.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            combine(Artwork.run, App.instance.enricher.status) { a, e -> a to e }.conflate().collect { (a, e) ->
                if (a?.running != true && !e.running) {
                    stopSelf()
                    return@collect
                }
                runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(this@LibraryWorkService, a, e)) }
                // Höchstens jede Sekunde neu zeichnen – Fortschritt kommt viel öfter.
                delay(1_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ARTWORK) Artwork.cancel(this)
        return START_NOT_STICKY
    }

    /** Android 15+: Datenabgleich darf nur begrenzt im Hintergrund laufen – dann sauber gehen; beim nächsten Öffnen geht es weiter. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "library_work"
        private const val ID = 7
        private const val ACTION_STOP_ARTWORK = "stop_artwork"

        /** Nur aus dem Vordergrund aufrufen (Android lässt Vordergrund-Dienste sonst nicht starten). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LibraryWorkService::class.java)) }
        }

        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Bibliothek aktualisieren", "Library updates"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }

        private fun notification(context: Context, a: Artwork.Run?, e: Enricher.Status): Notification {
            val artwork = a?.takeIf { it.running }
            val title = when {
                artwork?.waiting == true -> tr("Bilder: warte auf Netz", "Artwork: waiting for network")
                artwork != null -> tr("Bilder werden geladen", "Downloading artwork")
                else -> tr("Bibliothek wird mit TMDB abgeglichen", "Updating library from TMDB")
            }
            val parts = buildList {
                artwork?.let { add(tr("%,d von %,d Bildern", "%,d of %,d images").format(it.done, it.total)) }
                if (e.running) add(tr("TMDB: noch %,d", "TMDB: %,d left").format(e.total - e.done))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_image)
                .setContentTitle(title)
                .setContentText(parts.joinToString(" · "))
                .apply {
                    if (artwork != null) setProgress(artwork.total.coerceAtLeast(1), artwork.done, artwork.total == 0)
                    else if (e.running) setProgress(e.total.coerceAtLeast(1), e.done, false)
                    if (artwork != null) addAction(
                        0, tr("Bilder stoppen", "Stop artwork"),
                        PendingIntent.getService(context, 1, Intent(context, LibraryWorkService::class.java).setAction(ACTION_STOP_ARTWORK), PendingIntent.FLAG_IMMUTABLE),
                    )
                }
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .apply { if (Build.VERSION.SDK_INT >= 31) foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE }
                .build()
        }
    }
}
