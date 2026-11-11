package de.marne.streamplayer.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.LibraryStore
import de.marne.streamplayer.library.ProgressMerge
import de.marne.streamplayer.library.ProgressStore
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

/**
 * Abgleich mit dem Konto. Die Daten auf dem Gerät bleiben die Quelle, aus der die App liest;
 * Firestore ist nur der Weg zwischen den Geräten.
 *
 * Kosten sind eine Entwurfsvorgabe:
 * - Gelesen wird nur, was sich seit dem letzten Abgleich geändert hat (updatedAt > since),
 *   und nur, solange die App im Vordergrund ist.
 * - Geschrieben wird nur, was sich geändert hat (Vergleich über Hash). Fortschritt erst nach
 *   20 s Ruhe – während des Schauens meldet der Player alle 10 s, das ergäbe 360 Schreibvorgänge
 *   pro Stunde; so ist es einer pro Sitzung.
 * - Bilder sind eigene Dokumente und werden nur geholt, wenn sie auf dem Gerät fehlen.
 *
 * Aufbau im Konto:
 *   users/{uid}/titles/{titleId}                       { json, updatedAt, deleted }
 *   users/{uid}/profiles/{profileId}                   { json, updatedAt, deleted }
 *   users/{uid}/profiles/{profileId}/progress/{titleId} { json, updatedAt, deleted }
 *   users/{uid}/covers/{name}                          { data, updatedAt }
 *   users/{uid}/libraries/{libraryId}                  { json, updatedAt, deleted }
 *
 * Geteilte Bibliotheken (Sharing.kt):
 *   shared/{libraryId}/titles/{titleId}                { json, updatedAt, deleted }  – schreibt nur der Besitzer
 * Der Besitzer spiegelt die Titel dieser Bibliothek dorthin; Mitglieder lesen nur (readOnly-Kanal)
 * und haben die Titel lokal in einer Bibliothek mit joined = true – die geht nicht ins eigene Konto.
 * Gelöscht wird weich (deleted = true), damit andere Geräte es über dieselbe Abfrage erfahren.
 */
@OptIn(FlowPreview::class)
class CloudSync(
    private val context: Context,
    private val cloud: Cloud,
    private val library: LibraryStore,
    private val profiles: ProfileStore,
    private val progress: ProgressStore,
) {
    sealed interface Status {
        data object Off : Status
        data object Working : Status
        data class Done(val at: Long) : Status
        data class Error(val message: String) : Status
    }

    /** Name einer Freundes-Bibliothek, die gerade weggefallen ist (für einen Hinweis). */
    private val _revoked = MutableStateFlow<String?>(null)
    val revoked: StateFlow<String?> = _revoked.asStateFlow()
    fun revokedShown() { _revoked.value = null }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Session? = null
    private var foreground = false
    private val posters = File(context.filesDir, "posters")

    fun start() {
        if (!cloud.available) return
        scope.launch {
            cloud.user.collect { u ->
                if (session?.uid == u?.uid) return@collect
                session?.close()
                session = u?.let { Session(it.uid) }
                _status.value = if (u == null) Status.Off else Status.Working
                if (foreground) session?.open()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { foreground = true; session?.open() }
                // Im Hintergrund: Ausstehendes abschicken, dann nicht mehr zuhören (kostet sonst Lesevorgänge).
                Lifecycle.Event.ON_STOP -> { foreground = false; session?.pause() }
                else -> Unit
            }
        })
    }

    /** Sofort alles Geänderte schicken (Knopf in den Einstellungen). */
    fun syncNow() {
        session?.let { s -> scope.launch { s.flush() } }
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "sync", e)
        _status.value = Status.Error(
            when ((e as? FirebaseFirestoreException)?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> tr("Keine Berechtigung. Sind die Firestore-Regeln eingespielt?", "Permission denied. Are the Firestore rules deployed?")
                FirebaseFirestoreException.Code.UNAVAILABLE -> tr("Keine Verbindung – wird nachgeholt.", "No connection – will retry.")
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> tr("Firestore ist im Projekt noch nicht angelegt.", "Firestore isn't set up in the project yet.")
                else -> e.message ?: tr("Abgleich fehlgeschlagen.", "Sync failed.")
            }
        )
    }

    private fun done() {
        if (_status.value !is Status.Error || session != null) _status.value = Status.Done(System.currentTimeMillis())
    }

    /** Alles, was zu einem angemeldeten Konto gehört. */
    private inner class Session(val uid: String) {
        private val db = cloud.db!!
        private val root = db.collection("users").document(uid)
        private val state = SyncState(File(context.filesDir, "sync-$uid.json"))
        private val mutex = Mutex()
        private val channels = linkedMapOf<String, Channel>()
        private val jobs = mutableListOf<Job>()
        private var open = false
        private var closed = false

        init {
            channels["titles"] = Channel("titles", root.collection("titles"),
                // Titel aus Bibliotheken von Freunden gehören nicht ins eigene Konto: die kommen von dort.
                local = {
                    val joined = library.libraries.value.filter { it.joined }.map { it.id }.toSet()
                    library.titles.value.filter { it.library !in joined }.associate { it.id to SyncCodec.titleJson(it) }
                },
                apply = ::applyTitle,
                beforePush = ::uploadCovers,
            )
            // Bibliotheken („Anime“, „Serien“): nur Name und Farbe – welche Titel drin sind, steht am Titel.
            channels["libraries"] = Channel("libraries", root.collection("libraries"),
                local = { library.libraries.value.associate { it.id to it.toJson().toString() } },
                apply = ::applyLibrary,
            )
            channels["profiles"] = Channel("profiles", root.collection("profiles"),
                local = { profiles.profiles.value.associate { it.id to it.toJson().toString() } },
                apply = ::applyProfile,
            )
            syncProgressChannels()
            syncShareChannels()
        }

        /**
         * Je geteilter eigener Bibliothek ein Kanal, der ihre Titel nach shared/… spiegelt; je
         * Bibliothek eines Freundes einer, der nur liest. Läuft bei jeder Änderung der Bibliotheken.
         */
        fun syncShareChannels() {
            val libs = library.libraries.value
            val wanted = libs.filter { it.shared || it.joined }.associateBy { "share:${it.id}" }
            // Weg: Kanal schließen und seinen Stand vergessen – träte man wieder bei, käme sonst nur
            // „was seitdem neu ist“ und die älteren Titel fehlten.
            channels.keys.filter { it.startsWith("share:") && it !in wanted }.forEach { k ->
                channels.remove(k)?.detach()
                state.drop(k)
            }
            wanted.forEach { (key, l) ->
                if (key in channels) return@forEach
                val ref = db.collection("shared").document(l.id).collection("titles")
                val ch = if (l.joined) Channel(key, ref,
                    local = { emptyMap() },
                    apply = { id, json ->
                        if (json == null) library.removeShared(l.id, id)
                        else library.putShared(l.id, LibTitle.fromJson(JSONObject(json)))
                    },
                    readOnly = true,
                    // Kein Zugriff mehr: Der Freund hat das Teilen beendet oder einen entfernt.
                    onError = { e ->
                        if ((e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            library.removeJoined(l.id)
                            _revoked.value = l.label
                        } else fail(e)
                    },
                ) else Channel(key, ref,
                    local = { library.titles.value.filter { it.library == l.id }.associate { it.id to SyncCodec.sharedTitleJson(it) } },
                    apply = { _, _ -> },
                )
                channels[key] = ch
                if (open) ch.listen()
            }
        }

        fun open() {
            if (open || closed) return
            open = true
            channels.values.forEach { it.listen() }
            jobs += scope.launch {
                library.titles.drop(1).debounce(2_000).collect {
                    push("titles")
                    channels.keys.filter { it.startsWith("share:") }.forEach { push(it) }
                }
            }
            jobs += scope.launch { library.libraries.drop(1).debounce(1_000).collect { syncShareChannels(); push("libraries") } }
            jobs += scope.launch { profiles.profiles.drop(1).debounce(1_000).collect { syncProgressChannels(); push("profiles") } }
            jobs += scope.launch { progress.byProfile.drop(1).debounce(20_000).collect { pushProgress() } }
            jobs += scope.launch { retryMissingCovers() }
        }

        fun pause() {
            if (!open) return
            open = false
            jobs.forEach { it.cancel() }; jobs.clear()
            scope.launch {
                flush()
                channels.values.forEach { it.detach() }
            }
        }

        fun close() {
            closed = true
            pause()
        }

        suspend fun flush() = mutex.withLock { channels.values.toList().forEach { it.pushLocked() } }

        private suspend fun push(key: String) = mutex.withLock { channels[key]?.pushLocked() }

        private suspend fun pushProgress() = mutex.withLock {
            channels.filterKeys { it.startsWith("progress/") }.values.forEach { it.pushLocked() }
        }

        /** Je Profil ein Kanal für seinen Fortschritt; gelöschte Profile räumen ihren im Konto ab. */
        fun syncProgressChannels() {
            val ids = profiles.profiles.value.map { it.id }.toSet()
            ids.forEach { pid ->
                val key = "progress/$pid"
                if (key !in channels) {
                    val ch = Channel(key, root.collection("profiles").document(pid).collection("progress"),
                        local = { ProgressMerge.byTitle(progress.byProfile.value[pid].orEmpty()).mapValues { ProgressMerge.toJson(it.value).toString() } },
                        apply = { tid, json ->
                            if (json == null) progress.removeRemote(pid, tid)
                            else progress.mergeRemote(pid, ProgressMerge.fromJson(JSONObject(json)))
                        },
                    )
                    channels[key] = ch
                    if (open) ch.listen()
                }
            }
            channels.keys.filter { it.startsWith("progress/") && it.removePrefix("progress/") !in ids }.forEach { key ->
                val ch = channels.remove(key) ?: return@forEach
                scope.launch {
                    // Lokal ist der Fortschritt schon weg: ein letzter Abgleich schreibt die Löschungen.
                    mutex.withLock { ch.pushLocked() }
                    ch.detach()
                }
            }
        }

        // --- Anwenden, was aus dem Konto kommt ---

        private suspend fun applyTitle(id: String, json: String?) {
            if (json == null) {
                library.remove(id)
                progress.removeTitle(id)
                return
            }
            SyncCodec.coverNames(json).forEach { ensureCover(it) }
            val t = LibTitle.fromJson(SyncCodec.resolveCovers(json, ::localCover))
            // Derselbe Katalog-Eintrag auf zwei Geräten importiert, bevor sie sich kannten: zusammenführen.
            val dup = t.sourceId?.let { sid -> library.titles.value.firstOrNull { it.id != t.id && it.sourceId == sid } }
            if (dup != null) progress.rekey(dup.id, t.id)
            library.putRemote(t, replacing = dup?.id)
        }

        private fun applyLibrary(id: String, json: String?) {
            if (json == null) library.removeRemoteLibrary(id)
            else library.putRemoteLibrary(de.marne.streamplayer.library.Library.fromJson(JSONObject(json)))
        }

        private fun applyProfile(id: String, json: String?) {
            if (json == null) {
                profiles.removeRemote(id)
                progress.removeProfile(id)
            } else profiles.putRemote(Profile.fromJson(JSONObject(json)))
        }

        // --- Bilder ---

        private fun localCover(name: String): String? = File(posters, name).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }

        private suspend fun ensureCover(name: String): Boolean {
            val f = File(posters, name)
            if (f.exists()) return true
            return runCatching {
                val blob = root.collection("covers").document(name).get().await().getBlob("data")?.takeIf { it.toBytes().isNotEmpty() } ?: return false
                withContext(Dispatchers.IO) {
                    posters.mkdirs()
                    val tmp = File(posters, "$name.tmp")
                    tmp.writeBytes(blob.toBytes())
                    tmp.renameTo(f)
                }
                state.covers += name
                true
            }.getOrElse { Log.w(TAG, "cover $name", it); false }
        }

        /** Bilder, die beim letzten Mal nicht zu holen waren (offline), jetzt nachladen. */
        private suspend fun retryMissingCovers() = mutex.withLock {
            library.titles.value.forEach { t ->
                val json = t.toJson().toString()
                val missing = SyncCodec.coverNames(json)
                if (missing.isNotEmpty() && missing.all { ensureCover(it) }) {
                    library.putRemote(LibTitle.fromJson(SyncCodec.resolveCovers(json, ::localCover)))
                }
            }
        }

        private suspend fun uploadCovers(writes: List<Pair<String, String?>>) {
            writes.mapNotNull { it.second }.flatMap { SyncCodec.coverNames(it) }.distinct()
                .filter { it !in state.covers }
                .forEach { name ->
                    val f = File(posters, name)
                    if (!f.exists()) return@forEach
                    val bytes = withContext(Dispatchers.IO) { fitForDocument(f.readBytes()) } ?: return@forEach
                    root.collection("covers").document(name)
                        .set(mapOf("data" to Blob.fromBytes(bytes), "updatedAt" to FieldValue.serverTimestamp()))
                        .addOnFailureListener { e -> state.covers -= name; fail(e) }
                    state.covers += name
                }
        }

        /** Ein Firestore-Dokument fasst knapp 1 MB. Größere Bilder werden verkleinert, bis sie passen. */
        private fun fitForDocument(bytes: ByteArray): ByteArray? {
            // Anlass: eine 0-Byte-Datei (abgebrochenes Schreiben) wurde hochgeladen und hätte auf jedem
            // anderen Gerät das Bild ersetzt. Nur was sich als Bild lesen lässt, geht ins Konto.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bytes.isEmpty() || bounds.outWidth <= 0) return null
            if (bytes.size <= MAX_COVER) return bytes
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            var quality = 85
            repeat(8) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_COVER) return out.toByteArray()
                if (quality > 70) quality -= 8
                else bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt(), (bmp.height * 0.8f).toInt(), true)
            }
            return null
        }

        /**
         * Eine Sammlung im Konto, gespiegelt aus einer lokalen Liste. [local] liefert je ID den Text,
         * der ins Konto gehört; was sich am Hash nicht geändert hat, wird nicht geschrieben.
         */
        private inner class Channel(
            val key: String,
            val ref: CollectionReference,
            val local: () -> Map<String, String>,
            val apply: suspend (id: String, json: String?) -> Unit,
            val beforePush: suspend (List<Pair<String, String?>>) -> Unit = {},
            /** Nur lesen (Bibliothek eines Freundes): nie schreiben. */
            val readOnly: Boolean = false,
            val onError: ((Throwable) -> Unit)? = null,
        ) {
            private val st = state.channel(key)
            private var reg: ListenerRegistration? = null
            /**
             * Erst schreiben, wenn der Stand des Kontos einmal da ist – sonst überschriebe ein neues
             * Gerät beim ersten Anmelden das Konto mit seiner (älteren oder leeren) Fassung.
             */
            private var ready = st.since > 0

            fun listen() {
                if (reg != null) return
                // Nur Neues seit dem letzten Abgleich: das hält die Lesevorgänge klein.
                val q = if (st.since > 0) ref.whereGreaterThan("updatedAt", Timestamp(Date(st.since))) else ref
                reg = q.addSnapshotListener { snap, err ->
                    if (err != null) { (onError ?: ::fail)(err); return@addSnapshotListener }
                    if (snap != null) scope.launch { mutex.withLock { onSnapshot(snap) } }
                }
            }

            fun detach() {
                reg?.remove()
                reg = null
            }

            private suspend fun onSnapshot(snap: QuerySnapshot) {
                for (ch in snap.documentChanges) {
                    if (ch.type == DocumentChange.Type.REMOVED) continue
                    val d = ch.document
                    // Eigene, noch nicht bestätigte Schreibvorgänge: kennen wir schon.
                    if (d.metadata.hasPendingWrites()) continue
                    val ts = d.getTimestamp("updatedAt") ?: continue
                    val deleted = d.getBoolean("deleted") == true
                    val json = d.getString("json")
                    val h = if (deleted || json == null) TOMBSTONE else SyncCodec.hash(json)
                    if (st.hashes[d.id] != h) {
                        runCatching { apply(d.id, if (h == TOMBSTONE) null else json) }.onFailure { Log.w(TAG, "apply $key/${d.id}", it) }
                        st.hashes[d.id] = h
                    }
                    st.since = maxOf(st.since, ts.toDate().time)
                }
                if (!snap.metadata.isFromCache && !ready) {
                    ready = true
                    pushLocked()
                }
                state.save()
                if (ready && !snap.metadata.isFromCache) done()
            }

            /** Geänderte Einträge schreiben. Aufruf nur mit gehaltenem [mutex]. */
            suspend fun pushLocked() {
                if (!ready || readOnly) return
                val now = local()
                val writes = mutableListOf<Pair<String, String?>>()
                now.forEach { (id, json) -> if (st.hashes[id] != SyncCodec.hash(json)) writes += id to json }
                st.hashes.forEach { (id, h) -> if (h != TOMBSTONE && id !in now) writes += id to null }
                if (writes.isEmpty()) return
                _status.value = Status.Working
                beforePush(writes)
                writes.chunked(400).forEach { part ->
                    val batch = db.batch()
                    part.forEach { (id, json) ->
                        batch.set(ref.document(id), mapOf(
                            "json" to (json ?: ""),
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "deleted" to (json == null),
                        ))
                    }
                    // Optimistisch als erledigt vermerken: Firestore hat den Auftrag auf dem Gerät
                    // gespeichert und liefert ihn nach, auch nach einem Neustart ohne Netz.
                    part.forEach { (id, json) -> st.hashes[id] = json?.let(SyncCodec::hash) ?: TOMBSTONE }
                    batch.commit()
                        .addOnSuccessListener { done() }
                        .addOnFailureListener { e ->
                            // Abgelehnt (z. B. Regeln): beim nächsten Mal erneut versuchen.
                            scope.launch { mutex.withLock { part.forEach { st.hashes[it.first] = RETRY }; state.save() } }
                            fail(e)
                        }
                }
                state.save()
            }
        }
    }

    /** Was schon im Konto ist: je Sammlung Zeitstempel und Hashes, dazu die hochgeladenen Bilder. */
    private class SyncState(private val file: File) {
        class Ch(var since: Long = 0, val hashes: MutableMap<String, String> = mutableMapOf())

        private val channels = mutableMapOf<String, Ch>()
        val covers = mutableSetOf<String>()

        init {
            runCatching {
                val o = JSONObject(file.readText())
                val cs = o.getJSONObject("channels")
                cs.keys().forEach { k ->
                    val c = cs.getJSONObject(k)
                    val h = c.getJSONObject("hashes")
                    channels[k] = Ch(c.optLong("since"), h.keys().asSequence().associateWith { h.getString(it) }.toMutableMap())
                }
                val cv = o.optJSONArray("covers") ?: JSONArray()
                for (i in 0 until cv.length()) covers += cv.getString(i)
            }
        }

        fun channel(key: String) = channels.getOrPut(key) { Ch() }

        fun drop(key: String) { channels.remove(key); save() }

        fun save() {
            val cs = JSONObject()
            channels.forEach { (k, c) -> cs.put(k, JSONObject().put("since", c.since).put("hashes", JSONObject(c.hashes as Map<*, *>))) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("channels", cs).put("covers", JSONArray(covers.toList())).toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "CloudSync"
        private const val TOMBSTONE = "-"
        private const val RETRY = "?"
        private const val MAX_COVER = 950_000
    }
}
