package io.github.rymos.turbotube.core.twitch

import java.time.Instant

/**
 * Twitch cuts its ads straight into the live HLS playlist ("stitched ads"): during a break the
 * playlist carries ad segments instead of the stream. This removes them before the player sees
 * them.
 *
 * The player numbers segments by their position after EXT-X-MEDIA-SEQUENCE, so segments can only
 * be cut from the front or the back of the list, never from the middle:
 * - Break over (stream segments after the last ad): everything up to the last ad is cut from the
 *   front and the sequence numbers are moved on. The player jumps to the stream.
 * - Break running (the list ends in ads): the last clean playlist is handed out again, so the
 *   player sees nothing new and waits until the stream is back.
 */
class PlaylistCleaner {
    /** [inAdBreak]: ads are running right now and the player is held back. */
    data class Result(val text: String, val hadAds: Boolean, val inAdBreak: Boolean)

    private val lastClean = HashMap<String, String>()

    /** [key] identifies the playlist (one per quality); the URL without its query works. */
    @Synchronized
    fun clean(key: String, playlist: String): Result {
        if (!playlist.contains(AD_SIGNIFIER) && !playlist.contains("Amazon")) {
            lastClean[key] = playlist
            return Result(playlist, hadAds = false, inAdBreak = false)
        }

        val parsed = parse(playlist)
        val segments = parsed.segments
        val lastAd = segments.indexOfLast { it.ad }
        if (lastAd < 0) {
            // Ad markers announced, no ad segment yet: keep the stream, drop what points at ads
            val text = build(parsed, parsed.mediaSequence, parsed.discontinuitySequence, segments)
            lastClean[key] = text
            return Result(text, hadAds = false, inAdBreak = false)
        }

        if (lastAd < segments.lastIndex) {
            val dropped = segments.subList(0, lastAd + 1)
            val text = build(
                parsed,
                mediaSequence = parsed.mediaSequence + dropped.size,
                discontinuitySequence = parsed.discontinuitySequence + dropped.count { it.discontinuity },
                segments = segments.subList(lastAd + 1, segments.size),
            )
            lastClean[key] = text
            return Result(text, hadAds = true, inAdBreak = false)
        }

        lastClean[key]?.let { return Result(it, hadAds = true, inAdBreak = true) }
        // Ads from the very start: hand out the stream before them, or an empty list to wait on
        val before = segments.takeWhile { !it.ad }
        val text = build(parsed, parsed.mediaSequence, parsed.discontinuitySequence, before)
        lastClean[key] = text
        return Result(text, hadAds = true, inAdBreak = true)
    }

    private class Segment(val lines: List<String>, val ad: Boolean, val discontinuity: Boolean)

    private class Parsed(
        val header: List<String>,
        val segments: List<Segment>,
        val tail: List<String>,
        val mediaSequence: Long,
        val discontinuitySequence: Long,
    )

    private class AdRange(val start: Instant, val end: Instant?)

    private fun parse(playlist: String): Parsed {
        val lines = playlist.replace("\r", "").split("\n")
        val header = ArrayList<String>()
        val segments = ArrayList<Segment>()
        val pending = ArrayList<String>()
        val adRanges = ArrayList<AdRange>()
        var mediaSequence = 0L
        var discontinuitySequence = 0L
        var inSegments = false

        for (line in lines) {
            when {
                line.startsWith(MEDIA_SEQUENCE) ->
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith(DISCONTINUITY_SEQUENCE) ->
                    discontinuitySequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-DATERANGE") -> {
                    // Ad markers are dropped everywhere; the player has no use for them
                    val range = adRange(line)
                    if (range != null) adRanges += range else if (inSegments) pending += line else header += line
                }
                line.startsWith("#EXT-X-TWITCH-PREFETCH") -> Unit // may point at ad segments
                SEGMENT_TAGS.any { line.startsWith(it) } -> {
                    inSegments = true
                    pending += line
                }
                line.isNotBlank() && !line.startsWith("#") && inSegments -> {
                    pending += line
                    segments += segment(pending.toList(), adRanges)
                    pending.clear()
                }
                inSegments -> pending += line
                else -> header += line
            }
        }
        val tail = pending.filter { it.isNotBlank() }
        return Parsed(
            header.filter { it.isNotBlank() },
            segments,
            tail,
            mediaSequence,
            discontinuitySequence,
        )
    }

    private fun segment(lines: List<String>, adRanges: List<AdRange>): Segment {
        val title = lines.firstOrNull { it.startsWith("#EXTINF") }?.substringAfter(',', "")
        val date = lines.firstOrNull { it.startsWith("#EXT-X-PROGRAM-DATE-TIME") }
            ?.substringAfter(':')?.let(::instant)
        val ad = (!title.isNullOrEmpty() && !title.contains("live")) ||
            title?.contains("Amazon") == true ||
            (date != null && adRanges.any { date >= it.start && (it.end == null || date < it.end) })
        return Segment(lines, ad, lines.any { it == "#EXT-X-DISCONTINUITY" })
    }

    /** An EXT-X-DATERANGE that marks an ad, or null for any other date range. */
    private fun adRange(line: String): AdRange? {
        val attrs = ATTRIBUTE.findAll(line.substringAfter(':')).associate {
            it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
        }
        val isAd = attrs["CLASS"] == "twitch-stitched-ad" || attrs["ID"]?.startsWith("stitched-ad-") == true
        if (!isAd) return null
        // Unreadable dates still count as an ad marker; the segment titles catch those ads
        val start = attrs["START-DATE"]?.let(::instant) ?: return AdRange(Instant.MAX, null)
        val end = attrs["DURATION"]?.toDoubleOrNull()?.let { start.plusMillis((it * 1000).toLong()) }
            ?: attrs["END-DATE"]?.let(::instant)
        return AdRange(start, end)
    }

    private fun instant(text: String): Instant? = runCatching { Instant.parse(text.trim()) }.getOrNull()

    private fun build(parsed: Parsed, mediaSequence: Long, discontinuitySequence: Long, segments: List<Segment>): String {
        val out = StringBuilder()
        var wroteSequence = false
        for (line in parsed.header) {
            out.append(line).append('\n')
            // The sequence numbers follow the target duration, where they stood before
            if (!wroteSequence && line.startsWith("#EXT-X-TARGETDURATION")) {
                out.append(MEDIA_SEQUENCE).append(mediaSequence).append('\n')
                out.append(DISCONTINUITY_SEQUENCE).append(discontinuitySequence).append('\n')
                wroteSequence = true
            }
        }
        if (!wroteSequence) {
            out.append(MEDIA_SEQUENCE).append(mediaSequence).append('\n')
            out.append(DISCONTINUITY_SEQUENCE).append(discontinuitySequence).append('\n')
        }
        segments.forEach { segment -> segment.lines.forEach { out.append(it).append('\n') } }
        parsed.tail.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    private companion object {
        const val AD_SIGNIFIER = "stitched"
        const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        const val DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:"
        val SEGMENT_TAGS = listOf("#EXTINF", "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-BYTERANGE")
        val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }
}
