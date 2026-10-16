package io.github.rymos.turbotube.core.account

import io.github.rymos.turbotube.core.Account
import io.github.rymos.turbotube.core.Channel
import io.github.rymos.turbotube.core.Item
import io.github.rymos.turbotube.core.Page
import io.github.rymos.turbotube.core.Playlist
import io.github.rymos.turbotube.core.Video
import io.github.rymos.turbotube.core.youtubeChannelUrl
import io.github.rymos.turbotube.core.youtubePlaylistUrl
import io.github.rymos.turbotube.core.youtubeWatchUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * The logged-in part: the user's own subscriptions, playlists and liked videos from the
 * official YouTube Data API v3. Read-only; nothing is written back to the account.
 */
class YouTubeAccountApi(
    private val client: OkHttpClient,
    private val auth: GoogleDeviceAuth,
) {
    suspend fun account(): Account? {
        val json = get("channels") {
            addQueryParameter("part", "snippet")
            addQueryParameter("mine", "true")
        }
        val snippet = json.items().firstOrNull()?.obj("snippet") ?: return null
        return Account(snippet.optString("title").orEmpty(), snippet.thumbnail())
    }

    /** All subscribed channels, sorted by name. */
    suspend fun subscriptions(): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pageToken: String? = null
        do {
            val json = get("subscriptions") {
                addQueryParameter("part", "snippet")
                addQueryParameter("mine", "true")
                addQueryParameter("maxResults", "50")
                addQueryParameter("order", "alphabetical")
                pageToken?.let { addQueryParameter("pageToken", it) }
            }
            json.items().mapNotNullTo(channels) { item ->
                val snippet = item.obj("snippet") ?: return@mapNotNullTo null
                val id = snippet.obj("resourceId")?.optString("channelId")
                    ?: return@mapNotNullTo null
                Channel(id, youtubeChannelUrl(id), snippet.optString("title").orEmpty(),
                    snippet.thumbnail())
            }
            pageToken = json.optString("nextPageToken")
        } while (pageToken != null)
        return channels
    }

    suspend fun playlists(next: Any? = null): Page<Item> {
        val json = get("playlists") {
            addQueryParameter("part", "snippet,contentDetails")
            addQueryParameter("mine", "true")
            addQueryParameter("maxResults", "50")
            (next as? String)?.let { addQueryParameter("pageToken", it) }
        }
        val items = json.items().mapNotNull { item ->
            val id = item.optString("id") ?: return@mapNotNull null
            val snippet = item.obj("snippet") ?: return@mapNotNull null
            Playlist(
                id = id,
                url = youtubePlaylistUrl(id),
                title = snippet.optString("title").orEmpty(),
                thumbnailUrl = snippet.thumbnail(),
                videoCount = item.obj("contentDetails")?.long("itemCount") ?: -1,
            )
        }
        return Page(items, json.optString("nextPageToken"))
    }

    /** Videos of one of the user's playlists, including private ones. */
    suspend fun playlistVideos(playlistId: String, next: Any? = null): Page<Item> {
        val json = get("playlistItems") {
            addQueryParameter("part", "snippet,contentDetails")
            addQueryParameter("playlistId", playlistId)
            addQueryParameter("maxResults", "50")
            (next as? String)?.let { addQueryParameter("pageToken", it) }
        }
        val items = json.items().mapNotNull { item ->
            val snippet = item.obj("snippet") ?: return@mapNotNull null
            val id = snippet.obj("resourceId")?.optString("videoId") ?: return@mapNotNull null
            // Deleted and private videos of other people stay in playlists without a thumbnail
            val thumbnail = snippet.thumbnail() ?: return@mapNotNull null
            val channelId = snippet.optString("videoOwnerChannelId")
            Video(
                id = id,
                url = youtubeWatchUrl(id),
                title = snippet.optString("title").orEmpty(),
                channelName = snippet.optString("videoOwnerChannelTitle").orEmpty(),
                channelUrl = channelId?.let(::youtubeChannelUrl),
                thumbnailUrl = thumbnail,
                uploadedAtMillis = item.obj("contentDetails")?.optString("videoPublishedAt")
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
        return Page(items, json.optString("nextPageToken"))
    }

    suspend fun likedVideos(next: Any? = null): Page<Item> {
        val json = get("videos") {
            addQueryParameter("part", "snippet,contentDetails,statistics")
            addQueryParameter("myRating", "like")
            addQueryParameter("maxResults", "50")
            (next as? String)?.let { addQueryParameter("pageToken", it) }
        }
        val items = json.items().mapNotNull { item ->
            val id = item.optString("id") ?: return@mapNotNull null
            val snippet = item.obj("snippet") ?: return@mapNotNull null
            val channelId = snippet.optString("channelId")
            Video(
                id = id,
                url = youtubeWatchUrl(id),
                title = snippet.optString("title").orEmpty(),
                channelName = snippet.optString("channelTitle").orEmpty(),
                channelUrl = channelId?.let(::youtubeChannelUrl),
                thumbnailUrl = snippet.thumbnail(),
                durationSeconds = item.obj("contentDetails")?.optString("duration")
                    ?.let { runCatching { Duration.parse(it).seconds }.getOrNull() } ?: -1,
                viewCount = item.obj("statistics")?.optString("viewCount")?.toLongOrNull() ?: -1,
                uploadedAtMillis = snippet.optString("publishedAt")
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
        return Page(items, json.optString("nextPageToken"))
    }

    private suspend fun get(
        endpoint: String,
        query: HttpUrl.Builder.() -> Unit,
    ): JsonObject {
        val url = "$BASE_URL/$endpoint".toHttpUrl().newBuilder().apply(query).build()
        repeat(2) { attempt ->
            val token = auth.accessToken() ?: throw NotLoggedInException()
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    response.code to response.body.string()
                }
            }
            val (code, body) = result
            if (code == 401 && attempt == 0) {
                auth.invalidateAccessToken()
                return@repeat
            }
            if (code !in 200..299) {
                throw IOException("YouTube API $endpoint: HTTP $code ${apiError(body)}")
            }
            return Json.parseToJsonElement(body).jsonObject
        }
        throw NotLoggedInException()
    }

    private fun apiError(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject.obj("error")?.optString("message")
    }.getOrNull() ?: body.take(200)

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/youtube/v3"
    }
}

class NotLoggedInException : IOException("Nicht angemeldet")

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.items(): List<JsonObject> =
    (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Largest thumbnail the API offers, preferring sizes that look good on a TV. */
private fun JsonObject.thumbnail(): String? {
    val thumbnails = obj("thumbnails") ?: return null
    return listOf("maxres", "standard", "high", "medium", "default")
        .firstNotNullOfOrNull { thumbnails.obj(it)?.optString("url") }
}
