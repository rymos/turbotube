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
 * The script travels inside the APK: the workflow fetches this exact version from npm, checks it
 * against [SHA256] and writes it to `app/src/main/assets`. So the first start needs no network and
 * no third-party CDN. The copy on our own release is the fallback, npm the last resort — all three
 * from the same hash. Raising the version means updating VERSION, SHA256 here and the hash in
 * `.github/workflows/build.yml`.
 */
object TizenTubeScript {
    const val VERSION = "1.15.1"
    private const val SHA256 = "2fabc5b3f6961a7b0c9cd7b06e114134f1fe11d124960e2d13790fd716cbb7c5"
    private const val FILE_NAME = "tizentube-" + VERSION + ".js"

    /** Our own release first, so a start without a bundled script does not depend on a CDN. */
    private val urls = listOf(
        "https://github.com/rymos/turbotube/releases/latest/download/$FILE_NAME",
        "https://cdn.jsdelivr.net/npm/@foxreis/tizentube@$VERSION/dist/userScript.js",
        "https://unpkg.com/@foxreis/tizentube@$VERSION/dist/userScript.js",
    )

    /** The script: local copy, bundled copy or freshly downloaded; null when nothing works. */
    suspend fun load(context: Context, http: OkHttpClient): String? = withContext(Dispatchers.IO) {
        fromCache(context) ?: fromAssets(context) ?: fromNetwork(context, http)
    }

    private fun fromCache(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }
            ?.readBytes()?.takeIf(::matches)?.decodeToString()

    /** A local build may ship no asset; then this is simply skipped. */
    private fun fromAssets(context: Context): String? = runCatching {
        context.assets.open(FILE_NAME).use { it.readBytes() }.takeIf(::matches)?.decodeToString()
    }.getOrNull()

    private fun fromNetwork(context: Context, http: OkHttpClient): String? {
        for (url in urls) {
            val bytes = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            }.getOrNull() ?: continue
            if (!matches(bytes)) continue
            context.filesDir.listFiles { file -> file.name.startsWith("tizentube-") }?.forEach { it.delete() }
            File(context.filesDir, FILE_NAME).writeBytes(bytes)
            return bytes.decodeToString()
        }
        return null
    }

    private fun matches(bytes: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256
}
