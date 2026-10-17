package io.github.rymos.turbotube.core.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What the TV shows while waiting: the code to type in and the page to type it on. */
data class DeviceLogin(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

/** The signed-in account. */
data class TwitchAccount(val token: String, val login: String, val userId: String)

/**
 * Signs in with a code, like Twitch's own TV app: the TV shows a code, the viewer enters it on
 * twitch.tv/activate on their phone. The password never touches the TV. The token is the one
 * Twitch's Android app gets, without extra permissions.
 */
class TwitchAuth(private val http: OkHttpClient) {
    /** Twitch expects the same device id on every call of one sign-in, like its app sends. */
    private val deviceId = List(32) { "0123456789abcdef".random() }.joinToString("")

    suspend fun start(): DeviceLogin {
        val answer = when (val reply = post("https://id.twitch.tv/oauth2/device", "client_id" to CLIENT_ID, "scopes" to "")) {
            is Reply.Ok -> reply.body
            is Reply.Refused -> throw TwitchException("Twitch lehnt die Anmeldung gerade ab: ${reply.reason}")
        }
        return DeviceLogin(
            userCode = answer.string("user_code") ?: throw TwitchException("Twitch hat keinen Code geschickt."),
            verificationUri = answer.string("verification_uri") ?: "https://www.twitch.tv/activate",
            deviceCode = answer.string("device_code") ?: throw TwitchException("Twitch hat keinen Code geschickt."),
            intervalSeconds = answer["interval"]?.jsonPrimitive?.intOrNull ?: 5,
            expiresInSeconds = answer["expires_in"]?.jsonPrimitive?.intOrNull ?: 1800,
        )
    }

    /** Waits until the code was entered; null when it expired first. */
    suspend fun await(login: DeviceLogin): TwitchAccount? {
        val deadline = System.currentTimeMillis() + login.expiresInSeconds * 1000L
        var interval = login.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val reply = post(
                "https://id.twitch.tv/oauth2/token",
                "client_id" to CLIENT_ID,
                "device_code" to login.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
            val answer = when (reply) {
                is Reply.Ok -> reply.body
                is Reply.Refused -> when {
                    "slow_down" in reply.reason -> {
                        interval += 5
                        continue
                    }
                    "expired" in reply.reason || "invalid device code" in reply.reason -> return null
                    else -> continue // usually "authorization_pending": not entered yet
                }
            }
            val token = answer.string("access_token") ?: continue
            return validate(token) ?: throw TwitchException("Twitch hat die Anmeldung nicht bestätigt.")
        }
        return null
    }

    /** The account behind a stored token, or null if Twitch no longer accepts it. */
    suspend fun validate(token: String): TwitchAccount? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://id.twitch.tv/oauth2/validate")
            .header("Authorization", "OAuth $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) return@withContext null
            if (!response.isSuccessful) throw TwitchException("Twitch ist gerade nicht erreichbar (Fehler ${response.code}).")
            val answer = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return@withContext null
            TwitchAccount(
                token = token,
                login = answer.string("login") ?: return@withContext null,
                userId = answer.string("user_id").orEmpty(),
            )
        }
    }

    private sealed interface Reply {
        data class Ok(val body: JsonObject) : Reply

        /** A 4xx answer; [reason] is Twitch's own message and the status, e.g. "authorization_pending (400)". */
        data class Refused(val reason: String) : Reply
    }

    private suspend fun post(url: String, vararg fields: Pair<String, String>): Reply = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        // The headers Twitch's own app sends; without them the sign-in can be refused
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "de-DE")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Client-Id", CLIENT_ID)
            .header("Origin", "https://www.twitch.tv")
            .header("Referer", "https://www.twitch.tv/")
            .header("User-Agent", USER_AGENT)
            .header("X-Device-Id", deviceId)
            .post(form)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                val body = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                when {
                    response.isSuccessful && body != null -> Reply.Ok(body)
                    response.code in 400..499 -> {
                        val message = body?.string("message") ?: body?.string("error") ?: text.take(120).ifBlank { "ohne Begründung" }
                        Reply.Refused("$message (${response.code})")
                    }
                    else -> throw TwitchException("Twitch ist gerade nicht erreichbar (Fehler ${response.code}).")
                }
            }
        } catch (e: TwitchException) {
            throw e
        } catch (e: IOException) {
            throw TwitchException("Keine Verbindung zu Twitch: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    companion object {
        /** Twitch's Android app; tokens are bound to the client that asked for them. */
        const val CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
        const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 14; Google TV Streamer Build/UTT3.240625.001) tv.twitch.android.app/25.3.0/2503006"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
