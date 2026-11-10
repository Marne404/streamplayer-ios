package de.marne.streamplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.download.DownloadStore
import de.marne.streamplayer.download.Notifications
import de.marne.streamplayer.net.Http
import okhttp3.OkHttpClient

/** Hält die wenigen app-weiten Objekte. Kein DI-Framework: es sind vier Stück. */
class App : Application(), SingletonImageLoader.Factory {

    lateinit var settings: Settings
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var downloads: DownloadStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        http = Http.client(this)
        downloads = DownloadStore(this)
        Notifications.createChannels(this)
    }

    // Vorschaubilder: Netz über denselben OkHttp-Client, fertige Videos über ein Standbild.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                add(VideoFrameDecoder.Factory())
            }
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
