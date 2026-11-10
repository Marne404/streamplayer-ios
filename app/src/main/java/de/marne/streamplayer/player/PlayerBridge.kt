package de.marne.streamplayer.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Weg vom Player zurück in die Bibliothek: „diese Folge als Nächstes“. Der Player kann eine Seite
 * nicht selbst auflösen – das kann nur MainActivity mit ihrer WebView. Also legt er den Wunsch hier
 * ab und schließt sich; MainActivity holt ihn ab ([take]) und startet Ladebildschirm und Player neu.
 *
 * Ein StateFlow statt eines Ereignisses: MainActivity ist währenddessen gestoppt, ein verpasstes
 * Ereignis wäre weg. Der Wunsch bleibt liegen, bis ihn jemand nimmt.
 */
object PlayerBridge {
    data class Request(val titleId: String, val season: Int, val episode: Int)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun playEpisode(titleId: String, season: Int, episode: Int) {
        _pending.value = Request(titleId, season, episode)
    }

    fun take(): Request? = _pending.value.also { _pending.value = null }
}
