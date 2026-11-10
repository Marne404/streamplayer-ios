package de.marne.streamplayer.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlClassifierTest {

    @Test fun manifestsByExtension() {
        assertEquals(MediaKind.HLS, UrlClassifier.classify("https://cdn.example/v/master.m3u8?token=abc"))
        assertEquals(MediaKind.DASH, UrlClassifier.classify("https://cdn.example/v/manifest.mpd"))
        assertEquals(MediaKind.HLS, UrlClassifier.classify("https://cdn.example/play?file=video.m3u8"))
    }

    @Test fun filesByExtension() {
        assertEquals(MediaKind.FILE, UrlClassifier.classify("https://example.org/clip.MP4"))
        assertEquals(MediaKind.FILE, UrlClassifier.classify("https://example.org/talk.webm#t=10"))
        assertEquals(MediaKind.FILE, UrlClassifier.classify("https://example.org/podcast.mp3"))
    }

    @Test fun mimeWinsOverExtension() {
        assertEquals(MediaKind.HLS, UrlClassifier.classify("https://x.example/stream", "application/vnd.apple.mpegurl"))
        assertEquals(MediaKind.DASH, UrlClassifier.classify("https://x.example/stream", "application/dash+xml; charset=utf-8"))
        assertEquals(MediaKind.FILE, UrlClassifier.classify("https://x.example/get?id=7", "video/mp4"))
        assertNull(UrlClassifier.classify("https://x.example/seg.ts", "video/mp2t"))
    }

    @Test fun ignoresNonMedia() {
        assertNull(UrlClassifier.classify("https://example.org/app.js"))
        assertNull(UrlClassifier.classify("https://example.org/"))
        assertNull(UrlClassifier.classify("blob:https://example.org/1234"))
        assertNull(UrlClassifier.classify("data:video/mp4;base64,AAAA"))
    }

    @Test fun segments() {
        assertTrue(UrlClassifier.looksLikeSegment("https://cdn.example/v/720p/seg-12-v1-a1.ts"))
        assertTrue(UrlClassifier.looksLikeSegment("https://cdn.example/v/chunk_00042.m4s"))
        assertTrue(UrlClassifier.looksLikeSegment("https://cdn.example/v/segment3.mp4"))
        assertTrue(UrlClassifier.looksLikeSegment("https://cdn.example/videoplayback?itag=22&range=0-1000"))
        assertFalse(UrlClassifier.looksLikeSegment("https://example.org/movie_part2.mp4"))
        assertFalse(UrlClassifier.looksLikeSegment("https://example.org/videos/holiday.mp4"))
    }

    @Test fun helpers() {
        assertEquals("mp4", UrlClassifier.extension("https://a.example/b/c.mp4?x=1#y"))
        assertNull(UrlClassifier.extension("https://a.example/b/"))
        assertEquals("Mein Film.mp4", UrlClassifier.fileName("https://a.example/Mein%20Film.mp4?x=1"))
        assertEquals("https://a.example/b", UrlClassifier.dir("https://a.example/b/c.mp4?x=1"))
        assertEquals("https://a.example/c.mp4", UrlClassifier.key("https://a.example/c.mp4#t=3"))
    }
}
