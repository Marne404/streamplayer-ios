package de.marne.streamplayer.ios

import platform.Foundation.NSBundle

/**
 * Zugangsdaten fürs Konto. Kommen beim Bauen aus Geheimnissen des Build-Repos (GitHub Actions)
 * in die Info.plist – im Quelltext stehen sie nicht. Fehlen sie, gibt es nur die Beispieltitel.
 */
object Config {
    private fun info(key: String): String? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$(") }

    /** iOS-Client der Google-Anmeldung (GoogleService-Info.plist: CLIENT_ID). */
    val googleClientId: String? = info("SPGoogleClientId")

    /** Firebase-Schlüssel des iOS-Eintrags (GoogleService-Info.plist: API_KEY). */
    val firebaseApiKey: String? = info("SPFirebaseApiKey")

    const val FIREBASE_PROJECT = "streamplayer-eba4c"

    /**
     * Paket-ID, wie sie in Firebase eingetragen ist. Der Schlüssel ist auf sie beschränkt; SideStore
     * hängt beim Installieren die Team-ID an, darum fest statt aus NSBundle.
     */
    const val REGISTERED_BUNDLE_ID = "de.marne.streamplayer.ipad"

    val accountAvailable: Boolean get() = googleClientId != null && firebaseApiKey != null

    /** „123-abc.apps.googleusercontent.com“ → „com.googleusercontent.apps.123-abc“ (Rücksprung-Schema). */
    val googleRedirectScheme: String? get() = googleClientId?.split('.')?.reversed()?.joinToString(".")
}
