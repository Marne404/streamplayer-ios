package de.marne.streamplayer.admin

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ApiException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * Der Zugang zum Statistik-Worker (POST /admin, stats/src/admin.js). Jede Anfrage trägt den
 * Admin-Schlüssel; lehnt der Server ihn ab (401), zeigt die App wieder die Anmeldung.
 *
 * Der Schlüssel kommt aus BuildConfig (local.properties beim Bauen) oder, wenn dort keiner war oder
 * er nicht mehr gilt, aus der Eingabe auf dem Anmeldebildschirm (SharedPreferences).
 */
object Api {
    private const val KEY = "token"
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private lateinit var prefs: SharedPreferences

    /** Leer = keine Anmeldung, die App zeigt den Anmeldebildschirm. Compose-Zustand. */
    var token by mutableStateOf("")
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("admin", Context.MODE_PRIVATE)
        // Ein gespeicherter Wert gewinnt, auch ein leerer: nach „Abmelden“ soll der eingebaute
        // Schlüssel nicht still wieder auftauchen.
        token = if (prefs.contains(KEY)) prefs.getString(KEY, "").orEmpty() else BuildConfig.ADMIN_TOKEN
    }

    fun signIn(key: String) {
        token = key.trim()
        prefs.edit().putString(KEY, token).apply()
    }

    fun signOut() {
        token = ""
        prefs.edit().putString(KEY, "").apply()
    }

    /** Prüft einen Schlüssel, bevor er gespeichert wird. null = passt, sonst der Grund. */
    suspend fun verify(key: String): String? = try {
        post(key.trim(), JSONObject().put("op", "ping"))
        null
    } catch (e: ApiException) {
        if (e.unauthorized) "Dieser Schlüssel ist falsch" else e.message
    }

    /**
     * Ruft [op] auf. „Heute“ schickt die App mit: Die Tage in der Datenbank sind Ortszeit der
     * Player-App, und kurz nach Mitternacht wäre der UTC-Tag des Servers noch gestern.
     */
    suspend fun call(op: String, vararg params: Pair<String, Any?>): JSONObject {
        val body = JSONObject().put("op", op).put("today", LocalDate.now().toString())
        params.forEach { (k, v) -> body.put(k, v) }
        val o = try {
            post(token, body)
        } catch (e: ApiException) {
            if (e.unauthorized) signOut()
            throw e
        }
        if (!o.optBoolean("ok")) throw ApiException(o.optString("reason").ifEmpty { "Fehlgeschlagen" })
        return o
    }

    private suspend fun post(key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.STATS_URL}/admin")
            .header("authorization", "Admin $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (code, text) = try {
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw ApiException("Keine Verbindung zum Server")
        }
        when (code) {
            401 -> throw ApiException("Der Schlüssel wurde abgelehnt", unauthorized = true)
            404 -> throw ApiException("Der Server hat keinen Admin-Zugang eingerichtet")
        }
        runCatching { JSONObject(text) }.getOrNull() ?: throw ApiException("Unerwartete Antwort (HTTP $code)")
    }
}
