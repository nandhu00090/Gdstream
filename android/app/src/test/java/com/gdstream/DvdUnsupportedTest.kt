package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DvdUnsupportedTest {
    @Test
    fun unsupportedMpeg2TriggersOnlyWhenNoVideoCanPlay() {
        fun group(mime: String, support: Int) =
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
                false,
                intArrayOf(support),
                booleanArrayOf(false),
            )
        val dvd = group("video/mpeg2", C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertTrue(dvdMpeg2Unsupported(Tracks(listOf(dvd))))
        assertFalse(
            dvdMpeg2Unsupported(Tracks(listOf(dvd, group("video/mpeg2", C.FORMAT_HANDLED))))
        )
        assertFalse(dvdMpeg2Unsupported(Tracks(listOf(group("audio/ac3", C.FORMAT_HANDLED)))))
        assertFalse(dvdMpeg2Unsupported(Tracks.EMPTY))
    }
}
