package de.marne.streamplayer.detect

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import de.marne.streamplayer.net.Http
import de.marne.streamplayer.net.Http.headers
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Prüft eine gefundene Adresse, bevor sie in der Liste erscheint: Ist sie erreichbar,
 * ist es wirklich ein Video, wie groß, welche Qualitäten, geschützt oder live?
 */
class Prober(private val http: OkHttpClient, private val userAgent: () -> String) {

    suspend fun probe(c: Candidate): Candidate = withContext(Dispatchers.IO) {
        runCatching {
            when (c.kind) {
                MediaKind.FILE -> probeFile(c)
                MediaKind.HLS -> probeHls(c)
                MediaKind.DASH -> probeDash(c)
            }
        }.getOrElse { c.copy(status = ProbeStatus.FAILED) }
    }

    private fun request(url: String, referer: String?, range: String? = null): Request =
        Request.Builder()
            .url(url)
            .headers(Http.headersFor(url, referer, userAgent()))
            .apply { if (range != null) header("Range", range) }
            .build()

    // ---------- Einzeldatei ----------

    private fun probeFile(c: Candidate): Candidate {
        val referer = c.referer ?: c.pageUrl
        // Ein Byte statt HEAD: viele Server beantworten HEAD falsch oder gar nicht.
        http.newCall(request(c.url, referer, "bytes=0-0")).execute().use { r ->
            if (!r.isSuccessful) return c.copy(status = ProbeStatus.FAILED)
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            when (UrlClassifier.classify(c.url, mime)) {
                MediaKind.HLS -> return probeHls(c.copy(kind = MediaKind.HLS))
                MediaKind.DASH -> return probeDash(c.copy(kind = MediaKind.DASH))
                else -> Unit
            }
            if (mime != null && isNotMedia(mime)) return c.copy(status = ProbeStatus.FAILED)
            val total = if (r.code == 206) {
                r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
            } else {
                r.body?.contentLength()?.takeIf { it > 0 }
            }
            if (total != null && total < MIN_FILE_BYTES) return c.copy(status = ProbeStatus.FAILED)
            val finalMime = mime?.takeIf { it.startsWith("video/") || it.startsWith("audio/") } ?: c.mime
            return c.copy(status = ProbeStatus.OK, mime = finalMime, sizeBytes = total)
        }
    }

    private fun isNotMedia(mime: String): Boolean =
        mime.startsWith("text/") || mime.startsWith("image/") || mime.contains("json") ||
            mime.contains("javascript") || mime == "video/mp2t"

    // ---------- HLS ----------

    private fun probeHls(c: Candidate): Candidate {
        val referer = c.referer ?: c.pageUrl
        val (finalUrl, bytes) = fetch(c.url, referer)
        if (!String(bytes, 0, minOf(bytes.size, 64)).trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")) {
            return c.copy(status = ProbeStatus.FAILED)
        }
        return when (val playlist = HlsPlaylistParser().parse(Uri.parse(finalUrl), bytes.inputStream())) {
            is HlsMultivariantPlaylist -> fromMultivariant(c, playlist, referer)
            is HlsMediaPlaylist -> fromMediaPlaylist(c, playlist)
            else -> c.copy(status = ProbeStatus.FAILED)
        }
    }

    private fun fromMultivariant(c: Candidate, mv: HlsMultivariantPlaylist, referer: String): Candidate {
        if (mv.variants.isEmpty()) return c.copy(status = ProbeStatus.FAILED)

        // Die beste Variante einmal laden: Dauer, Live oder nicht, Verschlüsselung.
        val best = mv.variants.maxBy { it.format.bitrate }
        val media = runCatching {
            val (u, b) = fetch(best.url.toString(), referer)
            HlsPlaylistParser(mv, null).parse(Uri.parse(u), b.inputStream()) as? HlsMediaPlaylist
        }.getOrNull()

        val live = media != null && !media.hasEndTag
        val durationMs = media?.takeIf { it.hasEndTag }?.durationUs?.div(1000)
        val drm = mv.sessionKeyDrmInitData.isNotEmpty() || media?.protectionSchemes != null

        // Je Auflösung nur die Variante mit der höchsten Bitrate: ruhigere Auswahl.
        val variants = mv.variants.withIndex()
            .groupBy { it.value.format.height }
            .map { (_, same) -> same.maxBy { it.value.format.bitrate } }
            .sortedWith(compareByDescending<IndexedValue<HlsMultivariantPlaylist.Variant>> { it.value.format.height }
                .thenByDescending { it.value.format.bitrate })
            .map { (index, v) ->
                val keys = buildList {
                    add(Key(0, HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, index))
                    audioRenditionFor(mv, v.audioGroupId)?.let { add(Key(0, HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it)) }
                }
                val bitrate = v.format.bitrate.takeIf { it > 0 }
                Variant(
                    label = label(v.format),
                    height = v.format.height.takeIf { it > 0 },
                    bitrate = bitrate,
                    keys = keys,
                    estimatedBytes = estimate(bitrate, durationMs),
                )
            }

        return c.copy(
            status = ProbeStatus.OK,
            mime = MimeTypes.APPLICATION_M3U8,
            durationMs = durationMs,
            variants = variants,
            drm = drm,
            live = live,
            children = mv.mediaPlaylistUrls.map { it.toString() }.toSet(),
        )
    }

    private fun fromMediaPlaylist(c: Candidate, media: HlsMediaPlaylist): Candidate = c.copy(
        status = ProbeStatus.OK,
        mime = MimeTypes.APPLICATION_M3U8,
        durationMs = media.takeIf { it.hasEndTag }?.durationUs?.div(1000),
        variants = listOf(Variant("Original", null, null, emptyList(), null)),
        drm = media.protectionSchemes != null,
        live = !media.hasEndTag,
    )

    /** Getrennte Tonspur der Gruppe: die Standardspur, sonst die erste. */
    private fun audioRenditionFor(mv: HlsMultivariantPlaylist, groupId: String?): Int? {
        if (groupId == null) return null
        val inGroup = mv.audios.withIndex().filter { it.value.groupId == groupId && it.value.url != null }
        if (inGroup.isEmpty()) return null
        return (inGroup.firstOrNull { it.value.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 } ?: inGroup.first()).index
    }

    // ---------- DASH ----------

    private fun probeDash(c: Candidate): Candidate {
        val referer = c.referer ?: c.pageUrl
        val (finalUrl, bytes) = fetch(c.url, referer)
        val manifest = DashManifestParser().parse(Uri.parse(finalUrl), bytes.inputStream())
        if (manifest.periodCount == 0) return c.copy(status = ProbeStatus.FAILED)

        val durationMs = manifest.durationMs.takeIf { it > 0 && it != C.TIME_UNSET }
        val p0 = manifest.getPeriod(0)
        val drm = p0.adaptationSets.any { set -> set.representations.any { it.format.drmInitData != null } }

        // Ton: die erste Tonspur-Gruppe (meist die Hauptsprache), darin die beste Bitrate.
        val audio = p0.adaptationSets.withIndex().firstOrNull { it.value.type == C.TRACK_TYPE_AUDIO }?.let { (si, set) ->
            set.representations.withIndex().maxByOrNull { it.value.format.bitrate }?.let { (ri, rep) -> Triple(si, ri, rep.format.bitrate) }
        }

        val videoReps = p0.adaptationSets.withIndex()
            .filter { it.value.type == C.TRACK_TYPE_VIDEO }
            .flatMap { (si, set) -> set.representations.withIndex().map { (ri, rep) -> Triple(si, ri, rep.format) } }
            .groupBy { it.third.height }
            .map { (_, same) -> same.maxBy { it.third.bitrate } }
            .sortedWith(compareByDescending<Triple<Int, Int, Format>> { it.third.height }.thenByDescending { it.third.bitrate })

        fun keysFor(si: Int, ri: Int): List<Key> = (0 until manifest.periodCount).mapNotNull { pi ->
            val sets = manifest.getPeriod(pi).adaptationSets
            if (si < sets.size && ri < sets[si].representations.size) Key(pi, si, ri) else null
        }

        val audioKeys = audio?.let { keysFor(it.first, it.second) }.orEmpty()
        val audioBitrate = audio?.third?.takeIf { it > 0 } ?: 0

        val variants = if (videoReps.isEmpty()) {
            listOfNotNull(audio?.let {
                Variant(tr("Nur Ton", "Audio only"), null, it.third.takeIf { b -> b > 0 }, audioKeys, estimate(it.third.takeIf { b -> b > 0 }, durationMs))
            })
        } else {
            videoReps.map { (si, ri, f) ->
                val bitrate = f.bitrate.takeIf { it > 0 }?.plus(audioBitrate)
                Variant(label(f), f.height.takeIf { it > 0 }, bitrate, keysFor(si, ri) + audioKeys, estimate(bitrate, durationMs))
            }
        }
        if (variants.isEmpty()) return c.copy(status = ProbeStatus.FAILED)

        return c.copy(
            status = ProbeStatus.OK,
            mime = MimeTypes.APPLICATION_MPD,
            durationMs = durationMs,
            variants = variants,
            drm = drm,
            live = manifest.dynamic,
        )
    }

    // ---------- Hilfen ----------

    private fun fetch(url: String, referer: String?): Pair<String, ByteArray> =
        http.newCall(request(url, referer)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Leere Antwort")
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw IOException("Manifest zu groß")
            val bytes = body.bytes()
            if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("Manifest zu groß")
            r.request.url.toString() to bytes
        }

    private fun label(f: Format): String = when {
        f.height > 0 -> "${f.height}p"
        f.bitrate > 0 -> "${f.bitrate / 1000} kbit/s"
        else -> "Original"
    }

    private fun estimate(bitrate: Int?, durationMs: Long?): Long? =
        if (bitrate != null && durationMs != null) bitrate.toLong() * durationMs / 8000 else null

    private companion object {
        const val MIN_FILE_BYTES = 100L * 1024
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    }
}
