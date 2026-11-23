package com.gdstream

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal object FfmpegBridge {
    val available by lazy {
        try { FFmpegKitConfig.getVersion().isNotEmpty() } catch (_: LinkageError) { false }
    }

    internal fun arguments(input: File, output: File, pid: Int) = arrayOf(
        "-hide_banner", "-nostats", "-loglevel", "error", "-y", "-copyts",
        "-f", "mpegts", "-i", input.absolutePath, "-map", "0:a:0", "-c:a", "ac3",
        "-ac", "6", "-ar", "48000", "-b:a", "640k", "-streamid", "0:$pid",
        "-avoid_negative_ts", "disabled", "-mpegts_copyts", "1", "-muxdelay", "0",
        "-f", "mpegts", output.absolutePath
    )

    fun ac3(source: ByteArray, prefix: ByteArray, pid: Int, dir: File, owner: IsoReadSession): ByteArray {
        val input = File.createTempFile("bdav-dts-", ".ts", dir)
        try {
            val output = File.createTempFile("bdav-ac3-", ".ts", dir)
            try {
                input.writeBytes(source)
                val job = FFmpegSession.create(arguments(input, output, pid))
                val lease = owner.onCancel { FFmpegKit.cancel(job.sessionId) }
                try {
                    owner.checkActive()
                    FFmpegKitConfig.ffmpegExecute(job)
                    owner.checkActive()
                    if (!ReturnCode.isSuccess(job.returnCode)) throw IOException("DTS to AC-3 failed for PID $pid (rc=${job.returnCode})")
                } finally { lease.close() }
                // Use the source program number and PMT PID on every rendition. FFmpeg's PES
                // carries the original media timeline and its PCR is already on the audio PID.
                val bytes = output.readBytes()
                val ts = ByteArrayOutputStream(prefix.size + bytes.size)
                ts.write(prefix)
                for (p in 0 until bytes.size - 187 step 188) {
                    val packetPid = ((bytes[p + 1].toInt() and 31) shl 8) or (bytes[p + 2].toInt() and 255)
                    if (packetPid == pid) ts.write(bytes, p, 188)
                }
                if (ts.size() == prefix.size) throw IOException("Empty AC-3 rendition for PID $pid")
                return ts.toByteArray()
            } finally { output.delete() }
        } finally { input.delete() }
    }
}
