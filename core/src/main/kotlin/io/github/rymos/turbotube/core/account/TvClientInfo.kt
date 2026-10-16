package io.github.rymos.turbotube.core.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * What youtube.com/tv tells a TV: the OAuth client of the official TV app (inside its base.js)
 * and a visitor id. Read the same way SmartTube does it.
 */
class TvClientInfo(
    val credentials: Pair<String, String>?,
    val visitorData: String?,
) {
    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; Fire OS 6.0) Cobalt/22.lts.3.306369-gold " +
                "(unlike Gecko) v8/8.8.278.8-jit gles Starboard/13, Amazon_ATV_mediatek8695_2019/NS6294 " +
                "(Amazon, AFTMM, Wireless) com.amazon.firetv.youtube/22.3.r2.v66.0"

        private const val TV_URL = "https://www.youtube.com/tv"

        private val BASE_JS = listOf(
            Regex("""id="base-js" src="(.*?)""""),
            Regex("""\.src = '(.*?m=base)'"""),
            Regex("""\.src = '(.*?)'; .\.id = 'base-js'"""),
        )
        private val CLIENT = Regex(""""?clientId"?:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""")
        private val VISITOR = Regex(""""visitorData":"([^"]+)"""")

        suspend fun load(client: OkHttpClient): TvClientInfo = withContext(Dispatchers.IO) {
            val page = get(client, TV_URL)
            val visitor = parseVisitorData(page)
            val jsUrl = parseBaseJsUrl(page) ?: return@withContext TvClientInfo(null, visitor)
            val credentials = parseCredentials(get(client, jsUrl))
                ?: legacyUrl(jsUrl)?.let { url -> runCatching { parseCredentials(get(client, url)) }.getOrNull() }
            TvClientInfo(credentials, visitor)
        }

        internal fun parseBaseJsUrl(page: String): String? {
            val path = BASE_JS.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) } ?: return null
            return when {
                path.startsWith("http") -> path
                path.startsWith("//") -> "https:$path"
                else -> "https://www.youtube.com" + (if (path.startsWith("/")) path else "/$path")
            }
        }

        internal fun parseCredentials(js: String): Pair<String, String>? =
            CLIENT.find(js)?.let { it.groupValues[1] to it.groupValues[2] }

        internal fun parseVisitorData(page: String): String? = VISITOR.find(page)?.groupValues?.get(1)

        private fun legacyUrl(url: String): String? {
            val legacy = url.replace("/dg=0/", "/exm=base/ed=1/").replace("/m=base", "/m=main")
            return legacy.takeIf { it != url }
        }

        private fun get(client: OkHttpClient, url: String): String {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} für $url")
                response.body.string()
            }
        }
    }
}
