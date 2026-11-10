package de.marne.streamplayer.download

import androidx.media3.datasource.HttpDataSource
import androidx.media3.transformer.ExportException
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
            root.any { it is UnknownHostException } -> "Kein Netz. Prüf die Verbindung und versuch es noch einmal."
            root.any { it is SocketTimeoutException } -> "Der Server antwortet nicht. Versuch es später noch einmal."
            root.any { it is ExportException } -> "Zusammenfügen fehlgeschlagen. Wähl eine andere Qualität."
            root.any { it is FileNotFoundException } -> "Datei nicht mehr da. Lade sie neu."
            root.any { it is IOException } -> "Verbindung abgebrochen. Tipp auf Fortsetzen."
            else -> e.message ?: "Unbekannter Fehler."
        }
    }

    private fun http(code: Int): String = when (code) {
        401, 403 -> "Server lehnt ab ($code). Öffne die Seite neu und starte das Video."
        404, 410 -> "Nicht mehr verfügbar ($code). Die Adresse ist abgelaufen."
        429 -> "Zu viele Anfragen (429). Warte kurz und versuch es noch einmal."
        in 500..599 -> "Serverfehler ($code). Versuch es später noch einmal."
        else -> "Server antwortet mit $code."
    }
}
