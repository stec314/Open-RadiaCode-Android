package com.radiacode.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataBufTest {
    private fun header(b: TestBytes, gid: Int) = b.u8(1).u8(0).u8(gid).i32(0)

    @Test
    fun decodesRealtimeRareDataAndEvents() {
        val b = TestBytes()
        header(b, 0).f32(7.2f).f32(9.0e-6f).u16(105).u16(204).u16(0).u8(0)
        header(b, 3).i32(3600).f32(3.0e-7f).u16(2000 + 2450).u16(9300).u16(0)
        header(b, 7).u8(9).u8(0).u16(0)
        header(b, 7).u8(19).u8(0).u16(0)
        val d = RadiacodeDataBuf.decodeFullData(b.build())

        val rt = d.realTimeData
        assertNotNull(rt)
        assertEquals(7.2f, rt!!.countRate, 1e-5f)
        assertEquals(0.09f, rt.doseRate * 10000f, 1e-5f)
        assertEquals(10.5f, rt.countRateErr, 1e-5f)

        assertEquals(93, d.metadata!!.batteryLevel)
        assertEquals(24.5f, d.metadata!!.temperature, 1e-4f)

        assertEquals(3600L, d.dose!!.durationSeconds)
        assertEquals(0.3f, d.dose!!.doseSv * 1_000_000f, 1e-4f)

        assertEquals(2, d.events.size)
        assertEquals("Dose rate alarm 1", d.events[0].name)
        assertTrue(d.events[0].isAlarm)
        assertEquals("Spectrum reset", d.events[1].name)
    }

    @Test
    fun stopsCleanlyOnUnknownRecord() {
        val b = TestBytes()
        header(b, 0).f32(1f).f32(1e-7f).u16(0).u16(0).u16(0).u8(0)
        b.u8(1).u8(9).u8(9).i32(0).u8(1).u8(2)
        val d = RadiacodeDataBuf.decodeFullData(b.build())
        assertNotNull(d.realTimeData)
        assertEquals(0, d.events.size)
    }
}
