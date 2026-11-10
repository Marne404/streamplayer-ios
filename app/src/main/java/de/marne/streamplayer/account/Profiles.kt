package de.marne.streamplayer.account

import android.content.Context
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

/** Ein Profil wie bei Netflix: gemeinsame Bibliothek, eigener Fortschritt. */
data class Profile(val id: String, val name: String, val color: Int, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Profile(o.getString("id"), o.optString("name", "Profil"), o.optInt("color"), o.optLong("createdAt"))
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

    /** In dieser Sitzung schon gewählt? Sonst fragt die App bei mehreren Profilen „Wer schaut?“. */
    var chosenThisSession = false

    init {
        scope.launch { _profiles.drop(1).debounce(300).collect { save(it) } }
    }

    val active: Profile get() = _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.first()

    fun select(id: String) {
        if (_profiles.value.none { it.id == id }) return
        _activeId.value = id
        chosenThisSession = true
        prefs.edit().putString("active", id).apply()
    }

    fun add(name: String): Profile {
        val used = _profiles.value.map { it.color }.toSet()
        val color = (0 until COLORS).firstOrNull { it !in used } ?: (_profiles.value.size % COLORS)
        val p = Profile(UUID.randomUUID().toString(), name.trim().ifEmpty { "Profil" }, color, System.currentTimeMillis())
        _profiles.update { it + p }
        return p
    }

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

    private fun load(): List<Profile> {
        val list = runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return list.ifEmpty { listOf(Profile(Profile.DEFAULT_ID, "Ich", 0, 0)) }
    }

    private fun save(list: List<Profile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val COLORS = 6
    }
}
