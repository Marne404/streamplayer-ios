package de.marne.streamplayer.ui

import java.util.Locale

/**
 * Locale für Zahlen und Datum („7,4“ / „7.4“, „10. November“ / „November 10“). Nur Android:
 * [Lang] selbst liegt im Multiplatform-Modul :ui, java.util.Locale gibt es auf dem iPad nicht.
 */
val Lang.locale: Locale get() = if (isGerman) Locale.GERMANY else Locale.US
