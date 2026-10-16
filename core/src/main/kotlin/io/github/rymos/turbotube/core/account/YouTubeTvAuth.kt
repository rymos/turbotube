package io.github.rymos.turbotube.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** What the app keeps between starts. The app stores it in its private storage. */
interface TokenStore {
    var refreshToken: String?
    var clientId: String?
    var clientSecret: String?
    var deviceId: String?
}

/** What the TV shows while the user confirms the login on their phone or computer. */
data class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val intervalSeconds: Long,
    val expiresAtMillis: Long,
)

class AuthException(message: String) : IOException(message)

/**
 * Sign-in the way YouTube's own TV app does it (and SmartTube): the TV shows a code, the user
 * enters it at youtube.com/activate. The OAuth client of the official TV app is read from
 * youtube.com/tv at login time, so nobody has to register an own Google Cloud client.
 */
class YouTubeTvAuth(
    private val client: OkHttpClient,
    private val store: TokenStore,
) {
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAtMillis = 0L

    val isLoggedIn get() = store.refreshToken != null

    suspend fun requestDeviceCode(): DeviceCode {
        val (clientId, _) = credentials(refresh = true)
        val deviceId = store.deviceId ?: UUID.randomUUID().toString().also { store.deviceId = it }
        val json = post(
            DEVICE_CODE_URL,
            buildJsonObject {
                put("client_id", clientId)
                put("device_id", deviceId)
                put("model_name", MODEL_NAME)
                put("scope", SCOPE)
            },
        )
        json.optString("error")?.let { throw AuthException(json.optString("error_description") ?: it) }
        return DeviceCode(
            userCode = json.string("user_code"),
            verificationUrl = json.optString("verification_url") ?: "https://www.youtube.com/activate",
            deviceCode = json.string("device_code"),
            intervalSeconds = json.long("interval") ?: 5,
            expiresAtMillis = System.currentTimeMillis() + (json.long("expires_in") ?: 1800) * 1000,
        )
    }

    /**
     * Polls until the user has confirmed the code. Returns false when the code expired or the
     * user declined; the caller can then ask for a new code.
     */
    suspend fun awaitLogin(code: DeviceCode): Boolean {
        val (clientId, clientSecret) = credentials()
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < code.expiresAtMillis) {
            delay(interval * 1000)
            val json = post(
                TOKEN_URL,
                buildJsonObject {
                    put("code", code.deviceCode)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                    put("grant_type", DEVICE_GRANT)
                },
            )
            when (json.optString("error")) {
                null -> {
                    storeTokens(json)
                    return json.optString("refresh_token") != null
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied", "expired_token" -> return false
                else -> throw AuthException(json.optString("error_description") ?: json.toString())
            }
        }
        return false
    }

    /** A valid access token, refreshed when needed, or null when nobody is logged in. */
    suspend fun accessToken(): String? = mutex.withLock {
        val refresh = store.refreshToken ?: return null
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAtMillis - 60_000) {
            return cached
        }
        val (clientId, clientSecret) = credentials()
        val json = post(
            TOKEN_URL,
            buildJsonObject {
                put("refresh_token", refresh)
                put("client_id", clientId)
                put("client_secret", clientSecret)
                put("grant_type", "refresh_token")
            },
        )
        when (json.optString("error")) {
            null -> Unit
            // Revoked or expired: the user has to log in again
            "invalid_grant" -> {
                logoutLocally()
                return null
            }
            else -> throw AuthException(json.optString("error_description") ?: json.toString())
        }
        storeTokens(json)
        accessToken
    }

    /** Called when an API call answers 401, so the next call fetches a fresh token. */
    fun invalidateAccessToken() {
        accessToken = null
    }

    fun logout() = logoutLocally()

    private fun logoutLocally() {
        store.refreshToken = null
        accessToken = null
        accessTokenExpiresAtMillis = 0
    }

    private fun storeTokens(json: JsonObject) {
        accessToken = json.string("access_token")
        accessTokenExpiresAtMillis =
            System.currentTimeMillis() + (json.long("expires_in") ?: 3600) * 1000
        json.optString("refresh_token")?.let { store.refreshToken = it }
    }

    /**
     * Client id and secret of the official TV app. Tokens only refresh with the client that
     * issued them, so they are kept together with the refresh token and only re-read on login.
     */
    private suspend fun credentials(refresh: Boolean = false): Pair<String, String> {
        val id = store.clientId
        val secret = store.clientSecret
        if (!refresh && id != null && secret != null) return id to secret
        val fresh = runCatching { TvClientInfo.load(client).credentials }.getOrNull()
            ?: (FALLBACK_CLIENT_ID to FALLBACK_CLIENT_SECRET)
        store.clientId = fresh.first
        store.clientSecret = fresh.second
        return fresh
    }

    private suspend fun post(url: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", TvClientInfo.USER_AGENT)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/tv")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                runCatching { Json.parseToJsonElement(text).jsonObject }
                    .getOrElse { throw AuthException("HTTP ${response.code}: ${text.take(200)}") }
            }
        }

    companion object {
        private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        private const val SCOPE =
            "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
        private const val MODEL_NAME = "ytlr::"

        // Used only when youtube.com/tv can't be read; this is the long-known TV app client
        private const val FALLBACK_CLIENT_ID =
            "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val FALLBACK_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"

        private val JSON = "application/json".toMediaType()
    }
}

internal fun JsonObject.optString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.string(key: String): String =
    optString(key) ?: throw AuthException("Antwort ohne \"$key\"")

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }

