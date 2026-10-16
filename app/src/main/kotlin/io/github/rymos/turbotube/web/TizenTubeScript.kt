package io.github.rymos.turbotube.web

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The TizenTube user script (https://github.com/reisxd/TizenTube, GPL-3.0) that removes ads and
 * adds SponsorBlock and its settings menu to YouTube's TV interface.
 *
 * It is not bundled: the app loads it from npm, but only this exact version, and only if its
 * SHA-256 matches. Raising the version means updating both values.
 */
object TizenTubeScript {
    const val VERSION = "1.15.1"
    private const val SHA256 = "2fabc5b3f6961a7b0c9cd7b06e114134f1fe11d124960e2d13790fd716cbb7c5"

    private val urls = listOf(
        "https://cdn.jsdelivr.net/npm/@foxreis/tizentube@$VERSION/dist/userScript.js",
        "https://unpkg.com/@foxreis/tizentube@$VERSION/dist/userScript.js",
    )

    /** The script, from the local copy or freshly downloaded; null when neither works. */
    suspend fun load(context: Context, http: OkHttpClient): String? = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "tizentube-$VERSION.js")
        cache.takeIf { it.exists() }?.readBytes()?.takeIf(::matches)?.let { return@withContext it.decodeToString() }
        for (url in urls) {
            val bytes = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            }.getOrNull() ?: continue
            if (!matches(bytes)) continue
            context.filesDir.listFiles { file -> file.name.startsWith("tizentube-") }?.forEach { it.delete() }
            cache.writeBytes(bytes)
            return@withContext bytes.decodeToString()
        }
        null
    }

    private fun matches(bytes: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256
}
