package io.github.rymos.turbotube.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A newer build on GitHub. */
data class AppUpdate(
    val buildNumber: Int,
    val name: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Release text (the commit list); empty for older releases that were published without one. */
    val notes: String? = null,
)

/**
 * Looks at the recent GitHub releases. Every build of main is released as `build-<n>`, where n is
 * the same number the APK carries as its versionCode.
 *
 * "latest" on GitHub means newest by creation date, not highest build number: a re-run, an
 * out-of-order publish or a hand-made `v1.0` release ends up in front of it, and then the app
 * would silently see no update at all. So a page of releases is read and the highest build number
 * wins; releases without a `build-<n>` tag are ignored.
 */
object UpdateChecker {
    const val REPO = "rymos/turbotube"
    private const val APK_NAME = "turbotube.apk"

    /** Enough releases to cover the recent builds; older ones cannot be updates any more. */
    private const val PAGE_SIZE = 20

    private val BUILD_TAG = Regex("""^build-(\d+)$""")

    /** The newest update if it is newer than [currentBuild], otherwise null. */
    suspend fun check(client: OkHttpClient, currentBuild: Int, repo: String = REPO): AppUpdate? {
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases?per_page=$PAGE_SIZE")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub antwortet mit HTTP ${response.code}")
                response.body.string()
            }
        }
        return newest(Json.parseToJsonElement(text).jsonArray, newerThan = currentBuild)
    }

    /** The highest build out of a release list that is newer than [newerThan]. */
    internal fun newest(releases: JsonArray, newerThan: Int): AppUpdate? =
        parseAll(releases)
            .filter { it.buildNumber > newerThan }
            .maxByOrNull { it.buildNumber }

    /** Every usable update in a release list; drafts and releases without a build tag are dropped. */
    internal fun parseAll(releases: JsonArray): List<AppUpdate> = releases
        .mapNotNull { it as? JsonObject }
        .filterNot { it.flag("draft") }
        .mapNotNull(::parse)

    internal fun parse(release: JsonObject): AppUpdate? {
        val tag = release.text("tag_name") ?: return null
        val build = BUILD_TAG.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val apk = (release["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("name") == APK_NAME } ?: return null
        return AppUpdate(
            buildNumber = build,
            name = release.text("name") ?: tag,
            apkUrl = apk.text("browser_download_url") ?: return null,
            sizeBytes = (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
            notes = release.text("body")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.text(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** GitHub sends `"draft": true` as a JSON boolean, not as a string. */
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
