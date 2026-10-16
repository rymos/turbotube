package io.github.rymos.turbotube.core

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class YouTubeStreamInterceptorTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(range: Boolean, rn: Boolean) = OkHttpClient.Builder()
        .addInterceptor(YouTubeStreamInterceptor(range, rn))
        .build()

    @Test
    fun movesRangeIntoUrlAndReportsPartialContent() {
        server.enqueue(MockResponse.Builder().code(200).body("abc").build())
        val response = client(range = true, rn = true).newCall(
            Request.Builder()
                .url(server.url("/videoplayback?id=1"))
                .header("Range", "bytes=100-199")
                .build(),
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("100-199", recorded.url.queryParameter("range"))
        assertEquals("0", recorded.url.queryParameter("rn"))
        assertNull(recorded.headers["Range"])
        assertEquals(206, response.code)
    }

    @Test
    fun keepsRangeHeaderWhenNotUsingParameter() {
        server.enqueue(MockResponse.Builder().code(206).body("abc").build())
        client(range = false, rn = false).newCall(
            Request.Builder()
                .url(server.url("/videoplayback?id=1"))
                .header("Range", "bytes=0-")
                .build(),
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("bytes=0-", recorded.headers["Range"])
        assertNull(recorded.url.queryParameter("range"))
        assertNull(recorded.url.queryParameter("rn"))
    }

    @Test
    fun leavesOtherRequestsAlone() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        client(range = true, rn = true).newCall(
            Request.Builder().url(server.url("/api/manifest")).build(),
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertNull(recorded.url.queryParameter("rn"))
    }
}
