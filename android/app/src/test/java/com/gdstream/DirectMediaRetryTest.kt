package com.gdstream

import androidx.media3.common.C
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DirectMediaRetryTest {
    @Test fun progressivePlayerHookRecoversAnExplicitRetryAfter503AndResumesAtTheRequestedByte() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            java.net.ServerSocket(0).use { server ->
                server.soTimeout = 5000
                val requests = pool.submit<List<List<String>>> {
                    List(2) { index ->
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                            val reply = if (index == 0) "503 Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            else "206 Partial Content\r\nContent-Range: bytes 89000000000-89000000003/90000000000\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA"
                            socket.getOutputStream().write("HTTP/1.1 $reply".toByteArray())
                            headers
                        }
                    }
                }
                val url = "http://127.0.0.1:${server.localPort}/Movie%20Name.mp4?sig=a%2Fb+c"
                val hook = requireNotNull(VideoPlayerModule.archiveHttp(url, emptyMap(), 3))
                assertTrue(hook.second is DirectMediaLoadPolicy)
                val spec = DataSpec.Builder().setUri(url).setPosition(89_000_000_000L).setLength(4).build()
                val bytes = DataSourceInputStream(hook.first.createDataSource(), spec).use { it.readBytes() }
                assertEquals("DATA", bytes.decodeToString())
                val received = requests.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(received.all { "Range: bytes=89000000000-89000000003" in it && it.first().contains("Movie%20Name.mp4?sig=a%2Fb+c") })
                assertFalse("Cache-Control: no-cache" in received.first())
                assertTrue("Cache-Control: no-cache" in received.last())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun revalidatesTheSameRangeOnEachRetryAndMedia3SurfacesTheLastStatus() {
        val url = "https://worker/0:/Big%20Movie.mkv?sig=a%2Fb+c"
        val requests = mutableListOf<Request>()
        val delays = mutableListOf<Long>()
        for (code in listOf(404, 408, 429, 500, 502, 503, 504, 403, 206)) {
            requests.clear()
            delays.clear()
            val client = OkHttpClient.Builder()
                .addInterceptor(DirectMediaRetryInterceptor { _, delay -> delays += delay })
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                        .message("media").body("".toResponseBody()).build()
                }.build()
            val get = Request.Builder().url(url).header("Range", "bytes=89000000000-").build()
            client.newCall(get).execute().use { assertEquals(code, it.code) }
            assertEquals(if (code == 403 || code == 206) emptyList() else listOf(400L, 1000L, 2200L, 4000L), delays)
            assertNull(requests.first().header("Cache-Control"))
            assertEquals(delays.size + 1, requests.size)
            assertTrue(requests.drop(1).all { it.header("Cache-Control") == "no-cache" })
            assertTrue(requests.all { it.header("Range") == "bytes=89000000000-" && it.url.toString() == url })
        }
        val spec = DataSpec.Builder().setUri(url).build()
        val failure = HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), spec, byteArrayOf())
        val info = LoadErrorInfo(LoadEventInfo(0, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), failure, 1)
        assertEquals(C.TIME_UNSET, DirectMediaLoadPolicy().getRetryDelayMsFor(info))
    }
}
