package com.gdstream

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UdfReaderTest {
    /** Sparse 45 GiB image: only on-disc structures are stored, media data is never allocated. */
    private class Disc(val bs: Int = 2048, val tag: Int = 266) {
        val size = 45L * 1024 * 1024 * 1024
        val base = 512L * 2048
        val chunks = sortedMapOf<Long, ByteArray>()
        val expected = ArrayList<Pair<Long, Long>>()
        val fileSize = 40L * 1024 * 1024 * 1024 + 123
        fun word(b: ByteArray, p: Int, v: Long, width: Int = 4) {
            repeat(width) { b[p + it] = (v ushr (8 * it)).toByte() }
        }
        fun descriptor(id: Int, size: Int = bs) = ByteArray(size).also { word(it, 0, id.toLong(), 2) }
        fun metaOffset(block: Int): Long = base + (if (block < 6) 10L + block else 40L + block - 6) * bs
        fun putMeta(block: Int, data: ByteArray) {
            var pos = 0
            while (pos < data.size) {
                val count = minOf(bs, data.size - pos)
                chunks[metaOffset(block + pos / bs)] = data.copyOfRange(pos, pos + count)
                pos += count
            }
        }
        fun ad(length: Long, block: Long, ref: Int = 1, type: Int = 0, kind: Int = 1): ByteArray {
            val b = ByteArray(when (kind) { 0 -> 8; 1 -> 16; else -> 20 })
            word(b, 0, length or (type.toLong() shl 30))
            val location = if (kind == 2) 12 else 4
            if (kind == 2) { word(b, 4, length); word(b, 8, length) }
            word(b, location, block)
            if (kind != 0) word(b, location + 4, ref.toLong(), 2)
            return b
        }
        fun fe(size: Long, ads: ByteArray, kind: Int, fileType: Int = 5): ByteArray {
            val b = descriptor(tag)
            b[27] = fileType.toByte()
            word(b, 34, kind.toLong(), 2)
            word(b, 56, size, 8)
            if (tag == 266) word(b, 64, size + 1234, 8) // Object Size is NOT Information Length.
            val header = if (tag == 266) 216 else 176
            word(b, header - 8, 16) // Exercise EA skip for both FE and EFE.
            word(b, header - 4, ads.size.toLong())
            ads.copyInto(b, header + 16)
            return b
        }
        fun fid(name: String, block: Int, directory: Boolean = true): ByteArray {
            val encoded = byteArrayOf(8) + name.toByteArray(Charsets.ISO_8859_1)
            val b = descriptor(257, (38 + encoded.size + 3) and 3.inv())
            b[18] = if (directory) 2 else 0
            b[19] = encoded.size.toByte()
            word(b, 20, bs.toLong())
            word(b, 24, block.toLong())
            word(b, 28, 0, 2)
            encoded.copyInto(b, 38)
            return b
        }
        init {
            val anchor = descriptor(2, 2048)
            word(anchor, 16, 3L * 2048); word(anchor, 20, 257)
            word(anchor, 24, 3L * 2048); word(anchor, 28, 257)
            chunks[256L * 2048] = anchor
            // LVD deliberately comes BEFORE PD; metadata map index 0 refers to physical partition #7.
            val lvd = descriptor(6, 2048)
            word(lvd, 16, 1); word(lvd, 212, bs.toLong())
            word(lvd, 248, bs.toLong()); word(lvd, 252, 0); word(lvd, 256, 0, 2)
            word(lvd, 264, 70); word(lvd, 268, 2)
            lvd[440] = 2; lvd[441] = 64
            "*UDF Metadata Partition".toByteArray().copyInto(lvd, 445)
            word(lvd, 476, 1, 2); word(lvd, 478, 7, 2)
            word(lvd, 480, 4); word(lvd, 484, 5)
            lvd[504] = 1; lvd[505] = 6
            word(lvd, 506, 1, 2); word(lvd, 508, 7, 2)
            chunks[257L * 2048] = lvd
            val pd = descriptor(5, 2048)
            word(pd, 22, 7, 2); word(pd, 188, 512); word(pd, 192, (size - base) / 2048)
            chunks[258L * 2048] = pd
            chunks[259L * 2048] = descriptor(8, 2048)
            val metaAds = ad(6L * bs, 10, kind = 0) + ad(10L * bs, 40, kind = 0)
            chunks[base + 4L * bs] = fe(16L * bs, metaAds, 0, 250)
            chunks[base + 5L * bs] = fe(16L * bs, metaAds, 0, 251)
            val fsd = descriptor(256)
            word(fsd, 400, bs.toLong()); word(fsd, 404, 1); word(fsd, 408, 0, 2)
            putMeta(0, fsd)
            val root = fid("BDMV", 2)
            putMeta(1, fe(root.size.toLong(), root, 3, 4))
            val bdmv = fid("STREAM", 3)
            putMeta(2, fe(bdmv.size.toLong(), bdmv, 3, 4))
            putMeta(3, fe(2L * bs, ad(2L * bs, 5, kind = 0), 0, 4))
            // A directory extent AND its final FID cross the discontinuous metadata mapping.
            val directory = ByteArray(2 * bs)
            word(directory, 0, 257, 2); directory[18] = 4
            word(directory, 36, (bs - 8 - 38).toLong(), 2)
            fid("00001.m2ts", 4, false).copyInto(directory, bs - 8)
            putMeta(5, directory)
            var left = fileSize
            var block = 300000L
            val descriptors = ArrayList<ByteArray>()
            while (left > 0) {
                val allocated = minOf(0x3ffff800L, ((left + bs - 1) / bs) * bs)
                val take = minOf(left, allocated)
                descriptors.add(ad(allocated, block))
                expected.add((base + block * bs) to take)
                left -= take
                block += allocated / bs
            }
            val aed = descriptor(258)
            val continued = descriptors.drop(1).fold(ByteArray(0)) { a, b -> a + b }
            word(aed, 20, continued.size.toLong()); continued.copyInto(aed, 24)
            putMeta(8, aed)
            putMeta(4, fe(fileSize, descriptors[0] + ad(bs.toLong(), 8, 0, 3), 1))
        }
        fun read(start: Long, end: Long): ByteArray {
            assertTrue(start >= 0 && end < size)
            assertTrue(end - start < 8 * 1024 * 1024)
            val b = ByteArray((end - start + 1).toInt())
            for ((offset, data) in chunks) {
                val from = maxOf(start, offset)
                val until = minOf(end + 1, offset + data.size)
                if (from < until) data.copyInto(b, (from - start).toInt(), (from - offset).toInt(), (until - offset).toInt())
            }
            return b
        }
        fun files() = UdfReader(size, ::read).readTree()
    }

    @Test fun udfTreeScanCoalescesDescriptorAndDirectorySectors() {
        val disc = Disc()
        val ranges = ArrayList<Pair<Long, Long>>()
        val files = UdfReader(disc.size, { start, end ->
            ranges.add(start to end)
            disc.read(start, end)
        }).readTree()
        assertEquals(disc.expected, files.single().extents)
        assertEquals(2, ranges.size)
        assertTrue(ranges.all { it.first % (512 * 1024) == 0L && it.second - it.first + 1 == 512 * 1024L })
    }

    @Test fun bd50MetadataMappingsAndContinuationDescriptors() {
        for (tag in listOf(261, 266)) for (bs in listOf(1024, 2048)) {
            val disc = Disc(bs, tag)
            val file = disc.files().single()
            assertEquals("BDMV/STREAM/00001.m2ts", file.path)
            assertEquals(disc.fileSize, file.size)
            assertEquals(disc.expected, file.extents)
            assertEquals(file.size, file.extents.sumOf { it.second })
            assertTrue(file.extents.last().first > 40L * 1024 * 1024 * 1024)
        }
    }

    @Test fun backupAnchorAtLastSectorMinus256() {
        val disc = Disc()
        val anchor = disc.chunks.remove(256L * 2048)!!
        disc.chunks[(disc.size / 2048 - 257) * 2048] = anchor
        assertEquals(disc.fileSize, disc.files().single().size)
    }

    @Test fun metadataMirrorRecoversFromUnreadablePrimaryAndBadFsd() {
        for (badFsd in listOf(false, true)) {
            val disc = Disc()
            if (badFsd) {
                // The primary FE is valid, but points at data without an FSD.
                disc.chunks[disc.base + 4L * disc.bs] = disc.fe(16L * disc.bs, disc.ad(16L * disc.bs, 100, kind = 0), 0)
            } else disc.chunks[disc.base + 4L * disc.bs] = disc.descriptor(0)
            assertEquals(disc.expected, disc.files().single().extents)
        }
    }

    @Test fun rejectsMissingContinuationCyclicContinuationAndUnknownPartition() {
        for (mode in 0..2) {
            val disc = Disc()
            when (mode) {
                0 -> disc.putMeta(8, disc.descriptor(0))
                1 -> {
                    val aed = disc.descriptor(258)
                    disc.word(aed, 20, 16)
                    disc.ad(disc.bs.toLong(), 8, 0, 3).copyInto(aed, 24)
                    disc.putMeta(8, aed)
                }
                2 -> disc.putMeta(4, disc.fe(64, disc.ad(64, 123, 9), 1))
            }
            assertThrows(IOException::class.java) { disc.files() }
        }
    }

    @Test fun shortExtendedAndSparseAllocationsPreserveFilePositions() {
        for (kind in listOf(0, 2)) {
            val disc = Disc()
            val ads = disc.ad(16, 7, 0, kind = kind) + disc.ad(32, 0, 0, type = 2, kind = kind) +
                disc.ad(64, 9, 0, kind = kind)
            disc.putMeta(4, disc.fe(100, ads, kind))
            val file = disc.files().single()
            assertEquals(listOf(disc.metaOffset(7) to 16L, -1L to 32L, disc.metaOffset(9) to 52L), file.extents)
        }
    }

    @Test fun rejectsMetadataAddressPastItsExtentList() {
        val disc = Disc()
        val fsd = disc.chunks[disc.metaOffset(0)]!!
        disc.word(fsd, 404, 16) // Exactly past 16-block metadata file; no contiguous fallback is valid.
        assertThrows(IOException::class.java) { disc.files() }
    }
}
