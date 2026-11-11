package de.marne.streamplayer.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import de.marne.streamplayer.App
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.net.Http
import de.marne.streamplayer.ui.design.IvoryInkTheme

/**
 * Spielt eine fertige Datei aus der Mediathek oder – als Vorschau – einen gefundenen Stream
 * mit denselben Kopfzeilen, mit denen auch die Seite ihn abruft.
 *
 * Die Oberfläche ist eigene Compose-Steuerung ([PlayerScreen]) über einer nackten PlayerView:
 * Die Standard-Controls von media3 sehen nach Android aus, nicht nach der Mediathek davor.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var key: String? = null
    /** Das Video ohne Untertitel und die Untertitel dazu – eine eigene Datei kommt hinzu, ohne neu anzufangen. */
    private var baseItem: MediaItem? = null
    private var subs: List<Pair<de.marne.streamplayer.subtitles.SubTrack, Boolean>> = emptyList()

    /** Eigene Untertitel-Datei gewählt (Dateiwähler aus PlayerScreen). */
    private fun onSubtitlePicked(picked: Uri?) {
        val p = player ?: return
        val item = baseItem ?: return
        if (picked == null) return
        val name = runCatching {
            contentResolver.query(picked, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        // Gemerkt wird sie beim Film/der Folge (oder bei der Datei, wenn es kein Bibliothekseintrag ist).
        val own = App.instance.subtitles.import(key ?: intent.data.toString(), picked, name)
        if (own == null) {
            android.widget.Toast.makeText(this, de.marne.streamplayer.ui.tr("Datei lässt sich nicht lesen.", "Couldn't read the file."), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        subs = subs.map { it.first to false } + (own to true)
        val pos = p.currentPosition
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
        p.setMediaItem(item.buildUpon().setSubtitleConfigurations(subs.map { (s, on) -> s.toConfiguration(on) }).build(), pos)
        p.prepare()
    }
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            saveProgress()
            ticker.postDelayed(this, 10_000)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(0, de.marne.streamplayer.R.anim.player_exit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Schließen: wegblenden statt wegschieben – passend zum Überblenden beim Start.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, de.marne.streamplayer.R.anim.player_exit)
        }
        val uri = intent.data ?: return finish()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val userAgent = intent.getStringExtra(EXTRA_UA) ?: Http.defaultUserAgent(this)
        key = intent.getStringExtra(EXTRA_KEY)
        val startMs = intent.getLongExtra(EXTRA_START, 0)
        val info = PlaybackInfo.of(key, intent.getStringExtra(EXTRA_TITLE).orEmpty())

        // Aus der Bibliothek ist es ein Film oder eine Folge: quer, wie im Kino. Eine Vorschau aus dem
        // Browser kann alles sein (Hochkant-Clip, Audio) – die darf sich frei drehen.
        if (key != null) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val http = OkHttpDataSource.Factory(App.instance.http)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(buildMap { referer?.let { put("Referer", it) } })
        val withCookies = ResolvingDataSource.Factory(http) { spec ->
            Http.cookiesFor(spec.uri.toString())?.let { spec.withAdditionalHeaders(mapOf("Cookie" to it)) } ?: spec
        }
        // Untertitel: was die Seite hatte (mitgegeben), was beim Download gesichert oder früher
        // selbst gewählt wurde (auf dem Gerät).
        val pageSubs = de.marne.streamplayer.subtitles.SubTrack.listFromJson(intent.getStringExtra(EXTRA_SUBS)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() })
        val localSubs = App.instance.subtitles.forKeys(key, uri.toString())
        subs = (localSubs + pageSubs).distinctBy { it.url }.map { it to false }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, withCookies)))
            .build()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(intent.getStringExtra(EXTRA_MIME))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build())
            .build()
        baseItem = item
        p.setMediaItem(item.buildUpon().setSubtitleConfigurations(subs.map { (s, on) -> s.toConfiguration(on) }).build(), startMs)
        p.addListener(object : androidx.media3.common.Player.Listener {
            // Sehzeit: Die Uhr läuft nur, solange wirklich Bild kommt – Pause, Puffern und
            // Vorspulen zählen nicht (Sprünge ändern die Position, nicht die Uhr).
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playingSince = android.os.SystemClock.elapsedRealtime() else takePlayed()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Zu Ende geschaut: als gesehen merken – das macht „Nächste Folge“ möglich.
                if (state == androidx.media3.common.Player.STATE_ENDED) saveProgress(ended = true)
            }
        })
        p.prepare()
        p.playWhenReady = true
        player = p

        // Notch und Kamera-Loch: das Bild darf bis an den Rand, die Steuerung hält Abstand (Insets im Screen).
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            IvoryInkTheme(dark = true) {
                // Über Compose statt registerForActivityResult: dieselbe Art wie in MainActivity.
                val pickSubtitles = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), ::onSubtitlePicked,
                )
                PlayerScreen(
                    player = p,
                    info = info,
                    onBack = ::finish,
                    onPlayEpisode = ::playEpisode,
                    onPickSubtitles = { runCatching { pickSubtitles.launch(arrayOf("*/*")) } },
                )
            }
        }
    }

    /**
     * Andere Folge: Diese Activity kann die Seite nicht auflösen (das kann nur MainActivity mit ihrer
     * WebView). Fortschritt sichern, den Wunsch an die Bibliothek geben, schließen.
     */
    private fun playEpisode(t: LibTitle, e: Episode) {
        saveProgress()
        PlayerBridge.playEpisode(t.id, e.season, e.number)
        finish()
    }

    override fun onStart() {
        super.onStart()
        ticker.postDelayed(saveTick, 10_000)
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(saveTick)
        player?.pause()
        saveProgress()
    }

    /** Seit wann das Bild läuft (0 = steht); [played] sammelt bis zur nächsten Meldung. */
    private var playingSince = 0L
    private var played = 0L

    private fun takePlayed() {
        if (playingSince > 0) {
            played += android.os.SystemClock.elapsedRealtime() - playingSince
            playingSince = if (player?.isPlaying == true) android.os.SystemClock.elapsedRealtime() else 0L
        }
    }

    private fun saveProgress(ended: Boolean = false) {
        val k = key ?: return
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: return
        takePlayed()
        App.instance.progress.report(k, if (ended) duration else p.currentPosition, duration, ended, played)
        played = 0
    }

    override fun onDestroy() {
        ticker.removeCallbacks(saveTick)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_START = "start"
        private const val EXTRA_SUBS = "subs"

        /** key: Fortschritts-Schlüssel aus der Bibliothek; startMs: ab hier weiterspielen. */
        fun intent(
            context: Context, uri: Uri, title: String, mime: String? = null, referer: String? = null,
            userAgent: String? = null, key: String? = null, startMs: Long = 0,
            subtitles: List<de.marne.streamplayer.subtitles.SubTrack> = emptyList(),
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_START, startMs)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_UA, userAgent)
                .putExtra(EXTRA_SUBS, if (subtitles.isEmpty()) null else de.marne.streamplayer.subtitles.SubTrack.listToJson(subtitles).toString())
    }
}

/**
 * Was gerade läuft, so weit die Bibliothek es weiß. Ohne Schlüssel (Vorschau aus dem Browser) nur
 * der Titel – dann gibt es keine Folgen und keine „Nächste Folge“.
 */
data class PlaybackInfo(
    val title: String,
    val subtitle: String?,
    val libTitle: LibTitle?,
    val episode: Episode?,
    val next: Episode?,
) {
    companion object {
        fun of(key: String?, fallbackTitle: String): PlaybackInfo {
            val parsed = key?.let(Keys::parse)
            val t = parsed?.let { App.instance.library.get(it.first) }
            val ep = if (t != null && parsed.second != null && parsed.third != null) t.episode(parsed.second!!, parsed.third!!) else null
            if (t == null) return PlaybackInfo(fallbackTitle, null, null, null, null)
            val subtitle = when {
                ep != null -> listOfNotNull(ep.label, ep.title).joinToString(" · ")
                else -> t.shownYear?.toString()
            }
            return PlaybackInfo(t.title, subtitle, t, ep, ep?.let(t::next))
        }
    }
}
