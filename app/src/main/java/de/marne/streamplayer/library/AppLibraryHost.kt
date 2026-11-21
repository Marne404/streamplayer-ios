package de.marne.streamplayer.library

import de.marne.streamplayer.App
import kotlinx.coroutines.flow.StateFlow

/** [LibraryHost] auf Android: alles kommt aus [App]. */
class AppLibraryHost(private val app: App) : LibraryHost {
    override val tmdbAvailable: Boolean get() = app.enricher.available
    override fun prioritizeEnrich(titleId: String) = app.enricher.prioritize(titleId)
    override val watchDays: StateFlow<Map<Long, Long>> get() = app.progress.days
    override val homeContinue: StateFlow<Boolean> get() = app.settings.homeContinue
    override val homeMyList: StateFlow<Boolean> get() = app.settings.homeMyList
    override fun createCollection(name: String, firstTitle: String?): String = app.profiles.createCollection(name, firstTitle)
    override fun renameCollection(id: String, name: String) { app.profiles.renameCollection(id, name) }
    override fun deleteCollection(id: String) { app.profiles.deleteCollection(id) }
    override fun toggleInCollection(id: String, titleId: String) { app.profiles.toggleInCollection(id, titleId) }
}
