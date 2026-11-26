package com.gdstream

/** Logical VTS tracks keep a stable output PID even when the next PGC remaps its VOB IDs. */
internal class DvdStreams(
    val audio: List<Track>,
    val subtitles: List<Track> = emptyList(),
    val programs: List<Program> = emptyList(),
    val runs: List<Pair<Long, Int>> = emptyList(),
) {
    data class Track(val id: Int, val language: String, val channels: Int = 0, val codec: String = "")
    data class Program(val audio: List<Int>, val subtitles: List<Int>, val clut: IntArray)
    val videoPid = if (audio.size <= 1) 0x102 else 0x110
    val primary = audio.indexOfFirst { it.id in 0x80..0x87 }.coerceAtLeast(0)
    private val defaultProgram = Program(audio.map { it.id }, subtitles.map { it.id }, IntArray(16))

    fun programAt(offset: Long): Program {
        val index = runs.binarySearchBy(offset) { it.first }
        val run = runs.getOrNull(if (index < 0) -index - 2 else index)
        return run?.let { programs[it.second] } ?: defaultProgram
    }

    companion object {
        const val AUDIO_PID = 0x101
        const val SUBTITLE_PID = 0x120

        fun from(info: Map<String, Any>): DvdStreams {
            fun rows(key: String) = (info[key] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
            fun integers(row: Map<*, *>, key: String) = (row[key] as List<*>).map { (it as Number).toInt() }
            fun track(row: Map<*, *>) = Track((row["substreamId"] as Number).toInt(),
                row["languageCode"] as? String ?: "und", (row["channelCount"] as? Number)?.toInt() ?: 0,
                row["codec"] as? String ?: "")
            val audioRows = rows("audioTracks")
            val indices = audioRows.indices.filter { ((audioRows[it]["substreamId"] as? Number)?.toInt() ?: -1) >= 0 }
            val audio = indices.map { track(audioRows[it]) }
            val subtitles = rows("subtitleTracks").filter { it.containsKey("substreamId") }.map(::track)
            val programs = rows("dvdPrograms").map { row ->
                val ids = integers(row, "audio")
                Program(indices.map { ids[it] }, integers(row, "subpictures"), integers(row, "clut").toIntArray())
            }
            val runs = (info["dvdProgramRuns"] as? List<*>)?.map { row ->
                val pair = row as List<*>
                (pair[0] as Number).toLong() to (pair[1] as Number).toInt()
            }.orEmpty()
            // Standalone VOB probing has no IFO and provides the original AC-3 metadata.
            if (programs.isEmpty() && audio.isEmpty()) {
                val id = (info["ac3Substream"] as? Number)?.toInt() ?: -1
                if (id in 0x80..0x87) return DvdStreams(listOf(Track(id, info["audioLanguageCode"] as? String ?: "und",
                    (info["ac3Channels"] as? Number)?.toInt() ?: 2, "AC-3")))
            }
            return DvdStreams(audio, subtitles, programs, runs)
        }
    }
}
