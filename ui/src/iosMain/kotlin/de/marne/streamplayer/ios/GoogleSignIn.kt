package de.marne.streamplayer.ios

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google-Anmeldung ohne Google-SDK: das iOS-Anmeldefenster (ASWebAuthenticationSession, teilt die
 * Anmeldung mit Safari) mit OAuth 2 + PKCE. Ergebnis ist der Autorisierungscode; den tauscht
 * [FirebaseAuth] gegen die Firebase-Anmeldung.
 */
internal object GoogleSignIn {
    class Cancelled : Exception("Anmeldung abgebrochen")

    data class Code(val code: String, val verifier: String, val redirectUri: String)

    private var running: ASWebAuthenticationSession? = null

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow =
            keyWindow() ?: UIWindow()
    }

    private fun keyWindow(): UIWindow? {
        val windows = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
        return windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull()
    }

    suspend fun authorize(clientId: String, scheme: String): Code {
        val verifier = Crypto.base64Url(Crypto.randomBytes(48))
        val challenge = Crypto.base64Url(Crypto.sha256(verifier.encodeToByteArray()))
        val redirect = "$scheme:/oauth2redirect"
        val url = NSURLComponents("https://accounts.google.com/o/oauth2/v2/auth").apply {
            queryItems = listOf(
                NSURLQueryItem("client_id", clientId),
                NSURLQueryItem("redirect_uri", redirect),
                NSURLQueryItem("response_type", "code"),
                NSURLQueryItem("scope", "openid email profile"),
                NSURLQueryItem("code_challenge", challenge),
                NSURLQueryItem("code_challenge_method", "S256"),
                NSURLQueryItem("prompt", "select_account"),
            )
        }.URL ?: error("Ungültige Anmeldeadresse")

        val back: NSURL = suspendCancellableCoroutine { cont ->
            val session = ASWebAuthenticationSession(uRL = url, callbackURLScheme = scheme) { callback, error ->
                running = null
                when {
                    callback != null -> cont.resume(callback)
                    error?.code == 1L -> cont.resumeWithException(Cancelled()) // ASWebAuthenticationSessionErrorCodeCanceledLogin
                    else -> cont.resumeWithException(Exception(error?.localizedDescription ?: "Anmeldung fehlgeschlagen"))
                }
            }
            session.presentationContextProvider = anchor
            session.prefersEphemeralWebBrowserSession = false
            running = session
            cont.invokeOnCancellation { session.cancel() }
            if (!session.start()) {
                running = null
                cont.resumeWithException(Exception("Anmeldefenster ließ sich nicht öffnen"))
            }
        }
        val items = NSURLComponents(back, resolvingAgainstBaseURL = false).queryItems.orEmpty().filterIsInstance<NSURLQueryItem>()
        items.firstOrNull { it.name == "error" }?.value?.let { throw Exception("Google: $it") }
        val code = items.firstOrNull { it.name == "code" }?.value ?: throw Exception("Google hat keinen Code geliefert")
        return Code(code, verifier, redirect)
    }
}
