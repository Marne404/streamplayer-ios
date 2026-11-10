package de.marne.streamplayer.account

import android.app.Activity
import android.app.Application
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import de.marne.streamplayer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Firebase: Anmeldung und Datenbank. Ohne google-services.json (und ohne Emulator-Build) ist
 * [available] false – die App läuft dann wie bisher rein lokal.
 */
class Cloud(private val app: Application) {
    data class User(val uid: String, val email: String?, val name: String?, val google: Boolean)

    val available: Boolean = init()
    /** Für die Anzeige: läuft gegen den lokalen Emulator statt gegen das echte Projekt. */
    val emulator: Boolean get() = BuildConfig.FIREBASE_EMULATOR.isNotBlank()

    private val auth: FirebaseAuth? = if (available) FirebaseAuth.getInstance() else null
    val db: FirebaseFirestore? = if (available) FirebaseFirestore.getInstance() else null

    private val _user = MutableStateFlow(auth?.currentUser?.let(::toUser))
    val user: StateFlow<User?> = _user.asStateFlow()

    /**
     * Web-Client-ID für „Mit Google anmelden“. Erzeugt das google-services-Plugin nur, wenn Google
     * in der Konsole eingeschaltet war, *bevor* die Datei geladen wurde – deshalb zur Laufzeit gesucht.
     */
    private val webClientId: String? = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        .takeIf { it != 0 }?.let { app.getString(it) }
    val googleAvailable: Boolean get() = available && webClientId != null

    init {
        auth?.addAuthStateListener { _user.value = it.currentUser?.let(::toUser) }
    }

    private fun init(): Boolean {
        val host = BuildConfig.FIREBASE_EMULATOR
        var ready = FirebaseApp.getApps(app).isNotEmpty()
        if (!ready && host.isNotBlank()) {
            // Demo-Projekt: der Emulator braucht keine echten Schlüssel, „demo-“ verhindert jeden Zugriff aufs Netz.
            FirebaseApp.initializeApp(
                app,
                FirebaseOptions.Builder().setProjectId("demo-streamplayer").setApplicationId("1:1:android:1").setApiKey("demo-key").build(),
            )
            ready = true
        }
        if (ready && host.isNotBlank()) {
            FirebaseAuth.getInstance().useEmulator(host, 9099)
            FirebaseFirestore.getInstance().useEmulator(host, 8080)
        }
        return ready
    }

    private fun toUser(u: com.google.firebase.auth.FirebaseUser) = User(
        uid = u.uid,
        email = u.email,
        name = u.displayName,
        google = u.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
    )

    suspend fun signIn(email: String, password: String) {
        requireAuth().signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun register(email: String, password: String) {
        requireAuth().createUserWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun resetPassword(email: String) {
        requireAuth().sendPasswordResetEmail(email.trim()).await()
    }

    /** Über den Credential Manager: Kontoauswahl des Systems, kein eigenes Google-Fenster. */
    suspend fun signInWithGoogle(activity: Activity) {
        requireAuth().signInWithCredential(GoogleAuthProvider.getCredential(googleToken(activity), null)).await()
    }

    private suspend fun googleToken(activity: Activity): String {
        val clientId = webClientId ?: throw IllegalStateException(de.marne.streamplayer.ui.tr("Google-Anmeldung ist im Firebase-Projekt nicht eingeschaltet.", "Google sign-in is not enabled in the Firebase project."))
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException(de.marne.streamplayer.ui.tr("Unerwartete Antwort der Google-Anmeldung.", "Unexpected response from Google sign-in."))
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    /**
     * Bestätigen, dass hier der Kontoinhaber sitzt – für „PIN vergessen?“. E-Mail-Konten mit dem
     * Passwort, Google-Konten über die Kontoauswahl. Wirft, wenn es nicht passt.
     */
    suspend fun confirmOwner(activity: Activity, password: String?) {
        val u = requireAuth().currentUser ?: throw IllegalStateException(de.marne.streamplayer.ui.tr("Nicht angemeldet.", "Not signed in."))
        val credential = if (u.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }) {
            GoogleAuthProvider.getCredential(googleToken(activity), null)
        } else {
            com.google.firebase.auth.EmailAuthProvider.getCredential(u.email.orEmpty(), password.orEmpty())
        }
        u.reauthenticate(credential).await()
    }

    fun signOut() {
        auth?.signOut()
    }

    private fun requireAuth() = auth ?: throw IllegalStateException("Konto ist in dieser Version nicht eingerichtet.")

    companion object {
        /** Fehler so, dass man weiß, was zu tun ist. null = still (z. B. selbst abgebrochen). */
        fun message(e: Throwable): String? = when (e) {
            is GetCredentialCancellationException -> null
            is NoCredentialException -> "Kein Google-Konto auf diesem Gerät gefunden."
            is FirebaseAuthWeakPasswordException -> "Das Passwort ist zu kurz – mindestens 6 Zeichen."
            is FirebaseAuthUserCollisionException -> "Für diese E-Mail gibt es schon ein Konto. Melde dich an (oder mit Google, falls du es so angelegt hast)."
            is FirebaseAuthInvalidUserException -> "Kein Konto mit dieser E-Mail."
            is FirebaseAuthInvalidCredentialsException ->
                if (e.errorCode == "ERROR_INVALID_EMAIL") "Das sieht nicht nach einer E-Mail-Adresse aus." else "E-Mail oder Passwort stimmt nicht."
            is FirebaseTooManyRequestsException -> "Zu viele Versuche. Bitte kurz warten."
            is FirebaseNetworkException -> "Keine Verbindung."
            else -> e.message ?: "Anmeldung fehlgeschlagen."
        }
    }
}
