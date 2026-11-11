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
import de.marne.streamplayer.catalog.Tmdb
import de.marne.streamplayer.library.Enricher
import de.marne.streamplayer.library.Importer
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
    /** Untertitel-Dateien auf dem Gerät (zu Downloads gesichert oder selbst gewählt). */
    lateinit var subtitles: de.marne.streamplayer.subtitles.SubtitleStore
        private set
    lateinit var adblock: AdBlocker
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var meta: MetaFetcher
        private set
    lateinit var tmdb: Tmdb
        private set
    lateinit var enricher: Enricher
        private set
    lateinit var importer: Importer
        private set
    lateinit var profiles: de.marne.streamplayer.account.ProfileStore
        private set
    lateinit var cloud: de.marne.streamplayer.account.Cloud
        private set
    lateinit var sync: de.marne.streamplayer.account.CloudSync
        private set

    override fun onCreate() {
        super.onCreate()
        // Selbst-Update: im Debug-Build leer, dann fragt die App nie.
        de.marne.streamplayer.update.Updater.configure(BuildConfig.UPDATE_URL.ifEmpty { null }?.let { "$it/version.json" }.orEmpty())
        instance = this
        settings = Settings(this)
        de.marne.streamplayer.ui.Lang.current = settings.language.value
        http = Http.client(this)
        downloads = DownloadStore(this)
        subtitles = de.marne.streamplayer.subtitles.SubtitleStore(this)
        library = LibraryStore(this)
        profiles = de.marne.streamplayer.account.ProfileStore(this)
        progress = ProgressStore(this, profiles.activeId)
        meta = MetaFetcher(http) { Http.defaultUserAgent(this) }
        tmdb = Tmdb(http, key = { settings.tmdbKey.value }, language = { settings.tmdbLanguage })
        enricher = Enricher(tmdb, library, settings)
        enricher.upgradeOld()
        importer = Importer(this, library, enricher)
        adblock = AdBlocker(this, http, settings).also { it.start() }
        Notifications.createChannels(this)
        cloud = de.marne.streamplayer.account.Cloud(this)
        sync = de.marne.streamplayer.account.CloudSync(this, cloud, library, profiles, progress).also { it.start() }
        de.marne.streamplayer.widget.ContinueWidget.observe(this)
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
