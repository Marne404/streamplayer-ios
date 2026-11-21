package de.marne.streamplayer.ui

import de.marne.streamplayer.detect.Candidate

// Format liegt im gemeinsamen Modul :ui; Funde der Erkennung gibt es nur auf Android.

/** Metazeile eines Fundes: „HLS · 12:31 · bis 1080p“ */
fun Format.candidateMeta(c: Candidate): String = buildList {
    add(kind(c.kind, c.mime))
    c.durationMs?.let { add(duration(it)) }
    c.sizeBytes?.let { add(bytes(it)) }
    c.variants.firstOrNull()?.label?.takeIf { it != "Original" && c.variants.size > 1 }?.let { add(tr("bis $it", "up to $it")) }
}.joinToString(" · ")
