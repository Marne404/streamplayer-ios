package de.marne.streamplayer.download

import android.os.Environment
import android.os.StatFs

/** Freier und gesamter Platz des geteilten Speichers, in dem „Filme/Streamplayer“ liegt. */
fun Offline.storage(): Pair<Long, Long>? = runCatching {
    val s = StatFs(Environment.getExternalStorageDirectory().path)
    s.availableBytes to s.totalBytes
}.getOrNull()
