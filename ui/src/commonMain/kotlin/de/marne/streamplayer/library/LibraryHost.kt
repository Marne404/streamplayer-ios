package de.marne.streamplayer.library

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * Was die Bibliotheks-Seiten von der Plattform brauchen, aber nicht über [LibState]/[LibActions]
 * bekommen: Einstellungen der Startseite, Sehzeit je Tag, TMDB-Abgleich und Sammlungen.
 * Android: AppLibraryHost (über App), iPad: eigene Umsetzung.
 */
interface LibraryHost {
    /** TMDB ist eingerichtet (Schlüssel da) – Zuordnen und Neuladen sind möglich. */
    val tmdbAvailable: Boolean

    /** Diesen Titel beim TMDB-Abgleich vorziehen (Detailseite geöffnet). */
    fun prioritizeEnrich(titleId: String)

    /** Sehzeit je Tag (Tage seit 1970 → Millisekunden) des aktiven Profils. */
    val watchDays: StateFlow<Map<Long, Long>>

    /** Startseite: Regal „Weiterschauen“ zeigen. */
    val homeContinue: StateFlow<Boolean>

    /** Startseite: Regal „Meine Liste“ zeigen. */
    val homeMyList: StateFlow<Boolean>

    fun createCollection(name: String, firstTitle: String? = null): String
    fun renameCollection(id: String, name: String)
    fun deleteCollection(id: String)
    fun toggleInCollection(id: String, titleId: String)
}

val LocalLibraryHost = staticCompositionLocalOf<LibraryHost> { error("Kein LibraryHost gesetzt (CompositionLocalProvider fehlt).") }
