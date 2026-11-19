package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class ExternalTsPmtTest {
    private fun pmt(): ByteArray {
        val body = byteArrayOf(2, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0xf0.toByte(), 0x11, 0xf0.toByte(), 0) +
            listOf(0x24 to 0x1011, 0x24 to 0x1015, 0x80 to 0x1100, 0x86 to 0x1101, 0x83 to 0x1102, 0x90 to 0x1200)
                .flatMap { (type, pid) -> listOf(type.toByte(), (0xe0 or (pid ushr 8)).toByte(), pid.toByte(), 0xf0.toByte(), 0) }.toByteArray()
        body[2] = (body.size + 1).toByte()
        val crc = ExternalTsPmt.crc(body)
        return body + ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() }
    }
    @Test fun removesDependentVideoAndPcmAndAdvertisesCoreCodecsWithValidCrc() {
        val result = ExternalTsPmt.rewrite(pmt())!!
        assertEquals(0, ExternalTsPmt.crc(result))
        assertEquals(result.size - 3, result[2].toInt() and 255)
        assertEquals(listOf(0x24, 0x82, 0x81, 0x90), (12 until result.size - 4 step 5).map { result[it].toInt() and 255 })
        assertEquals(0x11, result[14].toInt())
    }
    @Test fun alternateAudioRemovesPesAndPmtEntriesFromVideoButRetainsClock() {
        val stream = BluRayStream(0x1101, 0x86)
        val section = ExternalTsPmt.rewrite(pmt(), listOf(stream), videoOnly = true)!!
        assertEquals(0, ExternalTsPmt.crc(section))
        assertEquals(listOf(0x24, 0x90), (12 until section.size - 4 step 5).map { section[it].toInt() and 255 })
        val data = ByteArray(376) { 99 }.apply {
            for (p in listOf(0, 188)) {
                this[p] = 0x47; this[p+1] = 0x51; this[p+2] = 1; this[p+3] = 0x10
            }
            this[191] = 0x30; this[192] = 7; this[193] = 16
        }
        val clock = data.copyOfRange(194, 200)
        ExternalTsPmt.patch(data, listOf(stream), videoOnly = true)
        assertEquals(0x1fff, ((data[1].toInt() and 31) shl 8) or (data[2].toInt() and 255))
        assertEquals(0x20, data[191].toInt() and 0x30)
        assertArrayEquals(clock, data.copyOfRange(194, 200))
        assertTrue(data.drop(200).all { it == 0xff.toByte() })
    }

    @Test fun corruptCrcIsNotRewritten() {
        val section = pmt(); section[8] = 0
        assertNull(ExternalTsPmt.rewrite(section))
    }
    @Test fun fragmentedSectionPreservesPacketCountAndUnrelatedPayload() {
        val section = pmt()
        val packets = ByteArray(188 * 3) { 0xff.toByte() }
        fun header(at: Int, start: Boolean, cc: Int) {
            packets[at] = 0x47; packets[at + 1] = if (start) 0x41 else 1
            packets[at + 2] = 0; packets[at + 3] = (0x30 or cc).toByte()
        }
        header(0, true, 0); packets[4] = 170.toByte(); packets[175] = 0
        section.copyInto(packets, 176, 0, 12)
        // Other PID between PSI fragments.
        packets[188] = 0x47; packets[189] = 2; packets[190] = 0; packets[191] = 0x10
        header(376, false, 1); packets[380] = 0
        section.copyInto(packets, 381, 12)
        val unrelated = packets.copyOfRange(188, 376)
        ExternalTsPmt.patch(packets)
        val rebuilt = packets.copyOfRange(176, 188) + packets.copyOfRange(381, 381 + section.size - 12)
        val changed = ExternalTsPmt.rewrite(section)!!
        assertArrayEquals(changed, rebuilt.copyOf(changed.size))
        assertTrue(rebuilt.drop(changed.size).all { it == 0xff.toByte() })
        assertArrayEquals(unrelated, packets.copyOfRange(188, 376))
    }
    @Test fun nativeDolbyDropsInterleavedTrueHdAndPreservesHevcRegistration() {
        for (nativeType in listOf(0x81, 0x84)) {
            val original = pmt().dropLast(4).toByteArray()
            // Add HEVC registration descriptor to the first ES and a native Dolby PID.
            original[16] = 6
            val body = original.copyOfRange(0, 17) + byteArrayOf(5, 4, 0x48, 0x45, 0x56, 0x43) +
                original.copyOfRange(17, original.size) + byteArrayOf(nativeType.toByte(), 0xf1.toByte(), 6, 0xf0.toByte(), 0)
            body[2] = (body.size + 1).toByte()
            val crc = ExternalTsPmt.crc(body)
            val result = ExternalTsPmt.rewrite(body + ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() })!!
            assertEquals(0, ExternalTsPmt.crc(result))
            assertArrayEquals(byteArrayOf(5, 4, 0x48, 0x45, 0x56, 0x43), result.copyOfRange(17, 23))
            val types = ArrayList<Int>()
            val pids = ArrayList<Int>()
            var at = 12
            while (at < result.size - 4) {
                types.add(result[at].toInt() and 255)
                pids.add(((result[at + 1].toInt() and 31) shl 8) + (result[at + 2].toInt() and 255))
                at += 5 + ((result[at + 3].toInt() and 15) shl 8) + (result[at + 4].toInt() and 255)
            }
            assertEquals(listOf(0x24, 0x82, 0x90, nativeType), types)
            assertFalse(pids.contains(0x1102))
            assertTrue(pids.contains(0x1106))
        }
    }

}
