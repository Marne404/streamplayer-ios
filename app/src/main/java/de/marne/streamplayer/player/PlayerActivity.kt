package de.marne.streamplayer.player

import de.marne.streamplayer.subtitles.listFromJson
import de.marne.streamplayer.subtitles.listToJson
import de.marne.streamplayer.subtitles.toConfiguration

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
import de.marne.streamplayer.core.R
import de.marne.streamplayer.App
import de.marne.streamplayer.library.Episode
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.net.Http
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.tr

/**
 * Spielt eine fertige Datei aus der Mediathek oder – als Vorschau – einen gefundenen Stream
 * mit denselben Kopfzeilen, mit denen auch die Seite ihn abruft.
 *
 * Die Oberfläche ist eigene Compose-Steuerung ([PlayerScreen]) über einer nackten PlayerView:
 * Die Standard-Controls von media3 sehen nach Android aus, nicht nach der Mediathek davor.
 *
 * Wer die App verlässt, während das Video läuft, bekommt es als kleines Fenster (Bild im Bild)
 * über allem anderen weitergespielt – mit Zurück/Pause/Vor im Fenster-Menü.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var key: String? = null
    /** Das Video ohne Untertitel und die Untertitel dazu – eine eigene Datei kommt hinzu, ohne neu anzufangen. */
    private var baseItem: MediaItem? = null
    private var subs: List<Pair<de.marne.streamplayer.subtitles.SubTrack, Boolean>> = emptyList()
    /** Läuft gerade als kleines Fenster: Compose blendet dann alle Steuerung aus. */
    private var pip by mutableStateOf(false)

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
        // Nur ein Player auf einmal: Wer aus der Bibliothek etwas Neues startet, während das alte
        // noch im kleinen Fenster läuft, will nicht zwei Tonspuren hören.
        current?.get()?.takeIf { it !== this }?.finish()
        current = java.lang.ref.WeakReference(this)
        if (savedInstanceState == null) de.marne.streamplayer.stats.Usage.play()
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
            // Im kleinen Fenster läuft es neben anderen Apps: Spielt eine andere Ton ab oder kommt ein
            // Anruf, hält das Video an; Kopfhörer ab ebenso – sonst tönt es plötzlich aus dem Lautsprecher.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
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
                updatePip()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updatePip()

            override fun onPlaybackStateChanged(state: Int) {
                // Zu Ende geschaut: als gesehen merken – das macht „Nächste Folge“ möglich.
                if (state == androidx.media3.common.Player.STATE_ENDED) saveProgress(ended = true)
            }
        })
        p.prepare()
        p.playWhenReady = true
        player = p
        ContextCompat.registerReceiver(this, pipActions, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePip()

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
                    pip = pip,
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

    /** Knöpfe im Fenster-Menü kommen als Broadcast zurück (nur an diese App, s. [pipAction]). */
    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = player ?: return
            when (intent.getIntExtra(EXTRA_PIP_CMD, 0)) {
                PIP_PLAY -> { if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) p.seekTo(0); p.play() }
                PIP_PAUSE -> p.pause()
                PIP_BACK -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                PIP_FORWARD -> p.seekTo(p.currentPosition + 10_000)
            }
        }
    }

    private fun pipAction(cmd: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, cmd, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon).setTint(android.graphics.Color.WHITE), title, title, pi)
    }

    /**
     * Fenster-Einstellungen aktuell halten: Seitenverhältnis wie das Video, Knöpfe passend zum
     * Zustand, und ab Android 12 geht es von selbst ins Fenster – aber nur, solange es wirklich läuft.
     */
    private fun pipParams(): PictureInPictureParams {
        val p = player
        val playing = p?.isPlaying == true
        val b = PictureInPictureParams.Builder()
            .setActions(listOf(
                pipAction(PIP_BACK, R.drawable.ic_replay_10, tr("10 s zurück", "Back 10 s")),
                if (playing) pipAction(PIP_PAUSE, R.drawable.ic_pause, tr("Pause", "Pause"))
                else pipAction(PIP_PLAY, R.drawable.ic_play_arrow, tr("Abspielen", "Play")),
                pipAction(PIP_FORWARD, R.drawable.ic_forward_10, tr("10 s vor", "Forward 10 s")),
            ))
        // Android lässt nur 1:2,39 bis 2,39:1 zu; ohne Bild (Audio, noch am Laden) 16:9.
        val size = p?.videoSize
        val w = size?.width ?: 0
        val h = size?.height ?: 0
        val ratio = if (w > 0 && h > 0) (w * (size?.pixelWidthHeightRatio ?: 1f)) / h else 16f / 9f
        val clamped = ratio.coerceIn(1f / 2.39f, 2.39f)
        b.setAspectRatio(Rational((clamped * 10_000).toInt(), 10_000))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(playing)
            b.setSeamlessResizeEnabled(false) // Video: lieber kurz überblenden als verzerrt ziehen
        }
        return b.build()
    }

    private fun updatePip() {
        if (player == null || isFinishing) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Vor Android 12 gibt es kein automatisches Fenster: Beim Verlassen (Home-Taste) selbst hinein. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < 31 && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            // Fenster weggewischt (nicht groß gemacht): Dann ist die Activity schon gestoppt – ganz schließen,
            // sonst hängt ein unsichtbarer Player im Verlauf herum.
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                finish()
                return
            }
            // Zurück im Vollbild: Leisten wieder weg (das Fenster hat sie mitunter hergeholt).
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
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
        de.marne.streamplayer.stats.Usage.watched(played)
        played = 0
    }

    override fun onDestroy() {
        ticker.removeCallbacks(saveTick)
        runCatching { unregisterReceiver(pipActions) }
        if (current?.get() === this) current = null
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

        private const val ACTION_PIP = "de.marne.streamplayer.PIP"
        private const val EXTRA_PIP_CMD = "cmd"
        private const val PIP_PLAY = 1
        private const val PIP_PAUSE = 2
        private const val PIP_BACK = 3
        private const val PIP_FORWARD = 4

        /** Der laufende Player (auch im kleinen Fenster) – ein neuer schließt ihn. */
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null

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
