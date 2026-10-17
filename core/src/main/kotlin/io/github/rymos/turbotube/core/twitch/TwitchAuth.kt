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
    suspend fun start(): DeviceLogin {
        val answer = post("https://id.twitch.tv/oauth2/device", "client_id" to CLIENT_ID, "scopes" to "")
            ?: throw TwitchException("Twitch-Anmeldung gerade nicht möglich.")
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
        while (System.currentTimeMillis() < deadline) {
            delay(login.intervalSeconds * 1000L)
            val answer = post(
                "https://id.twitch.tv/oauth2/token",
                "client_id" to CLIENT_ID,
                "device_code" to login.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ) ?: continue // not entered yet
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

    /** Null for 4xx answers, which during sign-in mean "not yet". */
    private suspend fun post(url: String, vararg fields: Pair<String, String>): JsonObject? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("Client-Id", CLIENT_ID)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code in 400..499) return@withContext null
            if (!response.isSuccessful) throw IOException("Twitch-Anmeldung: Fehler ${response.code}")
            json.parseToJsonElement(response.body.string()) as? JsonObject
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
