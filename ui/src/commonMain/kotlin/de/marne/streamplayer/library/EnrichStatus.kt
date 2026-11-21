package de.marne.streamplayer.library

/** Stand des TMDB-Abgleichs (Enricher auf Android): läuft, wie weit, letzter Fehler. */
data class EnrichStatus(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val lastError: String? = null)
