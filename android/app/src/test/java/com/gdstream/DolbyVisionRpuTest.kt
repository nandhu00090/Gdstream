package com.gdstream

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DolbyVisionRpuTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/dovi/$name.bin")!!.use { it.readBytes() }
    private fun nals(bytes: ByteArray): List<ByteArray> {
        val starts = (0..bytes.size - 4).filter { bytes[it] == 0.toByte() && bytes[it+1] == 0.toByte() && bytes[it+2] == 0.toByte() && bytes[it+3] == 1.toByte() }
        return starts.mapIndexed { index, start -> byteArrayOf(0x7c, 1) + bytes.copyOfRange(start + 4, starts.getOrNull(index + 1) ?: bytes.size) }
    }
    @Test fun melAndFelMatchIndependentDoviToolProfile81Vectors() {
        for (kind in listOf("mel", "fel")) {
            val input = nals(fixture("${kind}_orig"))
            val expected = nals(fixture("${kind}_to_81"))
            assertTrue(input.isNotEmpty()); assertEquals(input.size, expected.size)
            input.zip(expected).forEach { (source, target) ->
                assertArrayEquals(kind, target, DolbyVisionRpu.toProfile81(source))
                assertArrayEquals("8.1 remains stable", target, DolbyVisionRpu.toProfile81(target))
            }
        }
    }
    @Test fun corruptOrTruncatedRpuIsRejectedBeforeInjection() {
        val source = nals(fixture("fel_orig")).first()
        for (broken in listOf(source.copyOf(12), source.clone().also { it[15] = (it[15].toInt() xor 1).toByte() }, byteArrayOf(0x7e, 1))) {
            try { DolbyVisionRpu.toProfile81(broken); fail("must reject malformed RPU") } catch (_: IOException) {}
        }
    }
}
