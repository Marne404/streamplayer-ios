package de.marne.streamplayer.ios

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.TitleCollection
import de.marne.streamplayer.library.Keys
import de.marne.streamplayer.library.LibraryHost
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.sync.SyncJson
import de.marne.streamplayer.ui.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSUUID

/**
 * Änderungen vom iPad ins Konto – dieselben Dokumente und Regeln wie auf Android:
 * Profil (Meine Liste, Sammlungen) als ganzes Profil-Dokument, Fortschritt je Titel. Die Anzeige
 * ändert sich sofort; geschrieben wird der Reihe nach und immer der neueste Stand. Schlägt
 * Speichern fehl, lädt [onFailed] alles neu aus dem Konto.
 */
internal class CloudEdits(
    private val store: Firestore,
    private val library: CloudLibrary,
    private val uid: String,
    private val scope: CoroutineScope,
    private val get: () -> CloudData?,
    private val set: (CloudData) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val lock = Mutex()

    private fun save(what: suspend () -> Unit) {
        scope.launch {
            lock.withLock {
                try { what() } catch (e: Exception) { onFailed(e.message ?: "Speichern fehlgeschlagen") }
            }
        }
    }

    fun editProfile(pid: String, change: (Profile) -> Profile) {
        val d = get() ?: return
        val old = d.profiles.firstOrNull { it.id == pid } ?: return
        val new = change(old)
        if (new == old) return
        set(d.copy(profiles = d.profiles.map { if (it.id == pid) new else it }))
        save {
            // Der neueste Stand zum Zeitpunkt des Schreibens – nicht der beim Antippen.
            val latest = get()?.profiles?.firstOrNull { it.id == pid } ?: return@save
            store.write("users/$uid/profiles/$pid", SyncJson.profileText(latest))
            library.patchCache(uid, profile = latest)
        }
    }

    /** Wie ProgressStore.setWatched: Markieren ist kein Schauen, die gezählte Sehzeit bleibt. */
    fun setWatched(pid: String, keys: List<String>, watched: Boolean) {
        val d = get() ?: return
        var map = d.progress[pid].orEmpty()
        val now = nowMillis()
        keys.forEach { key ->
            val dur = map[key]?.durationMs ?: 0
            val w = map[key]?.countedMs ?: 0
            map = map + (key to if (watched) Progress(dur, dur, true, now, w) else Progress(0, dur, false, now, w))
        }
        set(d.copy(progress = d.progress + (pid to map)))
        keys.mapNotNull { Keys.parse(it)?.first }.distinct().forEach { titleId ->
            save {
                val latest = get()?.progress?.get(pid).orEmpty().filterKeys { Keys.parse(it)?.first == titleId }
                store.write("users/$uid/profiles/$pid/progress/$titleId", SyncJson.progressText(latest))
                library.patchCache(uid, progress = Triple(pid, titleId, latest))
            }
        }
    }

    /** [LibraryHost] fürs Konto: Sammlungen ändern das Profil wie ProfileStore auf Android. */
    fun host(pid: () -> String): LibraryHost = object : LibraryHost {
        override val tmdbAvailable = false
        override fun prioritizeEnrich(titleId: String) = Unit
        override val watchDays: StateFlow<Map<Long, Long>> = MutableStateFlow(emptyMap())
        override val homeContinue: StateFlow<Boolean> = MutableStateFlow(true)
        override val homeMyList: StateFlow<Boolean> = MutableStateFlow(true)

        override fun createCollection(name: String, firstTitle: String?): String {
            val c = TitleCollection(NSUUID().UUIDString().lowercase(), name.trim().ifEmpty { "…" }, listOfNotNull(firstTitle), nowMillis())
            editProfile(pid()) { it.copy(collections = it.collections + c) }
            return c.id
        }

        override fun renameCollection(id: String, name: String) = editProfile(pid()) { p ->
            p.copy(collections = p.collections.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
        }

        override fun deleteCollection(id: String) = editProfile(pid()) { p -> p.copy(collections = p.collections.filterNot { it.id == id }) }

        override fun toggleInCollection(id: String, titleId: String) = editProfile(pid()) { p ->
            p.copy(collections = p.collections.map { c ->
                if (c.id != id) c else if (titleId in c.items) c.copy(items = c.items - titleId) else c.copy(items = listOf(titleId) + c.items)
            })
        }
    }

    fun toggleList(pid: String, titleId: String) = editProfile(pid) { p ->
        if (titleId in p.list) p.copy(list = p.list - titleId) else p.copy(list = listOf(titleId) + p.list)
    }
}
