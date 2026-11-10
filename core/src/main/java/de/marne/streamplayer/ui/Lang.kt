package de.marne.streamplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Sprache der Oberfläche: Englisch oder Deutsch. Bei zwei Sprachen stehen beide Texte direkt
 * nebeneinander im Code ([tr]) statt in strings.xml – so gibt es keinen Text ohne Gegenstück, und
 * es geht auch außerhalb von Compose (Meldungen, Benachrichtigungen, Fehlertexte).
 *
 * [current] ist Compose-Zustand: wer in einer Composable [tr] aufruft, wird beim Umschalten neu
 * gezeichnet – ohne Neustart der Activity.
 *
 * Der Player setzt die Sprache aus seinen Einstellungen (im Willkommens-Ablauf zuerst Englisch,
 * dann die gewählte), der Creator ist immer Englisch.
 */
object Lang {
    enum class Code(val tag: String) { EN("en"), DE("de") }

    var current by mutableStateOf(Code.EN)

    val isGerman: Boolean get() = current == Code.DE

    /** Locale für Zahlen und Datum („7,4“ / „7.4“, „10. November“ / „November 10“). */
    val locale: Locale get() = if (isGerman) Locale.GERMANY else Locale.US

    /** Sprache des Geräts, sofern es eine der beiden ist – sonst null. */
    fun system(): Code? = when (Locale.getDefault().language) {
        "de" -> Code.DE
        "en" -> Code.EN
        else -> null
    }

    fun fromTag(tag: String?): Code? = Code.entries.firstOrNull { it.tag == tag }
}

/** Text in der aktuellen Sprache: `tr("Abspielen", "Play")`. */
fun tr(de: String, en: String): String = if (Lang.current == Lang.Code.DE) de else en
