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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

class NotLoggedInException : IOException("Nicht angemeldet")

/**
 * The signed-in part of YouTube, read through the internal API of YouTube's TV app (the same one
 * SmartTube uses): recommendations, subscriptions, watch later, history, playlists, likes.
 *
 * Nothing here reports playback back to YouTube.
 */
class YouTubeTvApi(
    private val client: OkHttpClient,
    private val auth: YouTubeTvAuth,
) {
    suspend fun account(): Account? {
        val json = post("account/accounts_list", buildJsonObject {
            putJsonObject("accountReadMask") {
                put("returnOwner", true)
                put("returnBrandAccounts", true)
                put("returnPersonaAccounts", false)
            }
        })
        return TvParser.account(json)
    }

    /** Start page: what YouTube recommends for this account. */
    suspend fun home(next: Any? = null) = browse("FEwhat_to_watch", next)

    /** Newest videos of the subscribed channels. */
    suspend fun subscriptionFeed(next: Any? = null) = browse("FEsubscriptions", next)

    /** The subscribed channels, as YouTube lists them on the left of its subscriptions page. */
    suspend fun subscriptions(): List<Channel> = TvParser.channelTabs(request("FEsubscriptions", null))

    suspend fun watchLater(next: Any? = null) = browse("VLWL", next)

    suspend fun history(next: Any? = null) = browse("FEhistory", next)

    suspend fun likedVideos(next: Any? = null) = browse("VLLL", next)

    suspend fun playlists(next: Any? = null) = browse("FEplaylist_aggregation", next)

    suspend fun playlistVideos(playlistId: String, next: Any? = null) =
        browse(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId", next)

    private suspend fun browse(browseId: String, next: Any?): Page<Item> =
        TvParser.page(request(browseId, next as String?))

    private suspend fun request(browseId: String, continuation: String?): JsonObject =
        post("browse", buildJsonObject {
            if (continuation != null) put("continuation", continuation) else put("browseId", browseId)
        })

    private suspend fun post(endpoint: String, body: JsonObject, retry: Boolean = true): JsonObject {
        val token = auth.accessToken() ?: throw NotLoggedInException()
        val full = JsonObject(body + ("context" to context()))
        val (code, text) = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", TvClientInfo.USER_AGENT)
                .header("X-YouTube-Client-Name", CLIENT_NAME_ID)
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/tv")
                .post(full.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { it.code to it.body.string() }
        }
        if (code == 401 && retry) {
            auth.invalidateAccessToken()
            return post(endpoint, body, retry = false)
        }
        if (code !in 200..299) throw IOException("YouTube antwortet mit HTTP $code")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun context() = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "TVHTML5")
            put("clientVersion", CLIENT_VERSION)
            put("hl", Locale.getDefault().language.ifEmpty { "en" })
            Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let { put("gl", it) }
            put("userAgent", TvClientInfo.USER_AGENT)
            putJsonObject("tvAppInfo") {
                put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                put("zylonLeftNav", true)
            }
        }
    }

    companion object {
        const val CLIENT_VERSION = "7.20260901.15.00"
        private const val CLIENT_NAME_ID = "7"
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * Reads TV API answers without depending on their exact nesting, which YouTube changes often:
 * it walks the whole tree and picks up every video, channel and playlist tile it finds.
 */
internal object TvParser {

    fun page(json: JsonObject): Page<Item> {
        val items = LinkedHashMap<String, Item>()
        var next: String? = null
        walk(json) { key, obj ->
            when (key) {
                "tileRenderer" -> { tile(obj)?.let { items.putIfAbsent(it.url, it) }; false }
                "lockupViewModel" -> { lockup(obj)?.let { items.putIfAbsent(it.url, it) }; false }
                "nextContinuationData", "reloadContinuationData" -> {
                    obj.str("continuation")?.let { next = it }; false
                }
                "continuationCommand" -> { obj.str("token")?.let { next = it }; false }
                else -> true
            }
        }
        return Page(items.values.toList(), next.takeIf { items.isNotEmpty() })
    }

    /** Channels shown as tabs (left column) of the TV subscriptions page, plus channel tiles. */
    fun channelTabs(json: JsonObject): List<Channel> {
        val channels = LinkedHashMap<String, Channel>()
        walk(json) { key, obj ->
            when (key) {
                "tabRenderer" -> {
                    val id = obj.path("endpoint", "browseEndpoint")?.str("browseId")
                    if (id != null && id.startsWith("UC")) {
                        channels.putIfAbsent(id, Channel(id, youtubeChannelUrl(id), text(obj["title"]) ?: id, thumbnail(obj["thumbnail"])))
                    }
                    true
                }
                "tileRenderer" -> {
                    (tile(obj) as? Channel)?.let { channels.putIfAbsent(it.id, it) }
                    false
                }
                else -> true
            }
        }
        return channels.values.toList()
    }

    fun account(json: JsonObject): Account? {
        val accounts = mutableListOf<JsonObject>()
        walk(json) { key, obj ->
            if (key == "accountItem" || key == "accountItemRenderer") { accounts += obj; false } else true
        }
        val chosen = accounts.firstOrNull { (it["isSelected"] as? JsonPrimitive)?.booleanOrNull == true }
            ?: accounts.firstOrNull() ?: return null
        val name = text(chosen["accountName"]) ?: return null
        return Account(name, thumbnail(chosen["accountPhoto"]))
    }

    private fun tile(tile: JsonObject): Item? {
        val meta = tile.path("metadata", "tileMetadataRenderer")
        val title = text(meta?.get("title")) ?: return null
        val lines = (meta?.get("lines") as? JsonArray).orEmpty().map { line ->
            ((line as? JsonObject)?.path("lineRenderer")?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { text((it as? JsonObject)?.path("lineItemRenderer")?.get("text")) }
                .filter { it.isNotBlank() && it != "•" }
        }
        val header = tile.path("header", "tileHeaderRenderer")
        val thumb = thumbnail(header?.get("thumbnail"))
        val command = tile.path("onSelectCommand")
        val browseId = command?.path("browseEndpoint")?.str("browseId")
        val videoId = command?.path("watchEndpoint")?.str("videoId")
        return when (tile.str("contentType")) {
            "TILE_CONTENT_TYPE_CHANNEL" -> {
                val id = browseId ?: tile.str("contentId") ?: return null
                Channel(id, youtubeChannelUrl(id), title, thumb)
            }
            "TILE_CONTENT_TYPE_PLAYLIST" -> {
                val id = (browseId ?: tile.str("contentId") ?: return null).removePrefix("VL")
                Playlist(id, youtubePlaylistUrl(id), title, thumb, countIn(lines.flatten()))
            }
            else -> {
                val id = videoId ?: tile.str("contentId") ?: return null
                var duration: String? = null
                var live = false
                walk(header ?: JsonObject(emptyMap())) { key, obj ->
                    if (key == "thumbnailOverlayTimeStatusRenderer") {
                        if (obj.str("style") == "LIVE") live = true else duration = text(obj["text"])
                        false
                    } else true
                }
                video(id, title, lines, thumb, duration, live, channelIn(tile))
            }
        }
    }

    private fun lockup(lockup: JsonObject): Item? {
        val meta = lockup.path("metadata", "lockupMetadataViewModel")
        val title = text(meta?.get("title")) ?: return null
        val rows = (meta?.path("metadata", "contentMetadataViewModel")?.get("metadataRows") as? JsonArray).orEmpty()
            .map { row ->
                ((row as? JsonObject)?.get("metadataParts") as? JsonArray).orEmpty()
                    .mapNotNull { text((it as? JsonObject)?.get("text")) }
                    .filter { it.isNotBlank() }
            }
        var thumb: String? = null
        var badge: String? = null
        walk(lockup["contentImage"] ?: JsonObject(emptyMap())) { key, obj ->
            when (key) {
                "image" -> { if (thumb == null) thumb = thumbnail(obj); false }
                "thumbnailBadgeViewModel" -> { if (badge == null) badge = obj.str("text"); false }
                else -> true
            }
        }
        val id = lockup.str("contentId") ?: return null
        val badgeText = badge
        return when (lockup.str("contentType")) {
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST" -> {
                val list = id.removePrefix("VL")
                Playlist(list, youtubePlaylistUrl(list), title, thumb, countIn(rows.flatten() + listOfNotNull(badgeText)))
            }
            "LOCKUP_CONTENT_TYPE_CHANNEL" -> Channel(id, youtubeChannelUrl(id), title, thumb)
            else -> {
                val live = badgeText != null && ':' !in badgeText && "LIVE" in badgeText.uppercase()
                video(id, title, rows, thumb, badgeText.takeUnless { live }, live, channelIn(lockup))
            }
        }
    }

    private fun video(
        id: String,
        title: String,
        lines: List<List<String>>,
        thumb: String?,
        duration: String?,
        live: Boolean,
        channelId: String?,
    ): Video {
        val first = lines.firstOrNull().orEmpty()
        val rest = lines.drop(1).flatten()
        return Video(
            id = id,
            url = youtubeWatchUrl(id),
            title = title,
            channelName = first.firstOrNull() ?: "",
            channelUrl = channelId?.let(::youtubeChannelUrl),
            thumbnailUrl = thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSeconds = duration?.let(::parseDuration) ?: -1,
            uploadedText = (first.drop(1) + rest).joinToString(" · ").ifEmpty { null },
            isLive = live,
        )
    }

    /** The uploader's channel id, from any link to a channel inside an item (menu, byline). */
    private fun channelIn(item: JsonObject): String? {
        var found: String? = null
        walk(item) { key, obj ->
            if (found != null) return@walk false
            if (key == "browseEndpoint") {
                obj.str("browseId")?.takeIf { it.startsWith("UC") }?.let { found = it }
            }
            true
        }
        return found
    }

    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        return parts.fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: return null) }
    }

    private fun countIn(texts: List<String>): Long =
        texts.firstNotNullOfOrNull { Regex("""^(\d[\d.,]*)\s""").find(it)?.groupValues?.get(1) }
            ?.filter(Char::isDigit)?.toLongOrNull() ?: -1

    /** Text from simpleText, runs or a view model's content. */
    fun text(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        obj.str("simpleText")?.let { return it }
        obj.str("content")?.let { return it }
        (obj["runs"] as? JsonArray)?.let { runs ->
            return runs.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("").ifEmpty { null }
        }
        return null
    }

    /** The largest image of a thumbnails/sources list, wherever it sits below [element]. */
    fun thumbnail(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val list = (obj["thumbnails"] ?: obj["sources"]) as? JsonArray
            ?: return (obj["image"] as? JsonObject)?.let(::thumbnail)
        val best = list.mapNotNull { it as? JsonObject }
            .maxByOrNull { (it["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 } ?: return null
        val url = best.str("url") ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /**
     * Visits every object in the tree in document order. [visit] gets the key the object sits
     * under and returns whether to descend into it.
     */
    private fun walk(element: JsonElement, key: String = "", visit: (String, JsonObject) -> Boolean) {
        when (element) {
            is JsonObject -> {
                if (key.isNotEmpty() && !visit(key, element)) return
                element.forEach { (k, v) -> walk(v, k, visit) }
            }
            is JsonArray -> element.forEach { walk(it, key, visit) }
            else -> Unit
        }
    }

    private fun JsonObject.path(vararg keys: String): JsonObject? =
        keys.fold(this as JsonObject?) { obj, k -> obj?.get(k) as? JsonObject }

    private fun JsonObject.str(key: String) = optString(key)
}
