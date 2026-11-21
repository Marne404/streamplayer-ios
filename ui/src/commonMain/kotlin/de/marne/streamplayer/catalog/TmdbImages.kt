package de.marne.streamplayer.catalog

/** Adressen und Größen der TMDB-Bilder – ohne Netzwerk, gemeinsam für Android und iPad. */
object TmdbImages {
    private const val IMG = "https://image.tmdb.org/t/p"

    // Poster w780: reicht auch, wenn ein Poster ohne Breitbild über die volle Breite gezogen wird.
    fun poster(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w780$it" }
    fun backdrop(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w1280$it" }
    // Standbilder klein (Folgenliste, viele auf einmal); groß gezeigt über [large].
    fun still(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMG/w300$it" }

    /**
     * TMDB-Bild in der Größe für die volle Bildschirmbreite. Erkennt die Art an der gespeicherten
     * Größe: w300 = Standbild (TMDB hat dafür nur w92/w185/w300/original), w500 = Poster aus
     * älteren Ständen. Andere Adressen bleiben, wie sie sind.
     */
    fun large(url: String?): String? {
        if (url == null || !url.startsWith(IMG)) return url
        return url.replace("/w300/", "/original/").replace("/w500/", "/w780/").replace("/w342/", "/w780/")
    }

    /** Für Bild-Modelle, die auch Uri oder Datei sein können (Coil): nur Text wird umgeschrieben. */
    fun largeModel(model: Any?): Any? = if (model is String) large(model) else model
}
