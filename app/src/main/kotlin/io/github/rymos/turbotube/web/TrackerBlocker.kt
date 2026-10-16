package io.github.rymos.turbotube.web

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Answers requests to ad and statistics addresses with an empty response, so they never leave
 * the TV. Watch time and playback reports stay allowed: YouTube needs them for the history and
 * for "continue watching".
 */
object TrackerBlocker {
    private val blockedHosts = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "imasdk.googleapis.com",
        "adservice.google.com",
    )

    private val blockedPaths = listOf(
        "/api/stats/ads",
        "/api/stats/qoe",
        "/api/stats/atr",
        "/api/stats/delayplay",
        "/ptracking",
        "/pagead/",
        "/youtubei/v1/log_event",
        "/log_event",
        "/csi_204",
        "/generate_204",
        "/error_204",
    )

    fun isBlocked(url: Uri): Boolean {
        val host = url.host ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        val isYouTube = host.endsWith("youtube.com") || host.endsWith("googlevideo.com")
        val path = url.path ?: return false
        return isYouTube && blockedPaths.any { path.startsWith(it) }
    }

    fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )
}
