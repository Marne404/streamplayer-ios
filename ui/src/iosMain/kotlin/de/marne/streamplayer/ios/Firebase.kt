package de.marne.streamplayer.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import de.marne.streamplayer.ui.nowMillis

/** Fehler eines Google-/Firebase-Dienstes mit lesbarer Meldung. */
internal class CloudException(message: String, val status: Int = 0) : Exception(message)

internal val http = HttpClient(Darwin) { expectSuccess = false }
internal val json = Json { ignoreUnknownKeys = true }

/** Jede Anfrage an googleapis.com: Der Schlüssel ist auf die iOS-App beschränkt. */
private fun io.ktor.client.request.HttpRequestBuilder.iosKey() = header("X-Ios-Bundle-Identifier", Config.REGISTERED_BUNDLE_ID)

private suspend fun HttpResponse.jsonOrThrow(what: String): JsonObject {
    val text = bodyAsText()
    if (!status.isSuccess()) {
        val msg = runCatching {
            val e = json.parseToJsonElement(text) as JsonObject
            ((e["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: (e["error_description"] as? JsonPrimitive)?.content
        }.getOrNull()
        throw CloudException("$what: ${msg ?: status.value}", status.value)
    }
    return json.parseToJsonElement(text) as JsonObject
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

/**
 * Firebase-Anmeldung über die REST-Schnittstelle (wie das Firebase-SDK es intern tut): Google-Code
 * → ID-Token → Firebase-Sitzung. Der Erneuerungsschlüssel liegt im Schlüsselbund; das kurzlebige
 * ID-Token (1 h) wird bei Bedarf neu geholt.
 */
internal class FirebaseAuth {
    data class User(val uid: String, val email: String?, val name: String?)

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private var idToken: String? = null
    private var expiresAt = 0L
    private val lock = Mutex()

    private val key get() = Config.firebaseApiKey ?: throw CloudException("Konto ist in dieser Version nicht eingerichtet")

    /** Gespeicherte Anmeldung übernehmen (ohne Netz). Das Token holt [token] beim ersten Zugriff. */
    fun restore(): Boolean {
        val uid = Keychain.get("uid") ?: return false
        if (Keychain.get("refresh") == null) return false
        _user.value = User(uid, Keychain.get("email"), Keychain.get("name"))
        return true
    }

    suspend fun signInWithGoogle() {
        val clientId = Config.googleClientId ?: throw CloudException("Google-Anmeldung ist nicht eingerichtet")
        val code = GoogleSignIn.authorize(clientId, Config.googleRedirectScheme!!)
        val google = http.submitForm(
            "https://oauth2.googleapis.com/token",
            parameters {
                append("client_id", clientId)
                append("code", code.code)
                append("code_verifier", code.verifier)
                append("grant_type", "authorization_code")
                append("redirect_uri", code.redirectUri)
            },
        ).jsonOrThrow("Google")
        val googleId = google.s("id_token") ?: throw CloudException("Google hat kein ID-Token geliefert")
        val fb = http.post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp") {
            parameter("key", key)
            iosKey()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("postBody", "id_token=$googleId&providerId=google.com")
                put("requestUri", "http://localhost")
                put("returnIdpCredential", true)
                put("returnSecureToken", true)
            }.toString())
        }.jsonOrThrow("Firebase")
        val uid = fb.s("localId") ?: throw CloudException("Firebase hat kein Konto geliefert")
        lock.withLock {
            idToken = fb.s("idToken")
            expiresAt = nowMillis() + ((fb.s("expiresIn")?.toLongOrNull() ?: 3600) - 120) * 1000
        }
        Keychain.set("refresh", fb.s("refreshToken"))
        Keychain.set("uid", uid)
        Keychain.set("email", fb.s("email"))
        Keychain.set("name", fb.s("displayName"))
        _user.value = User(uid, fb.s("email"), fb.s("displayName"))
    }

    fun signOut() {
        listOf("refresh", "uid", "email", "name").forEach { Keychain.set(it, null) }
        idToken = null
        expiresAt = 0
        _user.value = null
    }

    /** Gültiges ID-Token für Firestore; erneuert es mit dem gespeicherten Schlüssel. */
    suspend fun token(): String = lock.withLock {
        idToken?.takeIf { nowMillis() < expiresAt }?.let { return@withLock it }
        val refresh = Keychain.get("refresh") ?: throw CloudException("Nicht angemeldet", 401)
        val r = http.submitForm(
            "https://securetoken.googleapis.com/v1/token",
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refresh)
            },
        ) {
            parameter("key", key)
            iosKey()
        }.jsonOrThrow("Anmeldung")
        r.s("refresh_token")?.let { Keychain.set("refresh", it) }
        idToken = r.s("id_token")
        expiresAt = nowMillis() + ((r.s("expires_in")?.toLongOrNull() ?: 3600) - 120) * 1000
        idToken ?: throw CloudException("Kein Token")
    }
}

/** Liest Dokumente der Form { json, updatedAt, deleted }, wie CloudSync auf Android sie schreibt. */
internal class Firestore(private val auth: FirebaseAuth) {
    data class Doc(val id: String, val json: String?, val deleted: Boolean)

    private val base = "https://firestore.googleapis.com/v1/projects/${Config.FIREBASE_PROJECT}/databases/(default)/documents"

    /** Alle Dokumente einer Sammlung (seitenweise). */
    suspend fun list(path: String): List<Doc> {
        val out = mutableListOf<Doc>()
        var page: String? = null
        do {
            val r = http.get("$base/$path") {
                parameter("pageSize", 300)
                page?.let { parameter("pageToken", it) }
                header("Authorization", "Bearer ${auth.token()}")
                iosKey()
            }.jsonOrThrow("Konto")
            (r["documents"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { d ->
                val f = d["fields"] as? JsonObject
                val id = d.s("name")!!.substringAfterLast('/')
                val jsonText = ((f?.get("json") as? JsonObject)?.get("stringValue") as? JsonPrimitive)?.content
                val deleted = ((f?.get("deleted") as? JsonObject)?.get("booleanValue") as? JsonPrimitive)?.content == "true"
                out += Doc(id, jsonText?.takeIf { it.isNotEmpty() }, deleted || jsonText.isNullOrEmpty())
            }
            page = r.s("nextPageToken")
        } while (page != null)
        return out
    }

    /** Eigenes Bild (covers/{name}): Bytes oder null. */
    suspend fun cover(uidPath: String, name: String): ByteArray? {
        val r = http.get("$base/$uidPath/covers/$name") {
            header("Authorization", "Bearer ${auth.token()}")
            iosKey()
        }
        if (!r.status.isSuccess()) return null
        val d = json.parseToJsonElement(r.bodyAsText()) as JsonObject
        val b64 = (((d["fields"] as? JsonObject)?.get("data") as? JsonObject)?.get("bytesValue"))?.jsonPrimitive?.content ?: return null
        return runCatching { kotlin.io.encoding.Base64.Default.decode(b64) }.getOrNull()
    }
}
