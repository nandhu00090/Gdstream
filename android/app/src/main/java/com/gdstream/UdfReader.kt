package com.gdstream

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Random-access UDF reader. All external offsets and lengths are bytes, never sectors. */
internal class UdfReader(
    private val imageSize: Long,
    private val read: (Long, Long) -> ByteArray,
    private val executor: ExecutorService? = null
) {

    data class File(val path: String, val size: Long, val extents: List<Pair<Long, Long>>)
    private data class Partition(val start: Long, val size: Long)
    private data class MapSpec(val number: Int, val metadata: Boolean, val file: Long = -1, val mirror: Long = -1)
    private data class Entry(val size: Long, val extents: List<Pair<Long, Long>>)

    var volumeLabel: String = ""
        private set
    private var blockSize = 2048L
    private val partitions = HashMap<Int, Partition>()
    private val metadata = HashMap<Int, List<Pair<Long, Long>>>()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)

    private fun checked(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun bytes(offset: Long, length: Long): ByteArray {
        checked(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline, "UDF scan timed out or was cancelled")
        checked(offset >= 0 && length in 1..MAX_METADATA && offset <= imageSize - length,
            "UDF read outside image: $offset + $length / $imageSize")
        return read(offset, offset + length - 1).also {
            checked(it.size.toLong() == length, "Truncated UDF read at $offset")
        }
    }

    private fun u16(b: ByteArray, p: Int): Int {
        checked(p >= 0 && p <= b.size - 2, "Truncated UDF field at $p")
        return (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    }

    private fun u32(b: ByteArray, p: Int): Long = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) or (u32(b, p + 4) shl 32)).also {
        checked(it >= 0, "UDF length exceeds signed 64-bit range")
    }

    /** Map a whole logical range: a metadata allocation can itself cross physical extents. */
    private fun mapRange(ref: Int, offset: Long, length: Long): List<Pair<Long, Long>> {
        checked(offset >= 0 && length >= 0, "Negative UDF range")
        if (length == 0L) return emptyList()
        val meta = metadata[ref]
        if (meta != null) {
            var skip = offset
            var left = length
            val result = ArrayList<Pair<Long, Long>>()
            for ((start, size) in meta) {
                if (skip >= size) { skip -= size; continue }
                val take = minOf(left, size - skip)
                result.add((if (start < 0) -1L else start + skip) to take)
                left -= take
                if (left == 0L) return result
                skip = 0
            }
            throw IOException("UDF metadata range outside partition $ref: $offset + $length")
        }
        val part = partitions[ref] ?: throw IOException("Unresolved UDF partition reference $ref")
        checked(length <= part.size && offset <= part.size - length, "UDF range outside physical partition $ref")
        return listOf((part.start + offset) to length)
    }

    private fun readExtents(extents: List<Pair<Long, Long>>, length: Long): ByteArray {
        checked(length in 0..MAX_METADATA, "UDF metadata too large: $length")
        val result = ByteArray(length.toInt())
        var pos = 0
        for ((start, size) in extents) {
            val take = minOf(size, length - pos).toInt()
            if (take <= 0) break
            if (start >= 0) bytes(start, take.toLong()).copyInto(result, pos)
            pos += take
        }
        checked(pos == result.size, "Incomplete UDF allocation list")
        return result
    }

    private fun logicalBytes(ref: Int, offset: Long, length: Long) = readExtents(mapRange(ref, offset, length), length)

    private fun entry(block: Long, ref: Int): Entry {
        val offset = block * blockSize
        var buf = logicalBytes(ref, offset, blockSize)
        val tag = u16(buf, 0)
        checked(tag == 261 || tag == 266, "Expected UDF FE/EFE (261/266), got $tag at $ref:$block")
        // ECMA-167 FE and EFE share Information Length at 56, but have different EA/AD fields.
        val size = u64(buf, 56)
        val headerSize = if (tag == 266) 216 else 176
        val eaLength = u32(buf, headerSize - 8)
        val adLength = u32(buf, headerSize - 4)
        val adStart = headerSize + eaLength
        checked(adStart + adLength <= MAX_METADATA, "Oversized UDF FE/EFE allocation descriptors")
        if (adStart + adLength > buf.size) buf = logicalBytes(ref, offset, adStart + adLength)
        val allocType = u16(buf, 34) and 7
        if (allocType == 3) {
            checked(size <= adLength, "Truncated inline UDF file")
            return Entry(size, mapRange(ref, offset + adStart, size))
        }
        checked(allocType in 0..2, "Unsupported UDF allocation type $allocType")
        val width = when (allocType) { 0 -> 8; 1 -> 16; else -> 20 }
        val result = ArrayList<Pair<Long, Long>>()
        val continuations = HashSet<Pair<Int, Long>>()
        var left = size
        var descriptors = buf.copyOfRange(adStart.toInt(), (adStart + adLength).toInt())
        var p = 0
        while (p < descriptors.size && left > 0) {
            checked(p + width <= descriptors.size, "Truncated UDF allocation descriptor")
            val rawLength = u32(descriptors, p)
            val extentType = (rawLength ushr 30).toInt()
            val extentLength = rawLength and 0x3fffffffL
            val location = p + if (allocType == 2) 12 else 4
            val extentBlock = u32(descriptors, location)
            val extentRef = if (allocType == 0) ref else u16(descriptors, location + 4)
            if (extentLength == 0L) { p += width; continue }
            if (extentType == 3) {
                checked(continuations.add(extentRef to extentBlock) && continuations.size <= 64,
                    "Cyclic or excessive UDF allocation continuation")
                checked(extentLength in 24..MAX_METADATA, "Invalid UDF allocation extent length")
                val aed = logicalBytes(extentRef, extentBlock * blockSize, extentLength)
                checked(u16(aed, 0) == 258, "Expected UDF allocation extent descriptor (258)")
                val length = u32(aed, 20)
                checked(length <= aed.size - 24L, "Truncated UDF allocation extent descriptor")
                descriptors = aed.copyOfRange(24, 24 + length.toInt())
                p = 0
                continue
            }
            val informationLength = if (allocType == 2) u32(descriptors, p + 8) else extentLength
            val recordedLength = if (allocType == 2) u32(descriptors, p + 4) else extentLength
            checked(informationLength <= extentLength && recordedLength <= extentLength,
                "Invalid UDF extended allocation lengths")
            val take = minOf(left, informationLength)
            val recorded = if (extentType == 0) minOf(take, recordedLength) else 0L
            if (recorded > 0) result.addAll(mapRange(extentRef, extentBlock * blockSize, recorded))
            // Unrecorded/unallocated bytes occupy file positions; never shift following extents left.
            if (take > recorded) result.add(-1L to (take - recorded))
            left -= take
            p += width
        }
        checked(left == 0L, "Incomplete UDF FE/EFE: missing $left of $size bytes")
        return Entry(size, result)
    }

    fun readTree(): List<File> {
        val sectors = imageSize / SECTOR_SIZE
        val errors = ArrayList<String>()
        // Backup anchors are at N and N-256, where N is the LAST sector number.
        for (sector in listOf(256L, sectors - 257, sectors - 1, 512L).distinct()) {
            if (sector < 0 || sector >= sectors) continue
            try {
                val anchor = bytes(sector * SECTOR_SIZE, 512)
                if (u16(anchor, 0) != 2) continue
                for (p in listOf(16, 24)) {
                    try {
                        val length = u32(anchor, p)
                        val location = u32(anchor, p + 4)
                        checked(length >= SECTOR_SIZE, "Empty UDF descriptor sequence")
                        return readVolume(bytes(location * SECTOR_SIZE, length))
                    } catch (e: IOException) { errors.add(e.message ?: "UDF volume error") }
                }
            } catch (e: IOException) { errors.add(e.message ?: "UDF anchor error") }
        }
        throw IOException("Unable to read UDF: ${errors.distinct().take(4).joinToString("; ").ifEmpty { "no anchor" }}")
    }

    private fun readVolume(vds: ByteArray): List<File> {
        partitions.clear()
        metadata.clear()
        val physical = HashMap<Int, Partition>()
        var lvd: ByteArray? = null
        var sequence = -1L
        var p = 0
        while (p + 512 <= vds.size) {
            when (u16(vds, p)) {
                8 -> break
                5 -> {
                    val start = u32(vds, p + 188) * SECTOR_SIZE
                    val size = u32(vds, p + 192) * SECTOR_SIZE
                    checked(size > 0 && start <= imageSize - size, "UDF physical partition outside image")
                    physical[u16(vds, p + 22)] = Partition(start, size)
                }
                6 -> if (u32(vds, p + 16) >= sequence) {
                    val mapLength = u32(vds, p + 264)
                    checked(mapLength <= MAX_METADATA && p + 440L + mapLength <= vds.size, "Truncated UDF partition maps")
                    lvd = vds.copyOfRange(p, p + 440 + mapLength.toInt())
                    sequence = u32(vds, p + 16)
                }
            }
            p += SECTOR_SIZE.toInt()
        }
        val logical = lvd ?: throw IOException("Missing UDF logical volume descriptor")
        volumeLabel = decodeVolumeLabel(logical.copyOfRange(84, 212))
        blockSize = u32(logical, 212)
        checked(blockSize in 512..32768 && (blockSize and (blockSize - 1)) == 0L, "Invalid UDF logical block size")
        val count = u32(logical, 268)
        checked(count in 1..256, "Invalid UDF partition map count")
        val specs = ArrayList<MapSpec>()
        p = 440
        repeat(count.toInt()) {
            checked(p + 2 <= logical.size, "Truncated UDF partition map")
            val type = logical[p].toInt() and 255
            val length = logical[p + 1].toInt() and 255
            checked(length >= 6 && p + length <= logical.size, "Invalid UDF partition map length")
            val spec = when {
                type == 1 && length == 6 -> MapSpec(u16(logical, p + 4), false)
                type == 2 && length == 64 &&
                    String(logical, p + 5, 23, Charsets.US_ASCII).trimEnd('\u0000') == "*UDF Metadata Partition" ->
                    MapSpec(u16(logical, p + 38), true, u32(logical, p + 40), u32(logical, p + 44))
                else -> throw IOException("Unsupported UDF partition map type $type")
            }
            specs.add(spec)
            // Map indices are partition REFERENCES, not physical partition numbers. Resolve after all PDs.
            if (!spec.metadata) partitions[it] = physical[spec.number]
                ?: throw IOException("Missing UDF physical partition ${spec.number}")
            p += length
        }
        checked(p == logical.size, "Inconsistent UDF map table length")
        val fsdBlock = u32(logical, 252)
        val fsdRef = u16(logical, 256)
        checked((u32(logical, 248) and 0x3fffffffL) >= 416L, "Invalid UDF file set descriptor length")
        for ((ref, spec) in specs.withIndex()) {
            if (!spec.metadata) continue
            // Metadata File ICB locations are relative to their physical partition, NOT the metadata map.
            partitions[-1] = physical[spec.number] ?: throw IOException("Missing metadata backing partition")
            var failure: IOException? = null
            for (candidate in listOf(spec.file, spec.mirror).distinct()) {
                if (candidate == 0xffffffffL) continue
                try {
                    val meta = entry(candidate, -1)
                    checked(meta.size > 0, "Empty UDF metadata file")
                    metadata[ref] = meta.extents
                    // A readable FE with bad metadata data must also fall back to the mirror.
                    if (ref == fsdRef) root(fsdBlock, fsdRef)
                    failure = null
                    break
                } catch (e: IOException) {
                    metadata.remove(ref)
                    failure = e
                }
            }
            if (!metadata.containsKey(ref)) throw failure ?: IOException("No UDF metadata file or mirror")
        }
        partitions.remove(-1)
        val root = root(fsdBlock, fsdRef)
        val files = ArrayList<File>()
        walk(root.first, root.second, "", files, HashSet())
        checked(files.isNotEmpty(), "UDF has no supported media files")
        return files
    }

    private fun root(block: Long, ref: Int): Pair<Long, Int> {
        val fsd = logicalBytes(ref, block * blockSize, blockSize)
        checked(u16(fsd, 0) == 256, "Expected UDF FSD (256), got ${u16(fsd, 0)} at $ref:$block")
        return u32(fsd, 404) to u16(fsd, 408)
    }

    private fun walk(block: Long, ref: Int, path: String, files: MutableList<File>, visited: MutableSet<Pair<Int, Long>>) {
        checked(visited.add(ref to block) && visited.size <= 256, "Cyclic or excessive UDF directories")
        val dir = entry(block, ref)
        val data = readExtents(dir.extents, dir.size)
        val pending = ArrayList<Triple<String, Long, Int>>()
        var p = 0
        while (p + 38 <= data.size) {
            val tag = u16(data, p)
            if (tag == 0) { p = ((p / blockSize + 1) * blockSize).toInt(); continue }
            checked(tag == 257, "Invalid UDF directory descriptor $tag in $path")
            val flags = data[p + 18].toInt() and 255
            val nameLength = data[p + 19].toInt() and 255
            val nameStart = p + 38 + u16(data, p + 36)
            checked(nameStart + nameLength <= data.size, "Truncated UDF file identifier")
            val childBlock = u32(data, p + 24)
            val childRef = u16(data, p + 28)
            p += (nameStart - p + nameLength + 3) and 3.inv()
            if (flags and 12 != 0 || nameLength <= 1) continue
            val charset = when (data[nameStart].toInt() and 255) {
                8 -> Charsets.ISO_8859_1
                16 -> Charsets.UTF_16BE
                else -> throw IOException("Invalid UDF filename compression ID")
            }
            val name = String(data, nameStart + 1, nameLength - 1, charset)
            val fullPath = if (path.isEmpty()) name else "$path/$name"
            val upper = fullPath.uppercase(java.util.Locale.ROOT)
            if (flags and 2 != 0) {
                if (upper in listOf("BDMV", "BDMV/STREAM", "BDMV/PLAYLIST", "BDMV/CLIPINF", "VIDEO_TS")) {
                    walk(childBlock, childRef, fullPath, files, visited)
                }
            } else if (MEDIA_EXTENSIONS.any { upper.endsWith(it) }) {
                pending.add(Triple(fullPath, childBlock, childRef))
            }
        }
        for (batch in pending.chunked(8)) {
            val jobs = batch.map { child ->
                val load = {
                    val file = entry(child.second, child.third)
                    File(child.first, file.size, file.extents)
                }
                executor?.submit<File> { load() } to load
            }
            try {
                for ((future, load) in jobs) {
                    val remaining = deadline - System.nanoTime()
                    checked(remaining > 0, "UDF scan timed out")
                    val file = try { future?.get(remaining, TimeUnit.NANOSECONDS) ?: load() } catch (e: Exception) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        throw IOException("UDF file entry read failed: ${(e.cause ?: e).message}", e)
                    }
                    if (file.size > 0) files.add(file)
                }
            } finally {
                jobs.forEach { (future, _) -> future?.cancel(true) }
            }
        }
    }

    companion object {
        internal fun decodeVolumeLabel(field: ByteArray): String {
            if (field.size < 2) return ""
            val length = (field.last().toInt() and 255).coerceAtMost(field.size - 1)
            if (length <= 1) return ""
            return when (field[0].toInt() and 255) {
                8 -> String(field, 1, length - 1, Charsets.ISO_8859_1)
                16 -> if ((length - 1) % 2 == 0) String(field, 1, length - 1, Charsets.UTF_16BE) else ""
                else -> ""
            }.trimEnd('\u0000').trim()
        }

        private const val SECTOR_SIZE = 2048L
        private const val MAX_METADATA = 8L * 1024 * 1024
        private val MEDIA_EXTENSIONS = listOf(".IFO", ".CLPI", ".MPLS", ".M2TS", ".VOB", ".MKV", ".MP4", ".AVI", ".MOV", ".M4V", ".TS")
    }
}
