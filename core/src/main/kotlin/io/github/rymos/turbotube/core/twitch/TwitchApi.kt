package io.github.rymos.turbotube.core.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.random.Random

/** A channel, live or not. The stream fields are only set while it is live. */
data class TwitchChannel(
    val login: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val live: Boolean = false,
    val title: String = "",
    val viewers: Int = 0,
    val game: String = "",
    val previewUrl: String? = null,
    /** When the stream went live, ISO 8601, for the "live for 2:13" line. */
    val startedAt: String? = null,
)

data class TwitchCategory(
    val name: String,
    val displayName: String,
    val viewers: Int,
    val boxArtUrl: String?,
)

/** The playlist URL of a live stream and which kind of player Twitch thinks is asking. */
data class TwitchStream(val url: String, val playerType: String)

class TwitchException(message: String) : IOException(message)

/**
 * Twitch's own web API (gql.twitch.tv), anonymous: no account, no login. It answers with the
 * public client id of Twitch's website, like every other independent Twitch player does.
 */
class TwitchApi(private val http: OkHttpClient) {
    // A fresh one per app start; Twitch only needs it to tell sessions apart
    private val deviceId = (1..32).map { DEVICE_ID_CHARS[Random.nextInt(DEVICE_ID_CHARS.length)] }.joinToString("")

    suspend fun topStreams(): List<TwitchChannel> {
        val direct = runCatching {
            query("query { streams(first: $PAGE) { edges { node { $STREAM_FIELDS } } } }")
                .path("streams", "edges").array().mapNotNull { stream(it.path("node")) }
        }
        direct.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Twitch sometimes answers the overall list with nothing; the biggest categories always
        // have streams, and together they are the popular streams too
        val fromCategories = coroutineScope {
            topCategories().take(FALLBACK_CATEGORIES)
                .map { async { runCatching { categoryStreams(it.name) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }.distinctBy { it.login }.sortedByDescending { it.viewers }.take(PAGE)
        if (fromCategories.isEmpty()) direct.exceptionOrNull()?.let { throw it }
        return fromCategories
    }

    suspend fun topCategories(): List<TwitchCategory> {
        val data = query(
            "query { games(first: $PAGE) { edges { node { name displayName viewersCount " +
                "boxArtURL(width: 285, height: 380) } } } }",
        )
        return data.path("games", "edges").array().mapNotNull {
            val node = it.path("node")
            val name = node.string("name") ?: return@mapNotNull null
            TwitchCategory(
                name = name,
                displayName = node.string("displayName") ?: name,
                viewers = node.int("viewersCount"),
                boxArtUrl = node.string("boxArtURL"),
            )
        }
    }

    suspend fun categoryStreams(name: String): List<TwitchChannel> {
        val data = query(
            "query(\$name: String!) { game(name: \$name) { streams(first: $PAGE) { edges { node { " +
                "$STREAM_FIELDS } } } } }",
            "name" to name,
        )
        return data.path("game", "streams", "edges").array().mapNotNull { stream(it.path("node")) }
    }

    /** The given channels in the given order, live ones with their stream. */
    suspend fun channels(logins: List<String>): List<TwitchChannel> {
        if (logins.isEmpty()) return emptyList()
        val data = query(
            "query(\$logins: [String!]) { users(logins: \$logins) { $USER_FIELDS } }",
            "logins" to logins,
        )
        return data.path("users").array().mapNotNull(::user)
    }

    /** Channels whose name matches; an exact channel name always comes first. */
    suspend fun search(text: String): List<TwitchChannel> {
        val login = text.trim().lowercase().replace(" ", "")
        val exact = if (LOGIN.matches(login)) channels(listOf(login)) else emptyList()
        // Twitch's search query is not documented and changes now and then; the exact match
        // keeps working without it
        val found = runCatching {
            query(
                "query(\$q: String!) { searchFor(userQuery: \$q, platform: \"web\") { channels { " +
                    "edges { item { ... on User { $USER_FIELDS } } } } } }",
                "q" to text.trim(),
            ).path("searchFor", "channels", "edges").array().mapNotNull { user(it.path("item")) }
        }.getOrDefault(emptyList())
        return (exact + found).distinctBy { it.login }
    }

    /**
     * Finds a way into the stream that carries no ads right now. Twitch hands out ads by the kind
     * of player that asks; the ones tried here get none or few. If every one carries ads, the
     * first is used and the [PlaylistCleaner] cuts them out.
     */
    suspend fun stream(login: String, skip: String? = null): TwitchStream {
        var first: TwitchStream? = null
        for (type in PLAYER_TYPES) {
            if (type == skip) continue
            val url = runCatching { playlistUrl(login, type) }.getOrNull() ?: continue
            val stream = TwitchStream(url, type)
            if (first == null) first = stream
            if (runCatching { !hasAds(url) }.getOrDefault(false)) return stream
        }
        return first ?: throw TwitchException("$login ist gerade nicht live.")
    }

    /** Whether the stream behind this master playlist is showing ads at the moment. */
    suspend fun hasAds(masterUrl: String): Boolean {
        val master = get(masterUrl)
        val variant = master.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false
        val playlist = masterUrl.toHttpUrl().resolve(variant) ?: return false
        return get(playlist.toString()).contains("stitched")
    }

    private suspend fun playlistUrl(login: String, playerType: String): String? {
        val body = buildJsonObject {
            put("operationName", "PlaybackAccessToken")
            putJsonObject("variables") {
                put("isLive", true)
                put("login", login)
                put("isVod", false)
                put("vodID", "")
                put("playerType", playerType)
                put("platform", if (playerType == "autoplay") "android" else "web")
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", PLAYBACK_TOKEN_HASH)
                }
            }
        }
        val token = post(body).path("data", "streamPlaybackAccessToken")
        val value = token.string("value") ?: return null
        val signature = token.string("signature") ?: return null
        return "https://usher.ttvnw.net/api/v2/channel/hls/${login.lowercase()}.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("platform", "web")
            .addQueryParameter("p", Random.nextInt(999_999).toString())
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("fast_bread", "true")
            .addQueryParameter("sig", signature)
            .addQueryParameter("token", value)
            .build().toString()
    }

    /**
     * The channels the signed-in account follows, live ones with their stream. [token] comes from
     * [TwitchAuth]; it belongs to Twitch's TV app. Twitch's web API sometimes hides the account
     * from that app, so Twitch's public API (Helix) with the same token is the second way.
     */
    suspend fun followedChannels(token: String): List<TwitchChannel> {
        val viaGql = runCatching { followedViaGql(token) }
        viaGql.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val viaHelix = runCatching { followedViaHelix(token) }
        // An empty list from the web API is no proof: it may just hide the account. Helix decides.
        return viaHelix.getOrElse { throw it }
    }

    private suspend fun followedViaGql(token: String): List<TwitchChannel> {
        val result = ArrayList<TwitchChannel>()
        var after: String? = null
        repeat(MAX_FOLLOW_PAGES) {
            val me = query(
                "query(\$after: Cursor) { currentUser { follows(first: 100, after: \$after) { " +
                    "edges { cursor node { $USER_FIELDS } } pageInfo { hasNextPage } } } }",
                *listOfNotNull(after?.let { "after" to it }).toTypedArray(),
                token = token,
            ).path("currentUser")
            if (me !is JsonObject) throw TwitchException("Twitch gibt das Konto nicht heraus.")
            val page = me.path("follows")
            val edges = page.path("edges").array()
            result += edges.mapNotNull { user(it.path("node")) }
            val more = (page.path("pageInfo").path("hasNextPage") as? JsonPrimitive)?.contentOrNull == "true"
            after = edges.lastOrNull()?.string("cursor")
            if (!more || after == null) return result
        }
        return result
    }

    /** Needs the "user:read:follows" permission, which sign-ins since this version ask for. */
    private suspend fun followedViaHelix(token: String): List<TwitchChannel> {
        val check = getJson("https://id.twitch.tv/oauth2/validate", "Authorization" to "OAuth $token")
        val userId = check.string("user_id") ?: throw TwitchException("Twitch kennt die Anmeldung nicht mehr.")
        val clientId = check.string("client_id") ?: TwitchAuth.CLIENT_ID
        val scopes = check.path("scopes").array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (TwitchAuth.FOLLOWS_SCOPE !in scopes) {
            throw TwitchException("Bitte unter „Konto“ einmal abmelden und neu verbinden, dann darf Turbotube die Liste lesen.")
        }
        val logins = ArrayList<String>()
        var after: String? = null
        var pages = 0
        do {
            val url = "https://api.twitch.tv/helix/channels/followed".toHttpUrl().newBuilder()
                .addQueryParameter("user_id", userId)
                .addQueryParameter("first", "100")
                .apply { after?.let { addQueryParameter("after", it) } }
                .build().toString()
            val page = getJson(url, "Client-Id" to clientId, "Authorization" to "Bearer $token")
            logins += page.path("data").array().mapNotNull { it.string("broadcaster_login") }
            after = page.path("pagination").string("cursor")
        } while (after != null && ++pages < MAX_FOLLOW_PAGES)
        // Helix only knows the names; the rest (live, title, picture) comes like for saved channels
        return coroutineScope {
            logins.distinct().chunked(100).map { async { channels(it) } }.awaitAll().flatten()
        }
    }

    private suspend fun query(text: String, vararg variables: Pair<String, Any>, token: String? = null): JsonElement {
        val body = buildJsonObject {
            put("query", text)
            putJsonObject("variables") {
                for ((key, value) in variables) {
                    when (value) {
                        is String -> put(key, value)
                        is List<*> -> putJsonArray(key) { value.forEach { add(JsonPrimitive(it.toString())) } }
                        else -> put(key, value.toString())
                    }
                }
            }
        }
        val answer = post(body, token)
        val error = answer.path("errors").array().firstOrNull()?.string("message")
        val data = answer.path("data")
        // Partial answers carry data with every asked-for field null; that is a failure too
        if (data !is JsonObject || (error != null && data.values.all { it is JsonNull })) {
            throw TwitchException("Twitch antwortet nicht wie erwartet${error?.let { " ($it)" }.orEmpty()}.")
        }
        return data
    }

    private suspend fun post(body: JsonObject, token: String? = null): JsonElement = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("X-Device-Id", deviceId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (token == null) {
            builder.header("Client-ID", CLIENT_ID).header("User-Agent", USER_AGENT)
        } else {
            builder.header("Client-ID", TwitchAuth.CLIENT_ID)
                .header("User-Agent", TwitchAuth.USER_AGENT)
                .header("Authorization", "OAuth $token")
                .header("Origin", TwitchAuth.ORIGIN)
                .header("Referer", "${TwitchAuth.ORIGIN}/")
        }
        val request = builder.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful && text.isBlank()) {
                throw TwitchException("Twitch ist gerade nicht erreichbar (Fehler ${response.code}).")
            }
            runCatching { json.parseToJsonElement(text) }
                .getOrElse { throw TwitchException("Twitch antwortet nicht wie erwartet.") }
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TwitchException("Stream nicht verfügbar (Fehler ${response.code}).")
            response.body.string()
        }
    }

    private suspend fun getJson(url: String, vararg headers: Pair<String, String>): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val answer = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (!response.isSuccessful) {
                val reason = answer?.string("message") ?: "Fehler ${response.code}"
                throw TwitchException("Twitch gibt die Liste nicht heraus ($reason).")
            }
            answer ?: throw TwitchException("Twitch antwortet nicht wie erwartet.")
        }
    }

    private fun stream(node: JsonElement): TwitchChannel? {
        val broadcaster = node.path("broadcaster")
        val login = broadcaster.string("login") ?: return null
        return TwitchChannel(
            login = login,
            displayName = broadcaster.string("displayName") ?: login,
            avatarUrl = broadcaster.string("profileImageURL"),
            live = true,
            title = node.string("title").orEmpty(),
            viewers = node.int("viewersCount"),
            game = node.path("game").string("displayName").orEmpty(),
            previewUrl = node.string("previewImageURL"),
            startedAt = node.string("createdAt"),
        )
    }

    private fun user(node: JsonElement): TwitchChannel? {
        val login = node.string("login") ?: return null
        val live = node.path("stream")
        return TwitchChannel(
            login = login,
            displayName = node.string("displayName") ?: login,
            avatarUrl = node.string("profileImageURL"),
            live = live is JsonObject,
            title = live.string("title").orEmpty(),
            viewers = live.int("viewersCount"),
            game = live.path("game").string("displayName").orEmpty(),
            previewUrl = live.string("previewImageURL"),
            startedAt = live.string("createdAt"),
        )
    }

    companion object {
        private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val PLAYBACK_TOKEN_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9"

        /** Twitch refuses the stream token to requests that don't look like a browser. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** In this order; "autoplay" mostly comes without ads, but only in low quality. */
        val PLAYER_TYPES = listOf("embed", "popout", "autoplay")

        private const val PAGE = 40
        private const val DEVICE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val LOGIN = Regex("[a-z0-9_]{2,25}")
        private val json = Json { ignoreUnknownKeys = true }

        private const val FALLBACK_CATEGORIES = 6
        private const val MAX_FOLLOW_PAGES = 5
        private const val STREAM_FIELDS = "title viewersCount createdAt previewImageURL(width: 640, height: 360) " +
            "game { displayName } broadcaster { login displayName profileImageURL(width: 150) }"
        private const val USER_FIELDS = "login displayName profileImageURL(width: 150) stream { title " +
            "viewersCount createdAt previewImageURL(width: 640, height: 360) game { displayName } }"
    }
}

private fun JsonElement?.path(vararg keys: String): JsonElement {
    var current: JsonElement = this ?: JsonNull
    for (key in keys) current = (current as? JsonObject)?.get(key) ?: JsonNull
    return current
}

private fun JsonElement.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

private fun JsonElement.string(key: String): String? = (path(key) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.int(key: String): Int = (path(key) as? JsonPrimitive)?.intOrNull ?: 0
