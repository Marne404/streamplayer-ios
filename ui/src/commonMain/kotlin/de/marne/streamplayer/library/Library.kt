package de.marne.streamplayer.library

import de.marne.streamplayer.ui.tr
import de.marne.streamplayer.ui.nowMillis

/**
 * Eine Bibliothek: eigene Welt mit eigener Startseite, eigenen Regalen und eigenem Weiterschauen
 * (z. B. „Anime“, „Serien“). Jeder Titel steht in genau einer ([LibTitle.libraryId]).
 * [MAIN] gibt es immer – dort landet alles von früher, sie lässt sich umbenennen, nicht löschen.
 */
data class Library(
    val id: String,
    /** Leer bei [MAIN]: dann heißt sie in der eingestellten Sprache „Meine Bibliothek“. */
    val name: String,
    /** Farbe als Index in die Profilfarben (ProfileColors) – markiert die Bibliothek im Wechsler. */
    val color: Int = 0,
    val createdAt: Long = nowMillis(),
    /** Eigene Bibliothek, die geteilt ist: ihre Titel liegen gespiegelt unter shared/{id}. */
    val shared: Boolean = false,
    /** Gültiger Einladungscode (nur der Besitzer kennt ihn). */
    val invite: String? = null,
    /** Bibliothek eines Freundes, der man beigetreten ist: nur ansehen, [owner] ist sein Name. */
    val joined: Boolean = false,
    val owner: String? = null,
) {
    /** Titel hier dürfen nicht bearbeitet werden (sie gehören einem Freund). */
    val readOnly: Boolean get() = joined

    val label: String get() = name.ifBlank { tr("Meine Bibliothek", "My Library") }

    companion object {
        const val MAIN = "main"
        /** Auswahl „Alle“: kein Filter. Keine echte Bibliothek. */
        const val ALL = "*"
    }
}
