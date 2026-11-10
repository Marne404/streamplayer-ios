package de.marne.streamplayer.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Schneller Weg zur MP4 für gewöhnliches HLS. Der Transformer fügt über die ganze Abspiel-Pipeline
 * zusammen – für 10 Minuten 720p brauchte er im Emulator 18 s, auf dem Handy bei einer ganzen Folge
 * Minuten. Hier dagegen: die Segmente aus dem Cache aneinanderhängen (bei TS ergibt das wieder einen
 * gültigen Strom, bei fMP4 mit Init-Segment eine fragmentierte MP4) und die Spuren 1:1 mit
 * MediaExtractorCompat → MediaMuxer in eine normale MP4 umpacken. Nichts wird dekodiert.
 *
 * Nur wo das sicher stimmt; sonst [Unsupported], und der Transformer übernimmt:
 * eigene Tonspur-Playlist, Verschlüsselung, Brüche (EXT-X-DISCONTINUITY, oft Werbung), mehrere
 * Init-Segmente. Auch jeder andere Fehler fällt auf den Transformer zurück (siehe StreamEngine.export).
 */
object FastHls {
    class Unsupported(why: String) : Exception(why)

    /**
     * Macht aus jedem 3-Byte-Startcode (00 00 01) einen 4-Byte-Startcode (00 00 00 01).
     * Anlass: Androids MediaMuxer rechnet beim Umwandeln in Längenpräfixe mit vier Bytes und schnitt
     * so vom vorigen NAL ein Byte ab – im ersten Versuch fehlte dem PPS sein letztes Byte, das erste
     * Schlüsselbild war kaputt. x264 und viele Seiten setzen zwischen NALs eines Bildes nur drei Bytes.
     * Im NAL-Inhalt kann 00 00 01 nicht vorkommen (Emulation Prevention), jedes Vorkommen ist ein Startcode.
     */
    internal fun fourByteStartCodes(src: ByteArray, size: Int, dst: ByteArray): Int {
        var o = 0
        var zeros = 0
        for (i in 0 until size) {
            val b = src[i]
            if (b.toInt() == 1 && zeros == 2) dst[o++] = 0
            dst[o++] = b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return o
    }

    /** Platz für das Ergebnis von [fourByteStartCodes]: höchstens ein Byte mehr je drei Bytes. */
    internal fun fixedCapacity(size: Int) = size + size / 3 + 4

    suspend fun export(
        context: android.content.Context,
        source: DataSource.Factory,
        url: String,
        keys: List<StreamKey>,
        work: File,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val ds = source.createDataSource()
        fun open(uri: Uri, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): InputStream =
            DataSourceInputStream(ds, DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())

        val parser = HlsPlaylistParser()
        val root = Uri.parse(url)
        val media: HlsMediaPlaylist = when (val p = open(root).use { parser.parse(root, it) }) {
            is HlsMediaPlaylist -> p
            is HlsMultivariantPlaylist -> {
                val chosen = if (keys.isEmpty()) p else p.copy(keys)
                val v = chosen.variants.singleOrNull() ?: throw Unsupported("keine eindeutige Qualität")
                // Ton in einer eigenen Playlist: zwei Ströme müssten zusammengelegt werden – das kann der Transformer.
                if (chosen.audios.any { it.url != null } || p.audios.any { it.url != null && it.groupId == v.audioGroupId }) {
                    throw Unsupported("eigene Tonspur")
                }
                open(v.url).use { parser.parse(v.url, it) } as? HlsMediaPlaylist ?: throw Unsupported("keine Medien-Playlist")
            }
            else -> throw Unsupported("unbekannte Playlist")
        }
        val segs = media.segments
        if (segs.isEmpty()) throw Unsupported("leer")
        if (segs.any { it.fullSegmentEncryptionKeyUri != null }) throw Unsupported("verschlüsselt")
        if (segs.map { it.relativeDiscontinuitySequence }.distinct().size > 1) throw Unsupported("Brüche im Strom")
        val init = segs.map { it.initializationSegment?.let { s -> Triple(s.url, s.byteRangeOffset, s.byteRangeLength) } }.distinct()
        if (init.size > 1) throw Unsupported("mehrere Init-Segmente")

        // 1. Aneinanderhängen – der größte Teil ist reines Kopieren aus dem Cache.
        val joined = File(work.parentFile, work.name + ".src")
        joined.delete()
        try {
            joined.outputStream().buffered(1 shl 20).use { w ->
                segs.first().initializationSegment?.let { s ->
                    open(UriUtil.resolveToUri(media.baseUri, s.url), s.byteRangeOffset, s.byteRangeLength).use { it.copyTo(w, 1 shl 18) }
                }
                segs.forEachIndexed { i, s ->
                    ensureActive()
                    open(UriUtil.resolveToUri(media.baseUri, s.url), s.byteRangeOffset, s.byteRangeLength).use { it.copyTo(w, 1 shl 18) }
                    onProgress(0.5f * (i + 1) / segs.size)
                }
            }

            // 2. Umpacken in eine normale MP4 (mit Index, damit Spulen und Fortsetzen überall gehen).
            out.delete()
            // Der Leser von Media3, nicht der von Android: Dessen TS-Leser gibt beim Ton ganze PES-Pakete
            // mit 7–8 AAC-Frames als ein Sample heraus – in der MP4 würde daraus abgehackter Ton.
            val ex = androidx.media3.inspector.MediaExtractorCompat(context)
            var muxer: MediaMuxer? = null
            try {
                ex.setDataSource(joined.path)
                muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val map = IntArray(ex.trackCount) { -1 }
                val annexB = BooleanArray(ex.trackCount)
                var maxSize = 1 shl 21
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    // Nur Bild und Ton; ID3-Metadaten und Untertitel im Strom kann MP4 hier nicht tragen.
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    ex.selectTrack(i)
                    map[i] = muxer.addTrack(f)
                    annexB[i] = mime == "video/avc" || mime == "video/hevc"
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (map.none { it >= 0 }) throw Unsupported("keine Spuren")
                muxer.start()
                // Heap statt Direct: das Umschreiben der Startcodes läuft so über schnelle Arrays.
                var buf = ByteBuffer.allocate(maxSize)
                var fixed = ByteArray(fixedCapacity(maxSize))
                val info = MediaCodec.BufferInfo()
                val total = media.durationUs.coerceAtLeast(1)
                var first = -1L
                var samples = 0
                while (true) {
                    val track = ex.sampleTrackIndex
                    if (track < 0) break
                    val need = ex.sampleSize
                    if (need > buf.capacity()) buf = ByteBuffer.allocate(need.toInt() + (1 shl 16))
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    if (map[track] >= 0) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        if (annexB[track]) {
                            if (fixed.size < fixedCapacity(size)) fixed = ByteArray(fixedCapacity(size))
                            val n = fourByteStartCodes(buf.array(), size, fixed)
                            info.set(0, n, ex.sampleTime, flags)
                            muxer.writeSampleData(map[track], ByteBuffer.wrap(fixed, 0, n), info)
                        } else {
                            info.set(0, size, ex.sampleTime, flags)
                            muxer.writeSampleData(map[track], buf, info)
                        }
                        samples++
                        if (first < 0) first = ex.sampleTime
                        if (samples % 500 == 0) {
                            ensureActive()
                            onProgress(0.5f + 0.5f * ((ex.sampleTime - first).toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    ex.advance()
                }
                muxer.stop()
            } finally {
                runCatching { muxer?.release() }
                ex.release()
            }

            // 3. Gegenprobe: Ist die Länge nicht die der Playlist, lieber der langsame, sichere Weg.
            val ms = MediaMetadataRetriever().run {
                try { setDataSource(out.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
            } ?: throw Unsupported("Ergebnis ohne Länge")
            val expected = media.durationUs / 1000
            if (abs(ms - expected) > maxOf(3_000, expected / 50)) throw Unsupported("Länge ${ms} ms statt $expected ms")
            onProgress(1f)
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            joined.delete()
        }
    }
}
