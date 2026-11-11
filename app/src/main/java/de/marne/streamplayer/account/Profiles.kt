package de.marne.streamplayer.account

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Ein Profil wie bei Netflix: gemeinsame Bibliothek, eigener Fortschritt, eigene „Meine Liste“.
 * [list]: Titel-IDs, die neuesten vorn. Liegt im Profil, damit sie mit dem Profil abgeglichen wird.
 */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val list: List<String> = emptyList(),
    /** Profilsperre wie bei Netflix: Hash der vierstelligen PIN ([PinHash]), null = offen. */
    val lock: String? = null,
    /** Eigene Sammlungen („Weihnachten“, „Mit den Kindern“). Gehören dem Profil wie „Meine Liste“. */
    val collections: List<TitleCollection> = emptyList(),
) {
    val locked: Boolean get() = lock != null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color).put("createdAt", createdAt)
        .put("list", JSONArray(list)).put("lock", lock)
        .put("collections", JSONArray().apply { collections.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            o.getString("id"), o.optString("name", "Profil"), o.optInt("color"), o.optLong("createdAt"),
            o.optJSONArray("list")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
            if (o.isNull("lock")) null else o.optString("lock").takeIf { it.isNotEmpty() },
            o.optJSONArray("collections")?.let { a -> List(a.length()) { TitleCollection.fromJson(a.getJSONObject(it)) } }.orEmpty(),
        )
        /** Das erste Profil übernimmt den Fortschritt aus der Zeit vor den Profilen. */
        const val DEFAULT_ID = "main"
    }
}

/**
 * Profile dieses Geräts (mit Konto: des Kontos). Welches gerade schaut, gehört dem Gerät allein –
 * zwei Handys mit demselben Konto können verschiedene Profile offen haben.
 */
@OptIn(FlowPreview::class)
class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString("active", null) ?: _profiles.value.first().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    /**
     * Steht ein neuer Start an, bei dem „Wer schaut?“ fragen darf? Neu ist ein kalter Start oder die
     * Rückkehr nach [RESTART_AFTER] im Hintergrund. Nur der Prozess zählt nicht: Android hält ihn oft
     * stundenlang am Leben, dann käme die Frage beim Öffnen am nächsten Abend nie. Und jedes kurze
     * Wegwechseln (Player, Benachrichtigung, Datei wählen) darf sie nicht auslösen.
     */
    private val _needsChoice = MutableStateFlow(true)
    val needsChoice: StateFlow<Boolean> = _needsChoice.asStateFlow()
    private var backgroundSince = 0L

    init {
        scope.launch { _profiles.drop(1).debounce(300).collect { save(it) } }
        // Muss auf dem Hauptthread registriert werden – der Store entsteht in App.onCreate.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> backgroundSince = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> if (backgroundSince > 0 && SystemClock.elapsedRealtime() - backgroundSince >= RESTART_AFTER) {
                    _needsChoice.value = true
                }
                else -> Unit
            }
        })
    }

    /** Für diesen Start ist entschieden – gewählt, weggetippt oder im Willkommens-Ablauf. */
    fun chosen() { _needsChoice.value = false }

    val active: Profile get() = _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.first()

    fun select(id: String) {
        if (_profiles.value.none { it.id == id }) return
        _activeId.value = id
        chosen()
        prefs.edit().putString("active", id).apply()
    }

    fun add(name: String): Profile {
        val used = _profiles.value.map { it.color }.toSet()
        val color = (0 until COLORS).firstOrNull { it !in used } ?: (_profiles.value.size % COLORS)
        val p = Profile(UUID.randomUUID().toString(), name.trim().ifEmpty { de.marne.streamplayer.ui.tr("Profil", "Profile") }, color, System.currentTimeMillis())
        _profiles.update { it + p }
        return p
    }

    /** Name und Farbe auf einmal (Willkommens-Ablauf). */
    fun update(id: String, name: String, color: Int) = _profiles.update { list ->
        list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, color = color.mod(COLORS)) else it }
    }

    /** „Meine Liste“ des aktiven Profils: hinzufügen (vorn) oder entfernen. */
    fun toggleList(titleId: String) {
        val id = _activeId.value
        _profiles.update { list ->
            list.map { p ->
                if (p.id != id) p
                else if (titleId in p.list) p.copy(list = p.list - titleId)
                else p.copy(list = listOf(titleId) + p.list)
            }
        }
    }

    // --- Eigene Sammlungen (des aktiven Profils) ---

    private fun editActive(change: (Profile) -> Profile) {
        val id = _activeId.value
        _profiles.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** Neue Sammlung, optional gleich mit einem ersten Titel. Gibt ihre ID zurück. */
    fun createCollection(name: String, firstTitle: String? = null): String {
        val c = TitleCollection(UUID.randomUUID().toString(), name.trim().ifEmpty { "…" }, listOfNotNull(firstTitle), System.currentTimeMillis())
        editActive { it.copy(collections = it.collections + c) }
        return c.id
    }

    fun renameCollection(id: String, name: String) = editActive { p ->
        p.copy(collections = p.collections.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    fun deleteCollection(id: String) = editActive { p -> p.copy(collections = p.collections.filterNot { it.id == id }) }

    /** Titel in die Sammlung (vorn) oder wieder heraus. */
    fun toggleInCollection(id: String, titleId: String) = editActive { p ->
        p.copy(collections = p.collections.map { c ->
            if (c.id != id) c else if (titleId in c.items) c.copy(items = c.items - titleId) else c.copy(items = listOf(titleId) + c.items)
        })
    }

    /** PIN setzen (4 Ziffern) oder mit null die Sperre aufheben. */
    fun setLock(id: String, pin: String?) = _profiles.update { list ->
        list.map { if (it.id == id) it.copy(lock = pin?.let(PinHash::make)) else it }
    }

    /**
     * PIN prüfen. Nach je fünf Fehlversuchen 30 Sekunden Pause – vier Ziffern sind schnell
     * durchprobiert, so dauert es wenigstens Stunden. [lockedUntil] sagt der Oberfläche, wie lange noch.
     */
    fun checkPin(id: String, pin: String): Boolean {
        if (SystemClock.elapsedRealtime() < lockedUntil) return false
        val ok = _profiles.value.firstOrNull { it.id == id }?.lock?.let { PinHash.matches(it, pin) } ?: true
        if (ok) failures = 0 else if (++failures % 5 == 0) lockedUntil = SystemClock.elapsedRealtime() + 30_000
        return ok
    }

    private var failures = 0
    var lockedUntil = 0L
        private set

    fun rename(id: String, name: String) = _profiles.update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it } }

    /** Das letzte Profil bleibt immer: ohne Profil gäbe es keinen Ort für Fortschritt. */
    fun remove(id: String): Boolean {
        if (_profiles.value.size <= 1) return false
        _profiles.update { list -> list.filterNot { it.id == id } }
        if (_activeId.value == id) select(_profiles.value.first().id)
        return true
    }

    // --- Sync ---

    /** Vom Server: anlegen/ändern. */
    fun putRemote(p: Profile) = _profiles.update { list ->
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else (list + p).sortedBy { it.createdAt }
    }

    /** Vom Server gelöscht. Das letzte lokale Profil bleibt trotzdem. */
    fun removeRemote(id: String) {
        if (_profiles.value.size > 1) remove(id)
    }

    /**
     * Sicherung einspielen. Profile werden über ID, sonst über den Namen wiedererkannt.
     * [identity]: Name, Farbe und PIN aus der Datei (sonst bleiben die hiesigen; neue Profile
     * bekommen Name und Farbe, aber keine PIN). [list]/[collections]: diese Teile übernehmen.
     * Ersetzen mit [identity]: Profile, die die Datei nicht kennt, gehen. [titleIds]: Datei-ID → ID hier.
     * Rückgabe: Datei-Profil-ID → Profil-ID hier (für den Fortschritt).
     */
    fun importBackup(
        incoming: List<Profile>, replace: Boolean, identity: Boolean, list: Boolean, collections: Boolean,
        titleIds: Map<String, String>,
    ): Map<String, String> {
        val pidMap = mutableMapOf<String, String>()
        fun ids(l: List<String>) = l.map { titleIds[it] ?: it }.distinct()
        _profiles.update { current ->
            val out = current.toMutableList()
            for (inc in incoming) {
                val i = out.indexOfFirst { it.id == inc.id }.takeIf { it >= 0 }
                    ?: out.indexOfFirst { it.name.equals(inc.name, ignoreCase = true) }.takeIf { it >= 0 }
                val incList = ids(inc.list)
                val incCols = inc.collections.map { it.copy(items = ids(it.items)) }
                if (i == null) {
                    out += inc.copy(
                        lock = if (identity) inc.lock else null,
                        list = if (list) incList else emptyList(),
                        collections = if (collections) incCols else emptyList(),
                    )
                    pidMap[inc.id] = inc.id
                    continue
                }
                val old = out[i]
                pidMap[inc.id] = old.id
                out[i] = old.copy(
                    name = if (identity && replace) inc.name else old.name,
                    color = if (identity && replace) inc.color else old.color,
                    // Zusammenführen nimmt eine PIN nie weg, setzt aber eine, wo noch keine ist.
                    lock = when {
                        identity && replace -> inc.lock
                        identity && old.lock == null -> inc.lock
                        else -> old.lock
                    },
                    list = when {
                        !list -> old.list
                        replace -> incList
                        else -> old.list + incList.filter { it !in old.list }
                    },
                    collections = when {
                        !collections -> old.collections
                        replace -> incCols
                        else -> {
                            val merged = old.collections.map { c ->
                                val m = incCols.firstOrNull { it.id == c.id || it.name.equals(c.name, ignoreCase = true) }
                                if (m == null) c else c.copy(items = c.items + m.items.filter { it !in c.items })
                            }
                            merged + incCols.filter { n -> old.collections.none { it.id == n.id || it.name.equals(n.name, ignoreCase = true) } }
                        }
                    },
                )
            }
            if (replace && identity) out.filter { p -> p.id in pidMap.values }.ifEmpty { out } else out
        }
        if (_profiles.value.none { it.id == _activeId.value }) select(_profiles.value.first().id)
        return pidMap
    }

    private fun load(): List<Profile> {
        val list = runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return list.ifEmpty { listOf(Profile(Profile.DEFAULT_ID, de.marne.streamplayer.ui.tr("Ich", "Me"), 0, 0)) }
    }

    private fun save(list: List<Profile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val COLORS = 6
        /** So lange im Hintergrund, und die App gilt beim Zurückkommen als neu gestartet. */
        const val RESTART_AFTER = 30 * 60 * 1000L
    }
}

/**
 * Gesalzener PBKDF2-Hash der PIN. Die PIN selbst wird nirgends gespeichert, nur dieser Hash – er
 * liegt im Profil und kommt so auf alle Geräte des Kontos. Form: „pbkdf2|Runden|Salz|Hash“ (Base64).
 */
object PinHash {
    private const val ROUNDS = 20_000

    fun make(pin: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return listOf("pbkdf2", ROUNDS.toString(), b64(salt), b64(derive(pin, salt, ROUNDS))).joinToString("|")
    }

    fun matches(stored: String, pin: String): Boolean {
        val p = stored.split('|')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val rounds = p[1].toIntOrNull() ?: return false
        val salt = runCatching { java.util.Base64.getDecoder().decode(p[2]) }.getOrNull() ?: return false
        val want = runCatching { java.util.Base64.getDecoder().decode(p[3]) }.getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(want, derive(pin, salt, rounds))
    }

    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, rounds, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)
}

/** Eine eigene Sammlung: Name und Titel-IDs, die zuletzt hinzugefügten vorn. */
data class TitleCollection(val id: String, val name: String, val items: List<String>, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("items", JSONArray(items)).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = TitleCollection(
            o.getString("id"), o.optString("name"),
            o.optJSONArray("items")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
            o.optLong("createdAt"),
        )
    }
}
