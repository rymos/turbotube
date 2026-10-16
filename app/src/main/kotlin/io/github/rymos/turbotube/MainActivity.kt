package io.github.rymos.turbotube

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.rymos.turbotube.ui.LocalContainer
import io.github.rymos.turbotube.ui.Navigator
import io.github.rymos.turbotube.ui.Screen
import io.github.rymos.turbotube.ui.TurbotubeRoot
import io.github.rymos.turbotube.ui.TurbotubeTheme

class MainActivity : ComponentActivity() {
    private val navigator = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TurbotubeApp).container
        if (savedInstanceState == null) openLink(intent)
        setContent {
            TurbotubeTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    TurbotubeRoot(remember { navigator })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** A youtube.com or youtu.be link sent to the TV starts playing right away. */
    private fun openLink(intent: Intent?) {
        val url = intent?.dataString ?: return
        val isVideo = runCatching { io.github.rymos.turbotube.core.YouTube.videoId(url) }.isSuccess
        if (isVideo) navigator.push(Screen.Player(url))
    }
}
