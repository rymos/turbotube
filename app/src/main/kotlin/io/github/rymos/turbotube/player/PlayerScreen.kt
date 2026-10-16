package io.github.rymos.turbotube.player

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.rymos.turbotube.core.YouTube
import io.github.rymos.turbotube.ui.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(videoUrl: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    var status by remember { mutableStateOf<String?>("Lädt …") }

    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status = null
                if (state == Player.STATE_ENDED) onFinished()
            }

            override fun onPlayerError(error: PlaybackException) {
                status = "Wiedergabe fehlgeschlagen: ${error.errorCodeName}\n${error.message.orEmpty()}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(videoUrl) {
        try {
            val info = YouTube.stream(videoUrl)
            val maxHeight = container.settings.maxQuality.takeIf { it > 0 } ?: displayHeight(context)
            val choice = withContext(Dispatchers.Default) {
                container.mediaSources.create(info, maxHeight)
            }
            status = "${info.name}\n${choice.description}"
            player.setMediaSource(choice.source)
            if (info.startPosition > 0) player.seekTo(info.startPosition * 1000)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status = "Video konnte nicht geladen werden.\n${e.message ?: e.javaClass.simpleName}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowSubtitleButton(false)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = 3000
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() }
                }
            },
        )
        status?.let {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), MaterialTheme.shapes.medium)
                    .padding(24.dp),
            ) {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

/** Height of the largest mode the connected TV supports, e.g. 2160 for a 4K TV. */
private fun displayHeight(context: Context): Int {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
    val modes = display?.supportedModes.orEmpty()
    val height = modes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 1080
    return height.coerceAtLeast(720)
}
