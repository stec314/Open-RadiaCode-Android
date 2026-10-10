package com.radiacode.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun buildRequestFramesLengthCommandAndSequence() {
        val req = RadiacodeProtocol.buildRequest(0x0826, 3, byteArrayOf(0x00, 0x01, 0x00, 0x00))
        // <I len> = 4 header + 4 args
        assertEquals(8, req[0].toInt())
        assertEquals(0, req[1].toInt())
        // command 0x0826 little-endian
        assertEquals(0x26, req[4].toInt() and 0xFF)
        assertEquals(0x08, req[5].toInt() and 0xFF)
        assertEquals(0, req[6].toInt())
        // sequence 0x80 + 3
        assertEquals(0x83, req[7].toInt() and 0xFF)
        assertEquals(12, req.size)
    }

    @Test
    fun sequenceWrapsAt32() {
        val req = RadiacodeProtocol.buildRequest(0x0005, 33, ByteArray(0))
        assertEquals(0x81, req[7].toInt() and 0xFF)
    }

    @Test
    fun packHelpersAreLittleEndian() {
        assertArrayEquals(byteArrayOf(0x04, 0x05, 0, 0), RadiacodeProtocol.packU32LE(0x0504))
        assertArrayEquals(byteArrayOf(1, 2, 3), RadiacodeProtocol.concat(byteArrayOf(1), byteArrayOf(2, 3)))
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), RadiacodeProtocol.packF32LE(1.0f))
    }
}
