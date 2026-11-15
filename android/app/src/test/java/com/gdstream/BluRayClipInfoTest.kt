package com.gdstream

import org.junit.Assert.*
import org.junit.Test

internal class TestBits(private val data: ByteArray, private var position: Int = 0) {
    fun put(value: Long, count: Int): TestBits {
        for (i in count - 1 downTo 0) {
            val at = position++
            data[at / 8] = (data[at / 8].toInt() or (((value ushr i) and 1).toInt() shl (7 - at % 8))).toByte()
        }
        return this
    }
}

class BluRayClipInfoTest {
    private val inTicks = 27_000_064L
    private val times = listOf(inTicks, inTicks + 460_800, inTicks + 449_689_600)
    private val packets = listOf(32L, 130_000L, 220_000_000L)
    private fun fixture(): ByteArray {
        val bytes = ByteArray(256)
        "HDMV0200".toByteArray().copyInto(bytes)
        fun uint(at: Int, value: Long, n: Int = 4) { TestBits(bytes, at * 8).put(value, n * 8) }
        uint(12, 32); uint(16, 96)
        uint(32, 46); uint(37, 1, 1) // ProgramInfo length and number of programs
        uint(42, 0x100, 2); uint(44, 3, 1)
        var at = 46
        for ((pid, type) in listOf(0x1011 to 0x1b, 0x1100 to 0x80, 0x1101 to 0x86)) {
            uint(at, pid.toLong(), 2); uint(at + 2, 5, 1); uint(at + 3, type.toLong(), 1)
            uint(at + 4, 0x31, 1); "tam".toByteArray().copyInto(bytes, at + 5); at += 8
        }
        uint(96, 84); uint(101, 1, 1); uint(103, 1, 1)
        // Offset 30 is relative to CPI+6 (102), not CPI itself or the descriptor table.
        TestBits(bytes, 104 * 8).put(0x1011, 16).put(0, 10).put(1, 4).put(3, 16).put(3, 18).put(30, 32)
        uint(132, 28) // fine table relative to map stream start
        for (i in 0..2) {
            TestBits(bytes, (136 + i * 8) * 8).put(i.toLong(), 18).put(times[i] ushr 18, 14).put(packets[i], 32)
            TestBits(bytes, (160 + i * 4) * 8).put(0, 4).put((times[i] ushr 8) and 2047, 11).put(packets[i] and 131071, 17)
        }
        return bytes
    }
    @Test fun parsesCpiRelativePointers45KhzTimesAndFortyGbPacketOffsets() {
        val profile = BluRayClipInfo.parse(fixture(), inTicks, 10009L * 45000, 43_000_000_000L)
        assertEquals(3, profile.points.size)
        assertEquals(BluRayPoint(0, 32 * 188L), profile.points.first())
        assertEquals((times.last() - inTicks) * 1_000_000 / 45000, profile.points.last().timeUs)
        assertEquals(41_360_000_000L, profile.points.last().position)
        assertEquals(listOf(BluRayAudio(0x1100, 0x80, "tam"), BluRayAudio(0x1101, 0x86, "tam")), profile.audio)
        assertEquals(profile, BluRayProfile.fromJson(profile.toJson()))
    }
    @Test fun rejectsTruncatedMaliciousAndOutOfBoundsCpi() {
        val fixture = fixture()
        for (length in listOf(0, 31, 40, 105, 150, 171)) {
            assertThrows(IllegalArgumentException::class.java) { BluRayClipInfo.parse(fixture.copyOf(length), inTicks, 45000, 1000) }
        }
        fixture[16] = 0x7f
        assertThrows(IllegalArgumentException::class.java) { BluRayClipInfo.parse(fixture, inTicks, 45000, 1000) }
    }
    @Test fun missingCpiRetainsAudioMetadataAndFallsBackWithoutAnIndex() {
        val data = fixture(); data.fill(0, 16, 20)
        val profile = BluRayClipInfo.parse(data, inTicks, 45000, 1000)
        assertTrue(profile.points.isEmpty())
        assertEquals(2, profile.audio.size)
    }
    @Test fun trimmedPlayItemsDoNotInstallAMapWithTheWrongTimestampEpoch() {
        val data = fixture()
        TestBits(data, 8 * 8).put(192, 32)
        TestBits(data, 192 * 8).put(22, 32).put(0, 8).put(1, 8)
            .put(0, 32).put(1, 8).put(0, 8).put(0x1001, 16).put(0, 32)
            .put(inTicks - 45000, 32).put(inTicks + 45000, 32)
        val profile = BluRayClipInfo.parse(data, inTicks, 45000, 43_000_000_000L)
        assertTrue(profile.points.isEmpty())
        assertEquals(2, profile.audio.size)
    }
    @Test fun lpcmConvertsPaddingChannelOrderAnd24BitEndianWithoutLosingSamples() {
        val mono = HdmvPcm.layout(byteArrayOf(0, 4, 0x11, 0x40))
        assertArrayEquals(byteArrayOf(2, 1), HdmvPcm.decode(byteArrayOf(1, 2, 99, 99), mono))
        val surround = HdmvPcm.layout(byteArrayOf(0, 12, 0x91.toByte(), 0x40))
        assertArrayEquals(byteArrayOf(2, 1, 4, 3, 6, 5, 12, 11, 8, 7, 10, 9), HdmvPcm.decode(ByteArray(12) { (it + 1).toByte() }, surround))
        val highRes = HdmvPcm.layout(byteArrayOf(0, 6, 0x34, 0xc0.toByte()))
        assertEquals(96000, highRes.rate)
        assertArrayEquals(byteArrayOf(3, 2, 1, 6, 5, 4), HdmvPcm.decode(byteArrayOf(1, 2, 3, 4, 5, 6), highRes))
        assertThrows(IllegalArgumentException::class.java) { HdmvPcm.decode(ByteArray(11), surround) }
    }
}
