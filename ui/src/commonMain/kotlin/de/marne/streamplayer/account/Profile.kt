package de.marne.streamplayer.account

/**
 * Ein Profil wie bei Netflix: gemeinsame Bibliothek, eigener Fortschritt, eigene „Meine Liste“.
 * [list]: Titel-IDs, die neuesten vorn. Liegt im Profil, damit sie mit dem Profil abgeglichen wird.
 */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val createdAt: Long,
    val list: List<String> = emptyList(),
    /** Profilsperre wie bei Netflix: Hash der vierstelligen PIN ([PinHash]), null = offen. */
    val lock: String? = null,
    /** Eigene Sammlungen („Weihnachten“, „Mit den Kindern“). Gehören dem Profil wie „Meine Liste“. */
    val collections: List<TitleCollection> = emptyList(),
) {
    val locked: Boolean get() = lock != null

    companion object {
        /** Das erste Profil übernimmt den Fortschritt aus der Zeit vor den Profilen. */
        const val DEFAULT_ID = "main"
    }
}

/** Eine eigene Sammlung: Name und Titel-IDs, die zuletzt hinzugefügten vorn. */
data class TitleCollection(val id: String, val name: String, val items: List<String>, val createdAt: Long) {

    companion object
}
