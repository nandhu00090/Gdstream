package com.gdstream

/** Media3 MODE_HLS keys tracks by stream type. Give it a distinct rendition for each
 * selectable PID, all read from the same encoded segment as the multiplexed variant. */
internal class DvdHls(val tracks: DvdStreams) {
    val multivariant = tracks.audio.size > 1 || tracks.subtitles.size > 1
    private val pids = tracks.audio.indices.map { DvdStreams.AUDIO_PID + it } +
        tracks.subtitles.indices.map { DvdStreams.SUBTITLE_PID + it }

    data class Resource(val index: Int? = null, val pid: Int? = null, val master: Boolean = false)
    fun resource(name: String, count: Int, muxed: Boolean = false): Resource? {
        if (name == "dvd.m3u8") return Resource(master = multivariant && !muxed)
        if (multivariant && name == "dvd_video.m3u8") return Resource()
        val match = FILE.matchEntire(name) ?: return null
        val pid = match.groups[1]?.value?.toIntOrNull()
        if (match.groups[1] != null && (!multivariant || pid !in pids)) return null
        val index = match.groups[2]?.value?.toIntOrNull()
        if (name.endsWith(".ts") && (index == null || index !in 0 until count)) return null
        return Resource(index, pid)
    }

    fun playlist(resource: Resource, segments: List<DvdProxy.Segment>): ByteArray {
        if (!resource.master) return DvdProxy.playlist(segments, resource.pid)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            fun rendition(type: String, group: String, name: String, track: DvdStreams.Track, pid: Int, default: Boolean) {
                append("#EXT-X-MEDIA:TYPE=$type,GROUP-ID=\"$group\",NAME=\"$name\",LANGUAGE=\"${track.language}\",")
                append("DEFAULT=${if (default) "YES" else "NO"},AUTOSELECT=${if (default) "YES" else "NO"},")
                if (type == "AUDIO") append("CHANNELS=\"${track.channels.coerceIn(1, 6)}\",")
                append("URI=\"dvd_pid_$pid.m3u8\"\n")
            }
            for ((i, track) in tracks.audio.withIndex()) {
                val language = bluRayLanguage(track.language).takeUnless { it == "Unknown" }
                val name = listOfNotNull(language, track.codec, audioChannelLabel(track.channels), "Audio ${i + 1}").joinToString(" • ")
                rendition("AUDIO", "audio", name, track, DvdStreams.AUDIO_PID + i, i == 0)
            }
            for ((i, track) in tracks.subtitles.withIndex())
                rendition("SUBTITLES", "subs", "Subtitle ${i + 1}", track, DvdStreams.SUBTITLE_PID + i, false)
            append("#EXT-X-STREAM-INF:BANDWIDTH=${6500000 + tracks.audio.size * 640000},CODECS=\"avc1")
            if (tracks.audio.isNotEmpty()) append(",ac-3")
            append("\",CLOSED-CAPTIONS=NONE")
            if (tracks.audio.isNotEmpty()) append(",AUDIO=\"audio\"")
            if (tracks.subtitles.isNotEmpty()) append(",SUBTITLES=\"subs\"")
            append("\ndvd_video.m3u8\n")
        }.toByteArray()
    }

    /** Preserve PES, PTS and continuity counters verbatim. Copy the video PCR onto adaptation-only
     * packets on the rendition PID so an audio or sparse DVB TS is independently timed. */
    fun rendition(ts: ByteArray, pid: Int, index: Int): ByteArray {
        val cc = IntArray(8192)
        cc[0] = index
        cc[0x100] = index
        val writer = DvdTsWriter(cc)
        writer.tables(tracks, pid)
        var continuity = -1
        val clock = ByteArray(188) { 0xff.toByte() }
        clock[0] = 0x47
        clock[1] = (pid ushr 8).toByte()
        clock[2] = pid.toByte()
        clock[4] = 183.toByte()
        clock[5] = 0x10
        // Adaptation-only packets preceding the first PES repeat its preceding counter.
        for (p in ts.indices step 188) if (pidAt(ts, p) == pid) {
            continuity = (ts[p + 3].toInt() - 1) and 15
            break
        }
        for (p in ts.indices step 188) {
            when (pidAt(ts, p)) {
                pid -> {
                    writer.out.write(ts, p, 188)
                    continuity = ts[p + 3].toInt() and 15
                }
                tracks.videoPid -> if (ts[p + 3].toInt() and 32 != 0 && (ts[p + 4].toInt() and 255) >= 7 &&
                    ts[p + 5].toInt() and 16 != 0) {
                    clock[3] = (0x20 or (continuity and 15)).toByte()
                    ts.copyInto(clock, 6, p + 6, p + 12)
                    writer.out.write(clock)
                }
            }
        }
        // TS sniffers inspect five consecutive sync bytes even in a subtitle-only opening.
        val stuffing = byteArrayOf(0x47, 0x1f, 0xff.toByte(), 0x10) + ByteArray(184) { 0xff.toByte() }
        while (writer.out.size() < 5 * 188) writer.out.write(stuffing)
        return writer.out.toByteArray()
    }

    private fun pidAt(data: ByteArray, p: Int) = ((data[p + 1].toInt() and 31) shl 8) or (data[p + 2].toInt() and 255)

    companion object {
        private val FILE = Regex("dvd(?:_pid_([0-9]+))?(?:_([0-9]+)\\.ts|\\.m3u8)")
    }
}
