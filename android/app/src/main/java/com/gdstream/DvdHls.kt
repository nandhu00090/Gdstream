package com.gdstream

/** Media3 MODE_HLS keys tracks by stream type. Give it a distinct rendition for each
 * selectable PID, all read from the same encoded segment as the multiplexed variant. */
internal class DvdHls(val tracks: DvdStreams) {
    val multivariant = tracks.audio.size > 1 || tracks.subtitles.isNotEmpty()
    private val pids = tracks.audio.indices.map { DvdStreams.AUDIO_PID + it } + tracks.videoPid

    data class Resource(val index: Int? = null, val pid: Int? = null, val master: Boolean = false, val muxed: Boolean = false,
                        val subtitle: Int? = null)
    fun resource(name: String, count: Int, muxed: Boolean = false): Resource? {
        if (name == "dvd.m3u8") return Resource(master = multivariant && !muxed, muxed = muxed)
        if (multivariant && name == "dvd_video.m3u8") return Resource(pid = tracks.videoPid)
        SUBTITLE.matchEntire(name)?.let { match ->
            val track = match.groupValues[1].toIntOrNull()
            if (muxed || track !in tracks.subtitles.indices) return null
            val index = match.groups[2]?.value?.toIntOrNull()
            if (name.endsWith(".vtt") && index !in 0 until count) return null
            return Resource(index = index, subtitle = track)
        }
        val match = FILE.matchEntire(name) ?: return null
        val pid = match.groups[1]?.value?.toIntOrNull()
        if (match.groups[1] != null && (!multivariant || pid !in pids)) return null
        val index = match.groups[2]?.value?.toIntOrNull()
        if (name.endsWith(".ts") && (index == null || index !in 0 until count)) return null
        return Resource(index, pid ?: tracks.videoPid.takeIf { multivariant && !muxed }, muxed = muxed)
    }

    fun playlist(resource: Resource, segments: List<DvdProxy.Segment>): ByteArray {
        if (!resource.master) return DvdProxy.playlist(segments, resource.pid, resource.muxed, resource.subtitle)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            for ((i, track) in tracks.audio.withIndex()) {
                val language = bluRayLanguage(track.language).takeUnless { it == "Unknown" }
                val name = listOfNotNull(language, track.codec, audioChannelLabel(track.channels), "Audio ${i + 1}").joinToString(" • ")
                val default = if (i == 0) "YES" else "NO"
                append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"$name\",LANGUAGE=\"${track.language}\",")
                append("DEFAULT=$default,AUTOSELECT=$default,CHANNELS=\"${track.channels.coerceIn(1, 6)}\",")
                append("URI=\"dvd_pid_${DvdStreams.AUDIO_PID + i}.m3u8\"\n")
            }
            for ((i, track) in tracks.subtitles.withIndex()) {
                val name = "${bluRayLanguage(track.language)} • DVD ${i + 1}"
                append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"$name\",LANGUAGE=\"${track.language}\",")
                append("DEFAULT=NO,AUTOSELECT=NO,URI=\"dvd_sub_$i.m3u8\"\n")
            }
            append("#EXT-X-STREAM-INF:BANDWIDTH=${6500000 + tracks.audio.size * 640000},CODECS=\"avc1")
            if (tracks.audio.isNotEmpty()) append(",ac-3")
            append("\",CLOSED-CAPTIONS=NONE")
            if (tracks.audio.isNotEmpty()) append(",AUDIO=\"audio\"")
            if (tracks.subtitles.isNotEmpty()) append(",SUBTITLES=\"subs\"")
            append("\ndvd_video.m3u8\n")
        }.toByteArray()
    }

    /** Preserve PES, PTS and continuity counters verbatim. Copy the video PCR onto adaptation-only
     * packets on the audio rendition PID so its transport stream is independently timed. */
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
        // TS sniffers inspect five consecutive sync bytes even in a short segment.
        val stuffing = byteArrayOf(0x47, 0x1f, 0xff.toByte(), 0x10) + ByteArray(184) { 0xff.toByte() }
        while (writer.out.size() < 5 * 188) writer.out.write(stuffing)
        return writer.out.toByteArray()
    }

    private fun pidAt(data: ByteArray, p: Int) = ((data[p + 1].toInt() and 31) shl 8) or (data[p + 2].toInt() and 255)

    companion object {
        private val SUBTITLE = Regex("dvd_sub_([0-9]+)(?:_([0-9]+)\\.vtt|\\.m3u8)")
        private val FILE = Regex("dvd(?:_pid_([0-9]+))?(?:_([0-9]+)\\.ts|\\.m3u8)")
    }
}
