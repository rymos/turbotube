package io.github.rymos.turbotube.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.rymos.turbotube.core.StreamSelector
import io.github.rymos.turbotube.core.YouTubeStreamInterceptor
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap

/** Turns NewPipe's stream lists into something ExoPlayer can play. */
@OptIn(UnstableApi::class)
class YouTubeMediaSources(private val client: OkHttpClient) {
    private fun factory(rangeAsParameter: Boolean, requestNumber: Boolean) =
        OkHttpDataSource.Factory(
            client.newBuilder()
                .addInterceptor(YouTubeStreamInterceptor(rangeAsParameter, requestNumber))
                .build(),
        )

    private val dashData = factory(rangeAsParameter = true, requestNumber = true)
    private val progressiveData = factory(rangeAsParameter = false, requestNumber = true)
    private val hlsData = factory(rangeAsParameter = false, requestNumber = false)

    /** What was picked, for the info line in the player. */
    data class Choice(val source: MediaSource, val description: String)

    fun create(info: StreamInfo, maxHeight: Int): Choice {
        val metadata = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .build()

        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (isLive && info.hlsUrl.isNotEmpty()) {
            return Choice(hls(info.hlsUrl, metadata), "Live")
        }

        val video = StreamSelector.bestVideo(info.videoOnlyStreams, maxHeight) { m, w, h, fps ->
            hardwareDecodes(m, w, h, fps)
        } ?: StreamSelector.bestVideo(info.videoOnlyStreams, minOf(maxHeight, 1080)) { m, w, h, fps ->
            anyDecoder(m, w, h, fps)
        }
        val audio = StreamSelector.bestAudio(info.audioStreams)
        if (video != null && audio != null) {
            val source = MergingMediaSource(
                true,
                single(video, info, metadata),
                single(audio, info, metadata),
            )
            val codec = StreamSelector.mimeOf(video.codec)?.substringAfterLast('/')
                ?.removePrefix("x-vnd.on2.")?.uppercase()
            return Choice(source, "${video.height}p${if (video.fps > 30) video.fps else ""} $codec")
        }

        StreamSelector.bestMuxed(info.videoStreams, maxHeight)?.let { muxed ->
            return Choice(single(muxed, info, metadata), "${muxed.height}p")
        }
        if (info.hlsUrl.isNotEmpty()) return Choice(hls(info.hlsUrl, metadata), "HLS")
        throw IllegalStateException("Für dieses Video wurde kein abspielbarer Stream gefunden.")
    }

    private fun single(stream: Stream, info: StreamInfo, metadata: MediaMetadata): MediaSource {
        val item = MediaItem.Builder()
            .setUri(stream.content)
            .setMediaMetadata(metadata)
            .build()
        val itag = stream.itagItem
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> if (itag != null) {
                runCatching {
                    dash(
                        YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                            stream.content, itag, info.duration,
                        ),
                        item,
                    )
                }.getOrElse {
                    ProgressiveMediaSource.Factory(progressiveData).createMediaSource(item)
                }
            } else {
                ProgressiveMediaSource.Factory(progressiveData).createMediaSource(item)
            }
            DeliveryMethod.DASH -> dash(
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
                    stream.content, requireNotNull(itag), info.duration,
                ),
                item,
            )
            DeliveryMethod.HLS -> HlsMediaSource.Factory(hlsData).createMediaSource(item)
            else -> throw IllegalStateException("Nicht unterstützt: ${stream.deliveryMethod}")
        }
    }

    private fun dash(manifest: String, item: MediaItem): MediaSource {
        val parsed = DashManifestParser().parse(
            Uri.parse(item.localConfiguration?.uri?.toString().orEmpty()),
            manifest.byteInputStream(),
        )
        return DashMediaSource.Factory(dashData).createMediaSource(parsed, item)
    }

    private fun hls(url: String, metadata: MediaMetadata): MediaSource =
        HlsMediaSource.Factory(hlsData).createMediaSource(
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(metadata)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build(),
                )
                .build(),
        )

    companion object {
        private val decoders: List<MediaCodecInfo> by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }
        private val cache = ConcurrentHashMap<String, Boolean>()

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
            }

        private fun supports(
            mime: String, width: Int, height: Int, fps: Int, hardwareOnly: Boolean,
        ): Boolean = cache.getOrPut("$mime $width $height $fps $hardwareOnly") {
            decoders.any { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (!hardwareOnly || isHardware(info)) &&
                    runCatching {
                        val video = info.getCapabilitiesForType(mime).videoCapabilities
                        if (fps > 0) {
                            video?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
                        } else {
                            video?.isSizeSupported(width, height) == true
                        }
                    }.getOrDefault(false)
            }
        }

        fun hardwareDecodes(mime: String, width: Int, height: Int, fps: Int) =
            supports(mime, width, height, fps, hardwareOnly = true)

        fun anyDecoder(mime: String, width: Int, height: Int, fps: Int) =
            supports(mime, width, height, fps, hardwareOnly = false)
    }
}
