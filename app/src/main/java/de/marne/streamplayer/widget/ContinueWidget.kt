package de.marne.streamplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.marne.streamplayer.App
import de.marne.streamplayer.MainActivity
import de.marne.streamplayer.R
import de.marne.streamplayer.library.ContinueItem
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.Shelves
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * „Weiterschauen“ auf dem Startbildschirm: bis zu drei Cover mit Fortschritt, ein Tipp spielt
 * weiter (über MainActivity – nur sie kann eine Seite auflösen). Gesperrte Profile zeigen nichts:
 * Was jemand schaut, soll nicht ohne PIN auf dem Startbildschirm stehen.
 */
class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { render(context.applicationContext) } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Mutex()
        private const val SLOTS = 3

        /** Neu zeichnen, wenn sich Fortschritt, Bibliothek, Profil oder Sprache ändern (gebündelt). */
        @OptIn(FlowPreview::class)
        fun observe(app: App) {
            scope.launch {
                combine(app.progress.all, app.library.titles, app.profiles.profiles, app.profiles.activeId, app.settings.language) { _, _, _, _, _ -> }
                    .debounce(1500)
                    .collect { render(app) }
            }
        }

        private suspend fun render(context: Context) = lock.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isEmpty()) return@withLock
            val app = App.instance
            val profile = app.profiles.active
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            views.setTextViewText(R.id.heading, tr("Weiterschauen", "Continue Watching"))
            views.setTextViewText(R.id.profile, if (app.profiles.profiles.value.size > 1) profile.name else "")
            views.setOnClickPendingIntent(R.id.root, open(context))

            val items = if (profile.locked) emptyList() else Shelves.continueWatching(app.library.titles.value, app.progress.all.value).take(SLOTS)
            val empty = when {
                profile.locked -> tr("${profile.name} ist gesperrt – zum Öffnen tippen.", "${profile.name} is locked – tap to open.")
                items.isEmpty() -> tr("Noch nichts angefangen. Was du schaust, steht hier.", "Nothing started yet. What you watch shows up here.")
                else -> null
            }
            views.setViewVisibility(R.id.empty, if (empty != null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.slots, if (empty != null) View.GONE else View.VISIBLE)
            empty?.let { views.setTextViewText(R.id.empty, it) }

            for (i in 0 until SLOTS) {
                val slot = id("slot$i")
                val item = items.getOrNull(i)
                // Leere Plätze bleiben unsichtbar, nicht weg: sonst würden zwei Cover breit gezogen.
                views.setViewVisibility(slot, if (item != null) View.VISIBLE else View.INVISIBLE)
                if (item == null) continue
                fill(context, views, i, item)
            }
            manager.updateAppWidget(ids, views)
        }

        private suspend fun fill(context: Context, views: RemoteViews, i: Int, c: ContinueItem) {
            val t = c.title
            views.setTextViewText(id("title$i"), t.title)
            views.setTextViewText(id("sub$i"), c.episode?.let { e -> if (c.isNextUp) tr("Weiter: ${e.label}", "Next: ${e.label}") else e.label } ?: tr("Film", "Movie"))
            val fraction = if (c.isNextUp) 0f else c.progress?.fraction ?: 0f
            views.setViewVisibility(id("prog$i"), if (fraction > 0f) View.VISIBLE else View.INVISIBLE)
            views.setProgressBar(id("prog$i"), 1000, (fraction * 1000).toInt(), false)
            val bitmap = cover(context, t.cover ?: t.offline.values.firstOrNull())
            if (bitmap != null) views.setImageViewBitmap(id("cover$i"), bitmap)
            else views.setImageViewResource(id("cover$i"), android.R.color.transparent)
            val key = c.episode?.let { Keys.episode(t, it) } ?: Keys.movie(t)
            views.setOnClickPendingIntent(id("slot$i"), play(context, key, i))
        }

        /**
         * Cover klein laden (RemoteViews schicken Bitmaps durch einen engen Kanal) und die Ecken
         * selbst runden – RemoteViews kann das auf älteren Android-Versionen nicht.
         */
        private suspend fun cover(context: Context, model: Any?): Bitmap? {
            model ?: return null
            val result = runCatching {
                SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(model).size(240, 360).allowHardware(false).build()
                )
            }.getOrNull() as? SuccessResult ?: return null
            val src = result.image.toBitmap()
            val w = 240
            val h = 360
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // Mittig zuschneiden (centerCrop), dann mit runden Ecken zeichnen.
            val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
            })
            Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            return out
        }

        private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 100,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun play(context: Context, key: String, i: Int): PendingIntent = PendingIntent.getActivity(
            context, 200 + i,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_PLAY)
                // Eigene Adresse je Eintrag: Sonst hielte Android alle Plätze für dieselbe Absicht.
                .setData(Uri.parse("streamplayer://play/${Uri.encode(key)}"))
                .putExtra(MainActivity.EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        @Suppress("DiscouragedApi")
        private fun id(name: String): Int = when (name) {
            "slot0" -> R.id.slot0; "slot1" -> R.id.slot1; "slot2" -> R.id.slot2
            "cover0" -> R.id.cover0; "cover1" -> R.id.cover1; "cover2" -> R.id.cover2
            "prog0" -> R.id.prog0; "prog1" -> R.id.prog1; "prog2" -> R.id.prog2
            "title0" -> R.id.title0; "title1" -> R.id.title1; "title2" -> R.id.title2
            "sub0" -> R.id.sub0; "sub1" -> R.id.sub1; else -> R.id.sub2
        }
    }
}
