package de.marne.streamplayer.ios

import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.library.LibTitle
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.Progress
import de.marne.streamplayer.sync.SyncJson
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** Alles aus dem Konto, was die Bibliothek braucht. */
internal data class CloudData(
    val titles: List<LibTitle>,
    val libraries: List<Library>,
    val profiles: List<Profile>,
    /** Profil-ID → Fortschritt (Schlüssel → Stand). */
    val progress: Map<String, Map<String, Progress>>,
)

/**
 * Lädt die Bibliothek aus Firestore – dieselben Dokumente, die CloudSync auf Android schreibt –
 * und legt sie als Rohtext auf dem iPad ab. Beim nächsten Start ist sie sofort da und wird dann
 * im Hintergrund erneuert. Nur Lesen: das iPad schreibt (noch) nichts ins Konto.
 */
internal class CloudLibrary(private val store: Firestore) {
    private fun dir(kind: ULong): String {
        val d = (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String) + "/streamplayer"
        NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        return d
    }

    private val supportDir by lazy { dir(NSApplicationSupportDirectory) }
    private val coverDir by lazy { dir(NSCachesDirectory) + "/covers".also { NSFileManager.defaultManager.createDirectoryAtPath(dir(NSCachesDirectory) + it, true, null, null) } }

    private fun cacheFile(uid: String) = "$supportDir/cloud-$uid.json"

    /** Was beim letzten Mal geladen wurde – oder null. */
    suspend fun cached(uid: String): CloudData? = withContext(Dispatchers.Default) {
        val text = NSString.stringWithContentsOfFile(cacheFile(uid), NSUTF8StringEncoding, null) ?: return@withContext null
        runCatching { decode(json.parseToJsonElement(text) as JsonObject) }.getOrNull()
    }

    fun forget(uid: String) {
        NSFileManager.defaultManager.removeItemAtPath(cacheFile(uid), null)
    }

    suspend fun load(uid: String, onStep: (String) -> Unit): CloudData = coroutineScope {
        val root = "users/$uid"
        onStep("Bibliotheken")
        val libs = store.list("$root/libraries")
        val profiles = store.list("$root/profiles")
        onStep("Titel")
        val titles = store.list("$root/titles")
        val joined = libs.mapNotNull { d -> d.json?.takeUnless { d.deleted }?.let { runCatching { SyncJson.library(it) }.getOrNull() } }.filter { it.joined }
        val shared = joined.map { l -> async { l.id to runCatching { store.list("shared/${l.id}/titles") }.getOrDefault(emptyList()) } }.awaitAll()
        onStep("Fortschritt")
        val progress = profiles.filterNot { it.deleted }.map { p -> async { p.id to store.list("$root/profiles/${p.id}/progress") } }.awaitAll()

        // Eigene Cover (covers/{name}) holen, die noch nicht auf dem iPad liegen.
        val needed = titles.filterNot { it.deleted }.flatMap { d -> runCatching { SyncJson.coverNames(SyncJson.parse(d.json!!)) }.getOrDefault(emptyList()) }.toSet()
        val missing = needed.filterNot { NSFileManager.defaultManager.fileExistsAtPath("$coverDir/$it") }
        if (missing.isNotEmpty()) onStep("Eigene Cover")
        missing.chunked(8).forEach { batch ->
            batch.map { name -> async { name to store.cover(root, name) } }.awaitAll().forEach { (name, bytes) -> if (bytes != null) writeBytes("$coverDir/$name", bytes) }
        }

        val raw = buildJsonObject {
            put("libraries", texts(libs))
            put("profiles", texts(profiles))
            put("titles", texts(titles))
            put("shared", buildJsonObject { shared.forEach { (id, docs) -> put(id, texts(docs)) } })
            put("progress", buildJsonObject { progress.forEach { (pid, docs) -> put(pid, texts(docs)) } })
        }
        withContext(Dispatchers.Default) {
            (raw.toString() as NSString).writeToFile(cacheFile(uid), atomically = true, encoding = NSUTF8StringEncoding, error = null)
            decode(raw)
        }
    }

    private fun texts(docs: List<Firestore.Doc>) = buildJsonArray { docs.forEach { d -> if (!d.deleted && d.json != null) add(JsonPrimitive(d.json)) } }

    private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    private fun localCover(name: String): String? = "$coverDir/$name".takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }?.let { "file://$it" }

    private fun decode(raw: JsonObject): CloudData {
        val libraries = raw.strings("libraries").mapNotNull { runCatching { SyncJson.library(it) }.getOrNull() }
        val own = raw.strings("titles").mapNotNull { t ->
            runCatching { SyncJson.title(SyncJson.resolveCovers(SyncJson.parse(t), ::localCover)) }.getOrNull()
        }
        // Bibliotheken von Freunden: wie putShared auf Android – der Titel gehört dorthin, eigene
        // Bilder vom Gerät des Freundes gibt es hier nicht.
        val ownIds = own.map { it.id }.toSet()
        val shared = (raw["shared"] as? JsonObject).orEmpty().flatMap { (lid, arr) ->
            (arr as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.mapNotNull { t ->
                runCatching { SyncJson.title(t) }.getOrNull()
                    ?.takeIf { it.id !in ownIds }
                    ?.let { it.copy(libraryId = lid, alsoIn = emptyList(), offline = emptyMap(), customPoster = it.customPoster?.takeIf { p -> p.startsWith("http") }) }
            }
        }
        val profiles = raw.strings("profiles").mapNotNull { runCatching { SyncJson.profile(it) }.getOrNull() }
        val progress = (raw["progress"] as? JsonObject).orEmpty().mapValues { (_, arr) ->
            (arr as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                .fold(emptyMap<String, Progress>()) { acc, t -> acc + runCatching { SyncJson.progress(t) }.getOrDefault(emptyMap()) }
        }
        val libs = (if (libraries.none { it.id == Library.MAIN }) listOf(Library(Library.MAIN, "")) else emptyList()) + libraries
        return CloudData(
            titles = (own.map { it.copy(offline = emptyMap()) } + shared).sortedByDescending { it.addedAt },
            libraries = libs.sortedWith(compareBy<Library> { it.id != Library.MAIN }.thenBy { it.createdAt }),
            profiles = profiles.sortedBy { it.createdAt },
            progress = progress,
        )
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile(path, atomically = true)
    }
}
