package de.marne.streamplayer.subtitles

/**
 * Eine Untertitelspur einer Seite oder Datei. Nur die Daten – Format, Anzeigename, JSON und die
 * Übergabe an den Player stehen auf Android als Erweiterungen in Subtitles.kt.
 */
data class SubTrack(val url: String, val lang: String? = null, val label: String? = null, val referer: String? = null) {
    companion object
}
