package de.marne.streamplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import de.marne.streamplayer.adblock.AdBlocker
import de.marne.streamplayer.data.Settings
import de.marne.streamplayer.download.DownloadStore
import de.marne.streamplayer.download.Notifications
import de.marne.streamplayer.library.LibraryStore
import de.marne.streamplayer.library.MetaFetcher
import de.marne.streamplayer.library.ProgressStore
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
    lateinit var adblock: AdBlocker
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var meta: MetaFetcher
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        http = Http.client(this)
        downloads = DownloadStore(this)
        library = LibraryStore(this)
        progress = ProgressStore(this)
        meta = MetaFetcher(http) { Http.defaultUserAgent(this) }
        adblock = AdBlocker(this, http, settings).also { it.start() }
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
