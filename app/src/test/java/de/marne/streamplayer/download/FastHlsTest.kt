package de.marne.streamplayer.download

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Anlass: MediaMuxer schnitt bei 3-Byte-Startcodes vom vorigen NAL ein Byte ab (erstes Schlüsselbild
 * kaputt, PPS ohne letztes Byte). FastHls macht vorher aus jedem 00 00 01 ein 00 00 00 01.
 */
class FastHlsTest {
    private fun fix(vararg b: Int): ByteArray {
        val src = ByteArray(b.size) { b[it].toByte() }
        val dst = ByteArray(FastHls.fixedCapacity(src.size))
        return dst.copyOf(FastHls.fourByteStartCodes(src, src.size, dst))
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun dreiWerdenVier() =
        // Wie im echten Fehlerfall: PPS (68 ef bc b0), dann SEI hinter einem 3-Byte-Startcode.
        assertArrayEquals(bytes(0, 0, 0, 1, 0x68, 0xef, 0xbc, 0xb0, 0, 0, 0, 1, 0x06), fix(0, 0, 0, 1, 0x68, 0xef, 0xbc, 0xb0, 0, 0, 1, 0x06))

    @Test fun vierBleibenVier() =
        assertArrayEquals(bytes(0, 0, 0, 1, 0x65, 0, 0, 0, 1, 0x41), fix(0, 0, 0, 1, 0x65, 0, 0, 0, 1, 0x41))

    @Test fun ersterStartcodeMitDreiBytes() =
        assertArrayEquals(bytes(0, 0, 0, 1, 0x09, 0xf0), fix(0, 0, 1, 0x09, 0xf0))

    @Test fun nullenImInhaltOhneStartcodeBleiben() =
        // 00 00 03 ist Emulation Prevention – kein Startcode, nichts einfügen.
        assertArrayEquals(bytes(0, 0, 0, 1, 0x65, 0, 0, 3, 1, 2), fix(0, 0, 0, 1, 0x65, 0, 0, 3, 1, 2))
}
