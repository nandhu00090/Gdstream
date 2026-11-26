package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class DiscPlaybackTest {
    private val tamil = BluRayStream(0x1100, 0x81, language = "tam")
    private val subtitles = BluRayStream(0x1200, 0x90, language = "tel")

    private fun packet(pid: Int, payload: ByteArray, start: Boolean = true): ByteArray =
        ByteArray(188) { 0xff.toByte() }
            .apply {
                this[0] = 0x47
                this[1] = ((pid ushr 8) or if (start) 0x40 else 0).toByte()
                this[2] = pid.toByte()
                this[3] = 0x10
                payload.copyInto(this, 4)
            }

    private fun pmt(): ByteArray {
        val body =
            byteArrayOf(
                2,
                0xb0.toByte(),
                0,
                0,
                1,
                0xc1.toByte(),
                0,
                0,
                0xf0.toByte(),
                0x11,
                0xf0.toByte(),
                0,
            ) +
                byteArrayOf(
                    0x83.toByte(),
                    0xf1.toByte(),
                    0,
                    0xf0.toByte(),
                    6,
                    5,
                    4,
                    84,
                    82,
                    72,
                    68,
                    0x90.toByte(),
                    0xf2.toByte(),
                    0,
                    0xf0.toByte(),
                    0,
                )
        body[2] = (body.size + 1).toByte()
        val crc = ExternalTsPmt.crc(body)
        return body + ByteArray(4) { (crc ushr (24 - it * 8)).toByte() }
    }

    @Test
    fun growingPmtReplacesTrueHdRegistrationAndLabelsAudioAndPgsAcrossResume() {
        val tracks = listOf(tamil.copy(type = 0x83), subtitles)
        val packet = packet(0x100, byteArrayOf(0) + pmt())
        val unrelated = packet(0x1100, ByteArray(184) { it.toByte() })
        val stamp = byteArrayOf(12, 13, 14, 15)
        val movie = stamp + packet + stamp + unrelated
        fun read(start: Int, size: Int) =
            ExternalTsPmt.readM2ts(start.toLong(), size, movie.size.toLong(), tracks) { pos, len ->
                movie.copyOfRange(pos.toInt(), pos.toInt() + len)
            }
        val result = read(0, 73) + read(73, movie.size - 73)
        assertArrayEquals(read(0, movie.size), result)
        val changed = ExternalTsPmt.rewrite(pmt(), tracks)!!
        assertArrayEquals(changed, result.copyOfRange(9, 9 + changed.size))
        assertEquals(0, ExternalTsPmt.crc(changed))
        assertEquals(0x81, changed[12].toInt() and 255)
        assertArrayEquals(byteArrayOf(10, 4, 116, 97, 109, 0), changed.copyOfRange(17, 23))
        assertArrayEquals(byteArrayOf(10, 4, 116, 101, 108, 0), changed.copyOfRange(28, 34))
        assertArrayEquals(stamp, result.copyOf(4))
        assertArrayEquals(movie.copyOfRange(192, movie.size), result.copyOfRange(192, result.size))
    }

    @Test
    fun packedPmtBorrowsPaddingOnEitherSideAndKeepsContinuity() {
        val body = pmt().dropLast(4).toByteArray()
        // Fill the first packet with a program descriptor so ES descriptors need a continuation.
        val descriptor =
            ByteArray(183 - body.size - 4).apply {
                this[0] = 0x80.toByte()
                this[1] = (size - 2).toByte()
            }
        val large = body.copyOfRange(0, 12) + descriptor + body.copyOfRange(12, body.size)
        large[11] = descriptor.size.toByte()
        large[2] = (large.size + 1).toByte()
        val crc = ExternalTsPmt.crc(large)
        val section = large + ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() }
        val tracks = listOf(tamil, subtitles)
        val expected = ExternalTsPmt.rewrite(section, tracks)!!
        for (paddingFirst in listOf(true, false)) {
            val nullPacket = packet(0x1fff, byteArrayOf(), false)
            val original = packet(0x100, byteArrayOf(0) + section)
            val data = if (paddingFirst) nullPacket + original else original + nullPacket
            ExternalTsPmt.patch(data, tracks)
            val rebuilt = data.copyOfRange(7, 188) + data.copyOfRange(192, 376)
            assertArrayEquals(expected, rebuilt.copyOf(expected.size))
            assertEquals(0, data[191].toInt() and 15)
            assertEquals(0x80, data[5].toInt() and 0x80)
        }
    }

    @Test
    fun fourSecondVariantsSelectOnlyTheirAudioPid() {
        val other = tamil.copy(pid = 0x1101, language = "hin")
        val tracks = listOf(tamil, other, subtitles)
        val data = packet(tamil.pid, byteArrayOf(1)) + packet(other.pid, byteArrayOf(2))
        val profile =
            BluRayProfile(
                8_000_000,
                listOf(BluRayPoint(0, 0), BluRayPoint(4_000_000, data.size.toLong())),
                emptyList(),
                tracks,
            )
        val hls = BluRayHls.create(profile, data.size.toLong() * 2)!!
        val master = String(hls.master())
        assertTrue(master.contains("LANGUAGE=\"tam\""))
        assertTrue(master.contains("URI=\"audio_4353.m3u8\""))
        assertFalse(master.contains("audio_4608"))
        val media = String(hls.playlist(other.pid))
        assertTrue(media.contains("#EXT-X-TARGETDURATION:4"))
        assertTrue(media.contains("audio_4353_seg_1.ts"))
        assertArrayEquals(
            data.copyOfRange(188, 376),
            hls.segments.first().audioBytes(byteArrayOf(), other.pid) { start, size ->
                data.copyOfRange(start.toInt(), start.toInt() + size)
            },
        )
    }

    @Test
    fun prefetchStartsOnFirstDeliveredChunkAndSkipsHead() {
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        try {
            for (method in listOf("GET", "HEAD")) {
                val out = java.io.ByteArrayOutputStream()
                val file = IsoVirtualFile(listOf(0L to 188L * 3), false)
                var starts = 0
                file.serve(
                    method,
                    null,
                    "video/MP2T",
                    out,
                    executor,
                    maxReadBytes = 188,
                    onStreamStart = {
                        starts++
                        val split = out.toString("ISO-8859-1").indexOf("\r\n\r\n") + 4
                        assertEquals(188, out.size() - split)
                    },
                ) { _, size ->
                    ByteArray(size)
                }
                assertEquals(if (method == "GET") 1 else 0, starts)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun audioFramesSupplyLayoutAndRatesForMovieAverage() {
        val ac3 = byteArrayOf(11, 119, 0, 0, 36, 64, 0xe1.toByte(), 0) + ByteArray(16)
        val transport =
            ByteArray(4) +
                packet(
                    tamil.pid,
                    byteArrayOf(0, 0, 1, 0xbd.toByte(), 0, 0, 0x80.toByte(), 0, 0) + ac3,
                )
        val audio = DiscAudio.inspectM2ts(transport, listOf(tamil)).single()
        assertEquals(640, audio.kbps)
        assertEquals("5.1 ch", audio.layout)
        val eac3 = javaClass.getResourceAsStream("/bluray/silent.eac3.bin")!!.readBytes()
        assertEquals(DiscAudio("2.0 ch", 192), DiscAudio.probe(eac3, 0x84))
        val profile = BluRayProfile(10_000_000, emptyList(), emptyList(), listOf(audio))
        val info = profile.mediaInfo(31_800_000)
        assertEquals(24.8, info["videoBitrateMbps"] as Double, 0.000001)
        assertEquals(
            "Tamil • AC-3 • 5.1 ch • 640 kbps",
            (info["audioTracks"] as List<Map<*, *>>).single()["summary"],
        )
        val major =
            byteArrayOf(0xf8.toByte(), 0x72, 0x6f, 0xba.toByte(), 0, 0, 0, 0x4f) + ByteArray(10)
        assertEquals(DiscAudio("7.1 ch", 640), DiscAudio.probe(ac3 + major, 0x83))
    }

    @Test fun titleCadtAndVobuMapPartitionTheSelectedVobSectorsExactlyOnce() {
        val ifo = ByteArray(5 * 2048)
        fun put(p: Int, value: Int, n: Int) {
            repeat(n) { ifo[p + it] = (value ushr ((n - it - 1) * 8)).toByte() }
        }
        "DVDVIDEO-VTS".toByteArray().copyInto(ifo)
        put(0xc8, 1, 4)
        put(0xcc, 2, 4)
        put(0xe0, 3, 4)
        put(0xe4, 4, 4)
        put(2048, 1, 2)
        put(2052, 15, 4)
        put(2056, 12, 4)
        put(2060, 1, 2)
        put(2062, 1, 2)
        put(4096, 1, 2)
        put(4108, 16, 4)
        val pgc = 4112
        put(pgc + 2, 1, 1)
        put(pgc + 3, 1, 1)
        put(pgc + 230, 236, 2)
        put(pgc + 232, 240, 2)
        put(pgc + 234, 264, 2)
        put(pgc + 236, 1, 1)
        put(pgc + 244, 0x00000840, 4) // 8 s PAL
        val layer = 1 shl 21 // exercise DVD9 offsets above 4 GiB
        put(pgc + 248, layer + 100, 4)
        put(pgc + 260, layer + 155, 4)
        put(pgc + 264, 9, 2)
        put(pgc + 267, 2, 1)
        val cadt = 3 * 2048
        put(cadt, 2, 2)
        put(cadt + 4, 8 + 3 * 12 - 1, 4)
        fun address(index: Int, vob: Int, start: Int, last: Int) {
            val p = cadt + 8 + index * 12
            put(p, vob, 2)
            put(p + 2, 2, 1)
            put(p + 4, layer + start, 4)
            put(p + 8, layer + last, 4)
        }
        address(0, 9, 100, 119)
        address(1, 8, 120, 129) // different VOB with the same cell ID
        address(2, 9, 130, 155)
        val starts = listOf(90, 100, 101, 105, 110, 113, 115, 116, 118, 120, 125, 130, 131, 135, 140, 145, 148, 151, 153, 156)
        put(8192, 4 + starts.size * 4 - 1, 4)
        starts.forEachIndexed { i, sector -> put(8196 + i * 4, layer + sector, 4) }
        val parser = DvdTitleInfo.parse(ifo)
        val title = parser.main()
        val segments = parser.segments(title)
        assertEquals(listOf(DvdProxy.Segment(0, 20L * 2048, 4_000_000),
            DvdProxy.Segment(20L * 2048, 46L * 2048, 4_000_000)), segments)
        // Map each virtual range back through a VOB-file boundary into disc sectors.
        val sources = DvdTitleInfo.extents(title.cells, listOf(0L to (layer + 112L) * 2048, (layer + 1000L) * 2048 to 100L * 2048))
        val file = IsoVirtualFile(sources, false)
        val sectors = segments.flatMap { segment ->
            file.mappedRanges(segment.start, (segment.end - segment.start).toInt()).flatMap { bytes ->
                (bytes.first / 2048..bytes.last / 2048).toList()
            }
        }
        val expected = (100L..111).toList() + (1000L..1007).toList() + (1018L..1043).toList()
        assertEquals(expected.map { layer + it }, sectors)
    }

    @Test
    fun dvdTitleReferencesCellsAndUsesActiveStreamMappings() {
        val ifo = ByteArray(6144)
        fun put(p: Int, value: Int, n: Int) {
            repeat(n) { ifo[p + it] = (value ushr ((n - it - 1) * 8)).toByte() }
        }
        "DVDVIDEO-VTS".toByteArray().copyInto(ifo)
        put(0xc8, 1, 4)
        put(0xcc, 2, 4)
        put(2048, 1, 2)
        put(2052, 15, 4)
        put(2056, 12, 4)
        put(2060, 1, 2)
        put(2062, 1, 2)
        val pgc = 4112
        put(4096, 1, 2)
        put(4108, 16, 4)
        put(pgc + 2, 1, 1)
        put(pgc + 3, 1, 1)
        put(pgc + 230, 236, 2)
        put(pgc + 232, 240, 2)
        put(pgc + 236, 1, 1)
        val cell = pgc + 240
        put(cell + 4, 0x00010552, 4)
        put(cell + 8, 1, 4)
        put(cell + 20, 2, 4)
        put(0x200, 0x50, 1)
        put(0x202, 1, 2)
        put(0x205, 5, 1)
        "ta".toByteArray().copyInto(ifo, 0x206)
        put(pgc + 12, 0x8100, 2)
        put(0x254, 1, 2)
        put(pgc + 28, 128, 1)
        "en".toByteArray().copyInto(ifo, 0x258)
        val specs = DvdTitleInfo.parse(ifo)
        val title = specs.main()
        // 00:01:05 + 12 PAL frames.
        assertEquals(65.48, title.duration, 0.000001)
        assertEquals(
            listOf(4096L to 2048L, 10000L to 2048L),
            DvdTitleInfo.extents(title.cells, listOf(2048L to 4096L, 10000L to 4096L)),
        )
        val pes =
            byteArrayOf(
                0,
                0,
                1,
                0xbd.toByte(),
                0,
                40,
                0x80.toByte(),
                0,
                0,
                0x81.toByte(),
                0,
                0,
                0,
                11,
                119,
                0,
                0,
                28,
                64,
                0xe1.toByte(),
                0,
            ) + ByteArray(30)
        val info = specs.info(title, pes, 50_000_000)
        // The opening PGC has all audio controls disabled. A later PGC
        // selects physical AC-3 stream 1 for logical VTS audio attribute 0.
        val silentOpening = specs.info(title.copy(programChains = listOf(5200) + title.programChains), ByteArray(0), 50_000_000)
        assertEquals(0x81, silentOpening["ac3Substream"])
        assertEquals(6, silentOpening["ac3Channels"])
        assertEquals("MPEG-2", info["videoCodec"])
        assertEquals(
            "Tamil • AC-3 • 5.1 ch • 384 kbps",
            (info["audioTracks"] as List<Map<*, *>>).single()["summary"],
        )
        assertEquals("English", (info["subtitleTracks"] as List<Map<*, *>>).single()["language"])

        // Logical stream numbers and physical VOB IDs need not match. For 16:9
        // only the widescreen SPU number is selected, even when a letterbox ID differs.
        put(0x200, 0x5c, 1)
        put(0x202, 3, 2)
        put(0x20c, 6 shl 5, 1)
        put(0x20d, 5, 1)
        "en".toByteArray().copyInto(ifo, 0x20e)
        put(pgc + 14, 0x8300, 2)
        put(0x254, 5, 2)
        for (i in 0..3) put(pgc + 28 + i * 4, 0x80000800.toInt() or ((5 + i) shl 16), 4)
        put(pgc + 0xa4 + 4, 0x00eb8080, 4)
        val disc = DvdStreams.from(specs.info(title, pes, 50_000_000))
        assertEquals(listOf(0x81, 0x8b), disc.audio.map { it.id })
        assertEquals(listOf("tam", "eng"), disc.audio.map { it.language })
        assertEquals(listOf(6, 6), disc.audio.map { it.channels })
        assertEquals(listOf(0x25, 0x26, 0x27, 0x28), disc.subtitles.map { it.id })
        assertEquals(listOf("eng", "und", "und", "und"), disc.subtitles.map { it.language })
        val program = disc.programAt(2048)
        assertEquals(disc.audio.map { it.id }, program.audio)
        assertEquals(disc.subtitles.map { it.id }, program.subtitles)
        assertEquals(0xeb8080, program.clut[1])
    }
}
