package de.marne.streamplayer.subtitles

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anlass (1.11.0): Untertitel der Seite gehen mit in Player und Download – Teilstücke von HLS nicht. */
class SubtitlesTest {
    @Test fun ganzeDateienJaTeilstueckeNein() {
        assertTrue(Subtitles.looksLike("https://cdn.x/subs/de.vtt?token=1"))
        assertTrue(Subtitles.looksLike("https://cdn.x/film.German.srt"))
        assertFalse(Subtitles.looksLike("https://cdn.x/subs/0.vtt"))
        assertFalse(Subtitles.looksLike("https://cdn.x/subs/fileSequence12.webvtt"))
        assertFalse(Subtitles.looksLike("https://cdn.x/video.mp4"))
    }

    @Test fun formatAusDerEndung() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SubTrack("https://x/a.srt").mime)
        assertEquals(MimeTypes.TEXT_SSA, SubTrack("/data/subs/own-1.ass").mime)
        assertEquals(MimeTypes.TEXT_VTT, SubTrack("https://x/sub?id=3").mime)
    }
}
