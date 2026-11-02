package io.github.rymos.turbotube.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchApiPreviewTest {
    private val streams = """
        {"data":{"game":{"streams":{"edges":[{"node":{
          "title":"Test","viewersCount":12,"createdAt":"2026-11-02T10:00:00Z",
          "previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_abc-640x360.jpg",
          "game":{"displayName":"Spiel"},
          "broadcaster":{"login":"abc","displayName":"Abc",
            "profileImageURL":"https://static-cdn.jtvnw.net/jtv_user_pictures/abc-150x150.png"}
        }}]}}}}
    """.trimIndent()

    private val users = """
        {"data":{"users":[{
          "login":"abc","displayName":"Abc",
          "profileImageURL":"https://static-cdn.jtvnw.net/jtv_user_pictures/abc-150x150.png",
          "stream":{"title":"Test","viewersCount":12,"createdAt":"2026-11-02T10:00:00Z",
            "previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_abc-640x360.jpg",
            "game":{"displayName":"Spiel"}}
        }]}}
    """.trimIndent()

    private fun api(answer: String) = TwitchApi(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(answer.toResponseBody("application/json".toMediaType()))
                .build()
        }).build(),
    )

    private fun assertMinuteBucket(url: String?) {
        assertNotNull(url)
        assertTrue(url, Regex("""^https://static-cdn\.jtvnw\.net/previews-ttv/live_user_abc-640x360\.jpg\?t=\d+$""").matches(url!!))
    }

    @Test
    fun `stream preview gets a minute bucket`() = runBlocking {
        val channel = api(streams).categoryStreams("spiel").single()
        assertMinuteBucket(channel.previewUrl)
        assertEquals("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-150x150.png", channel.avatarUrl)
        assertFalse(channel.avatarUrl!!.contains("?"))
    }

    @Test
    fun `user preview gets a minute bucket`() = runBlocking {
        val channel = api(users).channels(listOf("abc")).single()
        assertMinuteBucket(channel.previewUrl)
        assertEquals("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-150x150.png", channel.avatarUrl)
        assertFalse(channel.avatarUrl!!.contains("?"))
    }
}
