package com.radiacode.ble

import com.radiacode.ble.device.AlarmUnits
import com.radiacode.ble.device.RegKind
import com.radiacode.ble.device.RegisterCodec
import com.radiacode.ble.device.RegisterSnapshot
import com.radiacode.ble.device.ResponseParsers
import com.radiacode.ble.device.Vsfr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterTest {
    @Test
    fun smallRegistersDecodeFromLowOrHighByte() {
        assertEquals(7.0, RegisterCodec.decode(RegKind.BYTE, 7), 0.0)
        assertEquals(7.0, RegisterCodec.decode(RegKind.BYTE, 7 shl 24), 0.0)
        assertEquals(1.0, RegisterCodec.decode(RegKind.BOOL, 1 shl 24), 0.0)
        assertEquals(0.0, RegisterCodec.decode(RegKind.BOOL, 0), 0.0)
        assertEquals(0x1FE.toDouble(), RegisterCodec.decode(RegKind.U16, 0x1FE), 0.0)
        assertEquals(0x1FE.toDouble(), RegisterCodec.decode(RegKind.U16, 0x1FE shl 16), 0.0)
    }

    @Test
    fun u32AndFloatDecode() {
        assertEquals(4294967295.0, RegisterCodec.decode(RegKind.U32, -1), 0.0)
        val bits = java.lang.Float.floatToIntBits(23.5f)
        assertEquals(23.5, RegisterCodec.decode(RegKind.F32, bits), 1e-6)
        assertEquals(bits, RegisterCodec.encode(RegKind.F32, 23.5))
        assertEquals(1, RegisterCodec.encode(RegKind.BOOL, 5.0))
    }

    @Test
    fun alarmUnitConversions() {
        assertEquals(40L, AlarmUnits.usvToUr(0.4))
        assertEquals(0.4, AlarmUnits.urToUsv(40.0), 1e-9)
        assertEquals(200L, AlarmUnits.cpsToCp10s(20.0))
        assertEquals(20.0, AlarmUnits.cp10sToCps(200.0), 1e-9)
        assertEquals(30, AlarmUnits.displayOffSeconds(3))
    }

    @Test
    fun batchReadHonoursValidityFlags() {
        val resp = TestBytes().i32(0b101).i32(11).i32(22).i32(33).build()
        val out = ResponseParsers.batchRead(resp, 3)
        assertEquals(11, out[0])
        assertNull(out[1])
        assertEquals(33, out[2])
        val req = ResponseParsers.batchReadRequest(listOf(0x8000, 0x8001))
        assertEquals(12, req.size)
        assertEquals(2, req[0].toInt())
    }

    @Test
    fun retcodeAndVirtString() {
        assertTrue(ResponseParsers.retcodeOk(TestBytes().i32(1).build()))
        assertFalse(ResponseParsers.retcodeOk(TestBytes().i32(0).build()))
        val vs = ResponseParsers.virtString(TestBytes().i32(1).i32(3).u8(65).u8(66).u8(67).build())
        assertEquals("ABC", ResponseParsers.ascii(vs))
    }

    @Test
    fun firmwareAndSerial() {
        val fw = TestBytes().u16(5).u16(4).u8(3).bytes("Jan".toByteArray())
            .u16(14).u16(4).u8(4).bytes("2025".toByteArray()).build()
        val info = ResponseParsers.firmwareVersion(fw)
        assertEquals("4.14", info.targetVersion)
        assertEquals("4.05", info.bootVersion)
        assertEquals("2025", info.targetDate)
        val serial = ResponseParsers.hardwareSerial(TestBytes().i32(8).i32(0x1A2B3C4D).i32(0x01).build())
        assertEquals("1A2B3C4D-00000001", serial)
    }

    @Test
    fun configurationParsesKeyValueLines() {
        val cfg = ResponseParsers.configuration("DeviceType=RC-110\r\nFW=4.14\n".toByteArray())
        assertEquals("RC-110", cfg["DeviceType"])
        assertEquals("4.14", cfg["FW"])
    }

    @Test
    fun snapshotJsonRoundTrip() {
        val snap = RegisterSnapshot(mapOf(Vsfr.DISP_BRT to 5, Vsfr.DR_LEV1_uR_h to 40))
        val back = RegisterSnapshot.fromJson(snap.toJson())
        assertNotNull(back)
        assertEquals(5.0, back!!.value(Vsfr.DISP_BRT)!!, 0.0)
        assertEquals(40.0, back.value(Vsfr.DR_LEV1_uR_h)!!, 0.0)
    }
}
