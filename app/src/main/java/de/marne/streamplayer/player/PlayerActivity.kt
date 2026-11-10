package de.marne.streamplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.media3.ui.PlayerView
import de.marne.streamplayer.App
import de.marne.streamplayer.net.Http

/**
 * Spielt eine fertige Datei aus der Mediathek oder – als Vorschau – einen gefundenen Stream
 * mit denselben Kopfzeilen, mit denen auch die Seite ihn abruft.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val userAgent = intent.getStringExtra(EXTRA_UA) ?: Http.defaultUserAgent(this)

        val http = OkHttpDataSource.Factory(App.instance.http)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(buildMap { referer?.let { put("Referer", it) } })
        val withCookies = ResolvingDataSource.Factory(http) { spec ->
            Http.cookiesFor(spec.uri.toString())?.let { spec.withAdditionalHeaders(mapOf("Cookie" to it)) } ?: spec
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, withCookies)))
            .build()
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(intent.getStringExtra(EXTRA_MIME))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra(EXTRA_TITLE)).build())
                .build()
        )
        p.prepare()
        p.playWhenReady = true
        player = p

        val view = PlayerView(this).apply {
            this.player = p
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        setContentView(view)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: Uri, title: String, mime: String? = null, referer: String? = null, userAgent: String? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_UA, userAgent)
    }
}
