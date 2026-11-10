package de.marne.streamplayer.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.marne.streamplayer.MainActivity
import de.marne.streamplayer.core.R
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.tr

object Notifications {
    private const val CHANNEL_PROGRESS = "progress"
    private const val CHANNEL_RESULT = "result"
    const val ID_FOREGROUND = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, tr("Laufende Downloads", "Active downloads"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, tr("Fertige Downloads", "Finished downloads"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openDownloads(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, items: List<DownloadItem>): Notification {
        val running = items.filter { it.state == DlState.RUNNING || it.state == DlState.MERGING }
        val waiting = items.count { it.state == DlState.WAITING_WIFI }
        val title = when {
            running.size == 1 -> running.first().title
            running.size > 1 -> tr("${running.size} Downloads laufen", "${running.size} downloads in progress")
            waiting > 0 -> tr("Wartet auf WLAN", "Waiting for Wi-Fi")
            else -> "Downloads"
        }
        val text = when {
            running.size == 1 -> Format.metaLine(running.first())
            running.size > 1 -> running.sumOf { it.speedBps }.let { if (it > 0) Format.speed(it) else "" }
            waiting > 0 -> tr("$waiting in der Warteschlange", "$waiting queued")
            else -> ""
        }
        val progress = if (running.isEmpty()) -1 else (running.map { it.progress }.average() * 100).toInt()
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openDownloads(context))
            .apply { if (progress >= 0) setProgress(100, progress, progress == 0) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun done(context: Context, item: DownloadItem) = post(
        context, item.id.hashCode(),
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(tr("Geladen", "Downloaded"))
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(openDownloads(context))
            .build()
    )

    fun failed(context: Context, item: DownloadItem) = post(
        context, item.id.hashCode(),
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(tr("Download fehlgeschlagen", "Download failed"))
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(openDownloads(context))
            .build()
    )

    @SuppressLint("MissingPermission") // geprüft direkt davor
    private fun post(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
