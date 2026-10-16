package io.github.rymos.turbotube.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A newer build on GitHub. */
data class AppUpdate(
    val buildNumber: Int,
    val name: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Looks at the latest GitHub release. Every build of main is released as `build-<n>`, where n is
 * the same number the APK carries as its versionCode.
 */
object UpdateChecker {
    const val REPO = "rymos/turbotube"
    private const val APK_NAME = "turbotube.apk"

    /** The latest release if it is newer than [currentBuild], otherwise null. */
    suspend fun check(client: OkHttpClient, currentBuild: Int, repo: String = REPO): AppUpdate? {
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub antwortet mit HTTP ${response.code}")
                response.body.string()
            }
        }
        return parse(Json.parseToJsonElement(text).jsonObject)?.takeIf { it.buildNumber > currentBuild }
    }

    internal fun parse(release: JsonObject): AppUpdate? {
        val tag = release.text("tag_name") ?: return null
        val build = Regex("""^build-(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val apk = (release["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("name") == APK_NAME } ?: return null
        return AppUpdate(
            buildNumber = build,
            name = release.text("name") ?: tag,
            apkUrl = apk.text("browser_download_url") ?: return null,
            sizeBytes = (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
        )
    }

    private fun JsonObject.text(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
