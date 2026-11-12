package de.marne.streamplayer

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    /** Bibliotheken mit Freunden teilen (nur ansehen). */
    lateinit var sharing: de.marne.streamplayer.account.Sharing
        private set

    override fun onCreate() {
        super.onCreate()
        // Selbst-Update: im Debug-Build leer, dann fragt die App nie.
        de.marne.streamplayer.update.Updater.configure(BuildConfig.UPDATE_URL.ifEmpty { null }?.let { "$it/version.json" }.orEmpty())
        instance = this
        settings = Settings(this)
        de.marne.streamplayer.ui.Lang.current = settings.language.value
        http = Http.client(this)
        de.marne.streamplayer.stats.Usage.init(this)
        downloads = DownloadStore(this)
        subtitles = de.marne.streamplayer.subtitles.SubtitleStore(this)
        library = LibraryStore(this)
        profiles = de.marne.streamplayer.account.ProfileStore(this)
        progress = ProgressStore(this, profiles.activeId)
        meta = MetaFetcher(http) { Http.defaultUserAgent(this) }
        tmdb = Tmdb(http, key = { settings.tmdbKey.value }, language = { settings.tmdbLanguage })
        enricher = Enricher(tmdb, library, settings, filesDir)
        enricher.upgradeOld()
        // Einmalig (1.12.0): ältere Importe, die Filme als „Serie mit einer Folge“ hatten, zu Filmen
        // machen – Fortschritt zieht mit, TMDB sucht sie nun als Film.
        if (!settings.flag("fix_single_episode_v1")) {
            val ids = library.fixSingleEpisodeSeries()
            ids.forEach(progress::episodeToMovie)
            enricher.enqueue(ids)
            settings.setFlag("fix_single_episode_v1")
        }
        importer = Importer(this, library, enricher)
        adblock = AdBlocker(this, http, settings).also { it.start() }
        Notifications.createChannels(this)
        cloud = de.marne.streamplayer.account.Cloud(this)
        sync = de.marne.streamplayer.account.CloudSync(this, cloud, library, profiles, progress).also { it.start() }
        sharing = de.marne.streamplayer.account.Sharing(cloud, library)
        de.marne.streamplayer.widget.ContinueWidget.observe(this)
        // Sitzung = App kommt nach vorn; beim Gehen die Zahlen schicken (wenn es Zeit ist).
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    de.marne.streamplayer.stats.Usage.session()
                    // Abgebrochener Bilder-Download (App zu, Handy neu gestartet): weiter. Läuft noch ein
                    // TMDB-Abgleich: Dienst dazu, damit er beim Schließen der App nicht stehen bleibt.
                    de.marne.streamplayer.library.Artwork.resume(this)
                    if (enricher.status.value.running) de.marne.streamplayer.library.LibraryWorkService.start(this)
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    de.marne.streamplayer.stats.Usage.maybeSend()
                    // Für die Größe des Bild-Speichers beim nächsten Start (s. Artwork.cacheLimit).
                    runCatching { coil3.SingletonImageLoader.get(this).diskCache?.size }.getOrNull()?.let { getSharedPreferences("artwork", MODE_PRIVATE).edit().putLong("used", it).apply() }
                }
                else -> Unit
            }
        })
        // Großer Import: Der Abgleich beginnt, während die App offen ist – der Dienst hält ihn am Leben.
        kotlinx.coroutines.MainScope().launch {
            enricher.status.map { it.running }.distinctUntilChanged().collect { running ->
                val front = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                if (running && front) de.marne.streamplayer.library.LibraryWorkService.start(this@App)
            }
        }
    }

    // Vorschaubilder: Netz über denselben OkHttp-Client, fertige Videos über ein Standbild.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                add(VideoFrameDecoder.Factory())
            }
            // Bilder bleiben auf dem Gerät: Cover, Banner und Standbilder kommen nach dem ersten Mal
            // ohne Netz (auch unterwegs). Größer als Coils Standard (2 %, höchstens 250 MB), damit
            // „Alle Bilder vorab laden“ eine große Bibliothek ganz fasst. Gleicher Ordner wie bisher.
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").absolutePath.let { okio.Path.Companion.run { it.toPath() } })
                    .maxSizeBytes(de.marne.streamplayer.library.Artwork.cacheLimit(cacheDir, getSharedPreferences("artwork", MODE_PRIVATE).getLong("used", 0L)))
                    .build()
            }
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
