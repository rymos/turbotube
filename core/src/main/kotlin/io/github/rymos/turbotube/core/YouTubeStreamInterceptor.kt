package io.github.rymos.turbotube.core

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes ExoPlayer's requests to YouTube's media servers look like the ones the official
 * clients send, the same way NewPipe's YoutubeHttpDataSource does: a POST with a tiny body,
 * the request counter `rn`, and the byte range as `range` query parameter instead of a header.
 */
class YouTubeStreamInterceptor(
    private val rangeAsParameter: Boolean,
    private val addRequestNumber: Boolean,
) : Interceptor {
    private val requestNumber = AtomicLong()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/videoplayback")) {
            return chain.proceed(request)
        }

        val url = request.url.newBuilder()
        if (addRequestNumber && request.url.queryParameter("rn") == null) {
            url.addQueryParameter("rn", requestNumber.getAndIncrement().toString())
        }
        val rangeHeader = request.header("Range")
        val movedRange = rangeAsParameter && rangeHeader != null &&
            request.url.queryParameter("range") == null
        if (movedRange) {
            url.addQueryParameter("range", rangeHeader.removePrefix("bytes="))
        }

        val original = request.url.toString()
        val builder = request.newBuilder()
            .url(url.build())
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(original)) {
                    YoutubeParsingHelper.getVisionOsUserAgent(null)
                } else {
                    NewPipeDownloader.USER_AGENT
                },
            )
            .post(POST_BODY.toRequestBody())
        if (movedRange) builder.removeHeader("Range")
        if (YoutubeParsingHelper.isWebStreamingUrl(original)) {
            builder.header("Origin", YOUTUBE)
                .header("Referer", "$YOUTUBE/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }

        val response = chain.proceed(builder.build())
        // With the range in the URL YouTube answers 200 instead of 206. ExoPlayer would then
        // skip the requested offset a second time, so report it as the partial content it is.
        return if (movedRange && response.code == 200) {
            response.newBuilder().code(206).build()
        } else {
            response
        }
    }

    private companion object {
        const val YOUTUBE = "https://www.youtube.com"
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
