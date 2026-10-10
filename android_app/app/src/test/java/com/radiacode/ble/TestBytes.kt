package com.radiacode.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small builder for little-endian test frames. */
class TestBytes {
    private val bb = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(v: Int) = apply { bb.put(v.toByte()) }
    fun u16(v: Int) = apply { bb.putShort(v.toShort()) }
    fun i32(v: Int) = apply { bb.putInt(v) }
    fun f32(v: Float) = apply { bb.putFloat(v) }
    fun bytes(b: ByteArray) = apply { bb.put(b) }
    fun build(): ByteArray = bb.array().copyOf(bb.position())
}
