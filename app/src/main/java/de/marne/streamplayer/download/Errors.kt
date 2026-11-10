package de.marne.streamplayer.download

import androidx.media3.datasource.HttpDataSource
import androidx.media3.transformer.ExportException
import de.marne.streamplayer.ui.tr
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Fehler in einer Zeile: was los ist und was zu tun ist. */
object Errors {
    fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.toList()
        root.firstNotNullOfOrNull { it as? HttpStatusException }?.let { return http(it.code) }
        root.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { return http(it.responseCode) }
        return when {
            root.any { it is UnknownHostException } -> tr("Kein Netz. Prüf die Verbindung und versuch es noch einmal.", "No connection. Check your network and try again.")
            root.any { it is SocketTimeoutException } -> tr("Der Server antwortet nicht. Versuch es später noch einmal.", "The server isn't responding. Try again later.")
            root.any { it is ExportException } -> tr("Zusammenfügen fehlgeschlagen. Wähl eine andere Qualität.", "Merging failed. Pick a different quality.")
            root.any { it is FileNotFoundException } -> tr("Datei nicht mehr da. Lade sie neu.", "File is gone. Download it again.")
            root.any { it is IOException } -> tr("Verbindung abgebrochen. Tipp auf Fortsetzen.", "Connection lost. Tap Resume.")
            else -> e.message ?: tr("Unbekannter Fehler.", "Unknown error.")
        }
    }

    private fun http(code: Int): String = when (code) {
        401, 403 -> tr("Server lehnt ab ($code). Öffne die Seite neu und starte das Video.", "Server refused ($code). Reopen the page and start the video.")
        404, 410 -> tr("Nicht mehr verfügbar ($code). Die Adresse ist abgelaufen.", "No longer available ($code). The link has expired.")
        429 -> tr("Zu viele Anfragen (429). Warte kurz und versuch es noch einmal.", "Too many requests (429). Wait a moment and try again.")
        in 500..599 -> tr("Serverfehler ($code). Versuch es später noch einmal.", "Server error ($code). Try again later.")
        else -> tr("Server antwortet mit $code.", "Server responded with $code.")
    }
}
