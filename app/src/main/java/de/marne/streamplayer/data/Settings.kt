package de.marne.streamplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.marne.streamplayer.ui.Lang
import org.json.JSONArray
import org.json.JSONObject

data class Visit(val url: String, val title: String)

/** Einstellungen und Verlauf. SharedPreferences reichen: wenige Werte, kein Abfragebedarf. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(prefs.getString(KEY_THEME, "system") ?: "system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _parallel = MutableStateFlow(prefs.getInt(KEY_PARALLEL, 2).coerceIn(1, 3))
    val parallel: StateFlow<Int> = _parallel.asStateFlow()

    /** Browser-Modus: Links nicht anzeigen, sondern direkt im eigenen Player abspielen. */
    private val _watchMode = MutableStateFlow(prefs.getBoolean(KEY_WATCH, false))
    val watchMode: StateFlow<Boolean> = _watchMode.asStateFlow()

    fun setWatchMode(value: Boolean) {
        _watchMode.value = value
        prefs.edit().putBoolean(KEY_WATCH, value).apply()
    }

    // TMDB: Schlüssel (v3-API-Key oder v4-Token), Sprache, automatische Erkennung beim Import.
    private val _tmdbKey = MutableStateFlow(prefs.getString(KEY_TMDB, "") ?: "")
    val tmdbKey: StateFlow<String> = _tmdbKey.asStateFlow()
    private val _tmdbAuto = MutableStateFlow(prefs.getBoolean(KEY_TMDB_AUTO, true))
    val tmdbAuto: StateFlow<Boolean> = _tmdbAuto.asStateFlow()
    /**
     * TMDB-Sprache: von Hand gesetzt, sonst die der Oberfläche – Deutsch als de-DE, Englisch in der
     * Variante des Geräts (en-GB, en-AU …), sonst en-US. Fehlt ein Bild in dieser Sprache, nimmt
     * Tmdb das englische oder sprachlose (siehe Tmdb.pickImage).
     */
    val tmdbLanguage: String get() = prefs.getString(KEY_TMDB_LANG, null) ?: when (_language.value) {
        Lang.Code.DE -> "de-DE"
        Lang.Code.EN -> java.util.Locale.getDefault().takeIf { it.language == "en" && it.country.isNotEmpty() }?.toLanguageTag() ?: "en-US"
    }

    /**
     * Sprache der Oberfläche. Fehlt sie, kommt es darauf an: Wer den Willkommens-Ablauf schon hatte
     * (vor 1.8 war alles Deutsch), bekommt die Gerätesprache, im Zweifel Deutsch. Neu Installierte
     * starten Englisch und wählen im ersten Schritt des Ablaufs.
     */
    private val _language = MutableStateFlow(
        Lang.fromTag(prefs.getString(KEY_LANGUAGE, null))
            ?: if (prefs.getBoolean("onboarded", false)) (Lang.system() ?: Lang.Code.DE) else Lang.Code.EN
    )
    val language: StateFlow<Lang.Code> = _language.asStateFlow()

    fun setLanguage(code: Lang.Code) {
        _language.value = code
        Lang.current = code
        prefs.edit().putString(KEY_LANGUAGE, code.tag).apply()
    }

    fun setTmdbKey(value: String) {
        _tmdbKey.value = value.trim()
        prefs.edit().putString(KEY_TMDB, value.trim()).apply()
    }

    fun setTmdbAuto(value: Boolean) {
        _tmdbAuto.value = value
        prefs.edit().putBoolean(KEY_TMDB_AUTO, value).apply()
    }

    private val _adblock = MutableStateFlow(prefs.getBoolean(KEY_ADBLOCK, true))
    val adblock: StateFlow<Boolean> = _adblock.asStateFlow()

    /** Seiten, auf denen der Werbeblocker aus ist (Host, gilt auch für Subdomains). */
    private val _allowlist = MutableStateFlow(prefs.getStringSet(KEY_ALLOWLIST, emptySet())!!.toSet())
    val allowlist: StateFlow<Set<String>> = _allowlist.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<Visit>> = _history.asStateFlow()

    var lastUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    /** Willkommens-Ablauf gesehen. Fehlt bei allen, die ihn noch nie hatten – auch nach einem Update. */
    private val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded", false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        _onboarded.value = value
        prefs.edit().putBoolean("onboarded", value).apply()
    }

    /** Wie bei Netflix: bei jedem Start „Wer schaut?“ – sofern es mehr als ein Profil gibt. */
    private val _askProfile = MutableStateFlow(prefs.getBoolean("ask_profile", true))
    val askProfile: StateFlow<Boolean> = _askProfile.asStateFlow()

    fun setAskProfile(value: Boolean) {
        _askProfile.value = value
        prefs.edit().putBoolean("ask_profile", value).apply()
    }

    fun setTheme(value: String) {
        _theme.value = value
        prefs.edit().putString(KEY_THEME, value).apply()
    }

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        prefs.edit().putBoolean(KEY_WIFI, value).apply()
    }

    fun setAdblock(value: Boolean) {
        _adblock.value = value
        prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()
    }

    fun setAllowed(host: String, allowed: Boolean) {
        val h = host.removePrefix("www.")
        val next = if (allowed) _allowlist.value + h else _allowlist.value - h
        _allowlist.value = next
        prefs.edit().putStringSet(KEY_ALLOWLIST, next).apply()
    }

    fun setParallel(value: Int) {
        val v = value.coerceIn(1, 3)
        _parallel.value = v
        prefs.edit().putInt(KEY_PARALLEL, v).apply()
    }

    /** Merkt sich die letzten Seiten, je Adresse einmal, die neueste oben. */
    fun recordVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        val list = listOf(Visit(url, title.ifBlank { url })) + _history.value.filter { it.url != url }
        val trimmed = list.take(MAX_HISTORY)
        _history.value = trimmed
        val arr = JSONArray()
        trimmed.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).remove(KEY_LAST_URL).apply()
    }

    private fun readHistory(): List<Visit> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { Visit(it.getString("u"), it.optString("t")) } }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_WIFI = "wifi_only"
        const val KEY_PARALLEL = "parallel"
        const val KEY_ADBLOCK = "adblock"
        const val KEY_WATCH = "watch_mode"
        const val KEY_TMDB = "tmdb_key"
        const val KEY_TMDB_AUTO = "tmdb_auto"
        const val KEY_TMDB_LANG = "tmdb_language"
        const val KEY_LANGUAGE = "ui_language"
        const val KEY_ALLOWLIST = "adblock_allowlist"
        const val KEY_HISTORY = "history"
        const val KEY_LAST_URL = "last_url"
        const val MAX_HISTORY = 12
    }
}
