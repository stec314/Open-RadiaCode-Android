package com.radiacode.ble

import com.radiacode.ble.device.SpectrumCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumCodecTest {
    private fun hdr(count: Int, vlen: Int) = (count shl 4) or vlen

    @Test
    fun decodesVersion0PlainArray() {
        val b = TestBytes()
        for (i in 0 until 1024) b.i32(i * 2)
        val counts = SpectrumCodec.decodeCounts(b.build(), 0, 4096)
        assertEquals(1024, counts.size)
        assertEquals(0, counts[0])
        assertEquals(2046, counts[1023])
    }

    @Test
    fun decodesRleWithAllValueWidths() {
        val b = TestBytes()
            .u16(hdr(3, 1)).u8(5).u8(6).u8(7)          // absolute bytes: 5 6 7
            .u16(hdr(2, 2)).u8(1).u8(-2)               // deltas: 8 6
            .u16(hdr(2, 0))                            // zeros: 0 0
            .u16(hdr(1, 3)).u16(300)                   // 300
            .u16(hdr(1, 4)).u8(0x10).u8(0x27).u8(0)    // +10000 -> 10300
            .u16(hdr(1, 5)).i32(-300)                  // 10000
        val data = b.build()
        val counts = SpectrumCodec.decodeCounts(data, 0, data.size)
        val expected = intArrayOf(5, 6, 7, 8, 6, 0, 0, 300, 10300, 10000)
        for (i in expected.indices) assertEquals("channel $i", expected[i], counts[i])
        assertEquals(1024, counts.size)
        assertEquals(0, counts[1023])
    }

    @Test
    fun truncatedRleDoesNotThrow() {
        val data = TestBytes().u16(hdr(4, 3)).u16(10).build()
        val counts = SpectrumCodec.decodeCounts(data, 0, data.size)
        assertEquals(10, counts[0])
        assertEquals(0, counts[1])
    }

    @Test
    fun decodesSpectrumHeader() {
        val payload = TestBytes().i32(3600).f32(1.5f).f32(2.5f).f32(0.0004f).u16(hdr(1, 1)).u8(9).build()
        val d = SpectrumCodec.decodeSpectrum(payload)
        assertEquals(3600, d.durationSeconds)
        assertEquals(2.5f, d.a1, 1e-6f)
        assertEquals(9, d.counts[0])
    }

    @Test
    fun calibrationPayloadLayout() {
        val p = SpectrumCodec.calibrationWritePayload(0x0202, 1f, 2f, 3f)
        assertEquals(20, p.size)
        assertEquals(0x02, p[0].toInt())
        assertEquals(0x02, p[1].toInt())
        assertEquals(12, p[4].toInt())
    }
}
