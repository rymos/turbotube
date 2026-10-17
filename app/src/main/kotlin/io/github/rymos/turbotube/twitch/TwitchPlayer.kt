@file:androidx.annotation.OptIn(UnstableApi::class)

package io.github.rymos.turbotube.twitch

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import io.github.rymos.turbotube.core.twitch.PlaylistCleaner
import io.github.rymos.turbotube.core.twitch.TwitchApi
import io.github.rymos.turbotube.core.twitch.TwitchStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays one live channel. Ads are kept out in two steps: [TwitchApi.stream] picks a way into the
 * stream that currently has none, and [AdFilterDataSource] cuts out what still gets through. When
 * a break starts anyway, the player tries once to switch to another way in.
 */
class TwitchPlayer(
    context: Context,
    private val api: TwitchApi,
    private val scope: CoroutineScope,
    private val login: String,
    private val onState: (State) -> Unit,
) {
    sealed interface State {
        data object Loading : State
        data object Playing : State
        data object AdBreak : State
        data class Failed(val message: String) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val cleaner = PlaylistCleaner()
    private var stream: TwitchStream? = null
    private var adBreak = false
    private var triedSwitchThisBreak = false
    private var errorsInRow = 0
    private var loadJob: Job? = null
    private var backJob: Job? = null

    private val dataSources = AdFilterDataSource.Factory(
        DefaultHttpDataSource.Factory()
            .setUserAgent(TwitchApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true),
        cleaner,
    ) { inBreak -> main.post { onAdBreak(inBreak) } }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = true
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorsInRow = 0
                    if (!adBreak) onState(State.Playing)
                }
            }

            override fun onPlayerError(error: PlaybackException) = recover(error)
        })
    }

    fun start() = load(skip = null)

    /** Back to the live edge, for example after the app was in the background. */
    fun resume() {
        exoPlayer.seekToDefaultPosition()
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pause() = exoPlayer.pause()

    /** Caps the picture at this many lines (1080, 720 …); null lets the player choose. */
    fun setMaxHeight(height: Int?) {
        val params = exoPlayer.trackSelectionParameters.buildUpon()
        if (height == null) params.clearVideoSizeConstraints() else params.setMaxVideoSize(Int.MAX_VALUE, height)
        exoPlayer.trackSelectionParameters = params.build()
    }

    fun release() {
        loadJob?.cancel()
        backJob?.cancel()
        main.removeCallbacksAndMessages(null)
        exoPlayer.release()
    }

    private fun load(skip: String?) {
        loadJob?.cancel()
        if (stream == null) onState(State.Loading)
        loadJob = scope.launch {
            val found = runCatching { api.stream(login, skip) }.getOrElse {
                if (stream == null) onState(State.Failed(it.message ?: "Der Stream lässt sich nicht laden."))
                return@launch
            }
            play(found)
        }
    }

    private fun play(found: TwitchStream) {
        stream = found
        val source = HlsMediaSource.Factory(dataSources)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(found.url))
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.play()
        watchForBetterStream()
    }

    private fun onAdBreak(inBreak: Boolean) {
        if (inBreak == adBreak) return
        adBreak = inBreak
        if (inBreak) {
            onState(State.AdBreak)
            if (!triedSwitchThisBreak) {
                triedSwitchThisBreak = true
                // Another way into the stream often has no ads during this break
                load(skip = stream?.playerType)
            }
        } else {
            triedSwitchThisBreak = false
            onState(if (exoPlayer.playbackState == Player.STATE_READY) State.Playing else State.Loading)
        }
    }

    /**
     * "autoplay" streams only come in low quality. While one plays, look now and then whether the
     * full-quality way in is free of ads again and go back to it.
     */
    private fun watchForBetterStream() {
        backJob?.cancel()
        val current = stream ?: return
        if (current.playerType == TwitchApi.PLAYER_TYPES.first()) return
        backJob = scope.launch {
            while (true) {
                delay(BETTER_STREAM_CHECK_MS)
                if (adBreak) continue
                val better = runCatching { api.stream(login) }.getOrNull() ?: continue
                if (better.playerType != current.playerType &&
                    TwitchApi.PLAYER_TYPES.indexOf(better.playerType) < TwitchApi.PLAYER_TYPES.indexOf(current.playerType)
                ) {
                    play(better)
                    return@launch
                }
            }
        }
    }

    /**
     * Live streams break off now and then; while ads are cut out the playlist also stands still,
     * which the player reports as an error. Start again at the live edge; after a few failures
     * in a row fetch a fresh stream address, and only give up after that fails too.
     */
    private fun recover(error: PlaybackException) {
        errorsInRow++
        if (errorsInRow > MAX_ERRORS) {
            onState(State.Failed("Der Stream bricht immer wieder ab (${error.errorCodeName})."))
            return
        }
        main.postDelayed({
            if (errorsInRow % REFRESH_EVERY == 0) {
                load(skip = null)
            } else {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
            }
        }, RETRY_DELAY_MS)
    }

    private companion object {
        const val MAX_ERRORS = 30
        const val REFRESH_EVERY = 4
        const val RETRY_DELAY_MS = 1_000L
        const val BETTER_STREAM_CHECK_MS = 60_000L
    }
}
