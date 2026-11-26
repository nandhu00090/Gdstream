package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class ExternalIsoAliasTest {
    @Test fun dvdRepresentationDependsOnTheSelectedPlayerAndPlatformDecoder() {
        for (pkg in listOf("org.videolan.vlc", "is.xyz.mpv", "org.xbmc.kodi"))
            assertFalse(dvdPlayerNeedsAvc(pkg) { false })
        assertTrue(dvdPlayerNeedsAvc("com.brouken.player") { true })
        val vob = "http://127.0.0.1:9090/zip/title/Arundhati.vob"
        val hls = "http://127.0.0.1:9090/hls/title/dvd.m3u8"
        for (entry in listOf(vob, hls)) {
            assertEquals(vob, externalDvdUrl(entry, "title", "Arundhati.vob", avc = false))
            assertEquals(hls, externalDvdUrl(entry, "title", "Arundhati.vob", avc = true))
            assertEquals("$hls?muxed=1", externalDvdUrl(entry, "title", "Arundhati.vob", avc = true, muxed = true))
        }
        assertTrue(dvdPlayerNeedsAvc("androidx.media3.demo.main") { true })
        assertTrue(dvdPlayerNeedsAvc("com.example.video") { false })
        assertFalse(dvdPlayerNeedsAvc("com.example.video") { true })
    }

    @Test fun aliasPreservesEncodingQueryAndFragmentAndRejectsOtherFilenames() {
        val name = "தமிழ் Movie + DTS.m2ts"
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val url = "http://127.0.0.1:9090/zip/disc/$encoded?probe=.m2ts#name=.m2ts"
        val alias = externalIsoTsUrl(url, name, true)
        assertEquals(url.replace(".m2ts?", ".ts?"), alias)
        assertTrue(archiveFilenameMatches(encoded, name, true))
        assertTrue(archiveFilenameMatches(encoded.dropLast(5) + ".ts", name, true))
        assertFalse(archiveFilenameMatches(encoded.dropLast(5) + ".ts", name))
        for (extension in listOf(".srt", ".eng.srt", ".ass", ".ssa", ".vtt")) {
            assertFalse(archiveFilenameMatches(encoded.dropLast(5) + extension, name, true))
        }
        assertEquals(url, externalIsoTsUrl(url, name, false))
        assertEquals(url, externalIsoTsUrl(url, "different.m2ts", true))
        assertEquals(alias, externalIsoTsUrl(alias, name, true))
    }
}
