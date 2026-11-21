package de.marne.streamplayer.detect

// Reine Typen aus der Erkennung, die auch Bibliothek und Downloads brauchen (Android + iPad).

enum class MediaKind { FILE, HLS, DASH }

/** Spur-Auswahl in einer HLS/DASH-Playlist (Periode, Gruppe, Spur). */
data class Key(val period: Int, val group: Int, val stream: Int)
