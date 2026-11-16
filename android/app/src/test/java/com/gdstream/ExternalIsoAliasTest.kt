package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class ExternalIsoAliasTest {
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
        assertEquals("video/mp2t", externalVideoMimeType(java.net.URI(alias).path))
    }
}
