package io.github.rymos.turbotube.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Where the refresh token lives between app starts. The app keeps it in private storage. */
interface TokenStore {
    var refreshToken: String?
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
 * Google sign-in for TVs, using the OAuth 2.0 device flow
 * (https://developers.google.com/identity/protocols/oauth2/limited-input-device).
 * The user opens google.com/device on another device, types the code shown on the TV and
 * signs in there; the TV never sees the password.
 */
class GoogleDeviceAuth(
    private val client: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val store: TokenStore,
) {
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAtMillis = 0L

    val isConfigured get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val isLoggedIn get() = store.refreshToken != null

    suspend fun requestDeviceCode(): DeviceCode {
        val json = post(
            DEVICE_CODE_URL,
            FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", SCOPE)
                .build(),
        )
        return DeviceCode(
            userCode = json.string("user_code"),
            verificationUrl = json.optString("verification_url") ?: "https://www.google.com/device",
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
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < code.expiresAtMillis) {
            delay(interval * 1000)
            val json = postRaw(
                TOKEN_URL,
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("device_code", code.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build(),
            )
            when (json.optString("error")) {
                null -> {
                    storeTokens(json)
                    return true
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
        val json = postRaw(
            TOKEN_URL,
            FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build(),
        )
        if (json.optString("error") == "invalid_grant") {
            // Token was revoked or expired: the user has to log in again
            logoutLocally()
            return null
        }
        if (json.optString("error") != null) {
            throw AuthException(json.optString("error_description") ?: json.toString())
        }
        storeTokens(json)
        accessToken
    }

    /** Called when an API call answers 401, so the next call fetches a fresh token. */
    fun invalidateAccessToken() {
        accessToken = null
    }

    suspend fun logout() {
        val token = store.refreshToken
        logoutLocally()
        if (token != null) {
            runCatching {
                postRaw(REVOKE_URL, FormBody.Builder().add("token", token).build())
            }
        }
    }

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

    private suspend fun post(url: String, body: FormBody): JsonObject {
        val json = postRaw(url, body)
        json.optString("error")?.let {
            throw AuthException(json.optString("error_description") ?: it)
        }
        return json
    }

    private suspend fun postRaw(url: String, body: FormBody): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                runCatching { Json.parseToJsonElement(text).jsonObject }
                    .getOrElse { throw AuthException("HTTP ${response.code}: $text") }
            }
        }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}

internal fun JsonObject.optString(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

internal fun JsonObject.string(key: String): String =
    optString(key) ?: throw AuthException("Antwort ohne \"$key\"")

internal fun JsonObject.long(key: String): Long? =
    this[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
