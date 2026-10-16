package io.github.rymos.turbotube.core

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/** Picks which of YouTube's many separate video and audio streams to play. */
object StreamSelector {
    /**
     * Best video-only stream up to [maxHeight]. Higher resolution wins, then higher frame rate,
     * then the more efficient codec among those the device can decode in hardware.
     *
     * @param canDecode tells whether the device decodes this codec at this size and frame rate
     */
    fun bestVideo(
        streams: List<VideoStream>,
        maxHeight: Int,
        canDecode: (mimeType: String, width: Int, height: Int, fps: Int) -> Boolean,
    ): VideoStream? = streams
        .filter { it.isVideoOnly && it.deliveryMethod != DeliveryMethod.TORRENT }
        .filter { it.height in 1..maxHeight }
        .filter { !isHdr(it.codec) }
        .filter { s -> mimeOf(s.codec)?.let { canDecode(it, s.width, s.height, s.fps) } == true }
        .maxWithOrNull(
            compareBy<VideoStream> { it.height }
                .thenBy { it.fps }
                .thenBy { codecRank(it.codec) }
                .thenBy { it.bitrate },
        )

    /** Best audio stream, preferring the original language over dubbed or described tracks. */
    fun bestAudio(streams: List<AudioStream>): AudioStream? = streams
        .filter { it.deliveryMethod != DeliveryMethod.TORRENT }
        .maxWithOrNull(
            compareBy<AudioStream> {
                when (it.audioTrackType) {
                    null, AudioTrackType.ORIGINAL -> 2
                    AudioTrackType.DUBBED -> 1
                    else -> 0
                }
            }.thenBy { if (it.codec?.startsWith("opus") == true) 1 else 0 }
                .thenBy { it.averageBitrate },
        )

    /** Highest resolution muxed stream (video with sound), the fallback for old videos. */
    fun bestMuxed(streams: List<VideoStream>, maxHeight: Int): VideoStream? = streams
        .filter { !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .filter { it.height <= maxHeight }
        .maxByOrNull { it.height }
        ?: streams.filter { !it.isVideoOnly }.minByOrNull { it.height }

    /**
     * 10-bit HDR variants (VP9 profile 2, 10-bit AV1). Skipped for now: they need HDR output
     * handling, and the SDR versions of the same resolutions are always offered too.
     */
    fun isHdr(codec: String?): Boolean = codec != null && (
        codec.startsWith("vp09.02") ||
            (codec.startsWith("av01") && codec.split('.').getOrNull(3) == "10")
        )

    fun mimeOf(codec: String?): String? = when {
        codec == null -> null
        codec.startsWith("av01") -> "video/av01"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "video/x-vnd.on2.vp9"
        codec.startsWith("avc1") -> "video/avc"
        codec.startsWith("hev1") || codec.startsWith("hvc1") -> "video/hevc"
        else -> null
    }

    private fun codecRank(codec: String?): Int = when (mimeOf(codec)) {
        "video/av01" -> 3
        "video/x-vnd.on2.vp9" -> 2
        "video/hevc" -> 1
        else -> 0
    }
}
