package io.github.rymos.turbotube.core

/** Anything that can show up in a result grid. */
sealed interface Item {
    val url: String
}

/** A video as shown in a grid or list. [url] is a normal youtube.com watch URL. */
data class Video(
    val id: String,
    override val url: String,
    val title: String,
    val channelName: String,
    val channelUrl: String?,
    val thumbnailUrl: String?,
    /** Duration in seconds, or -1 when unknown (for example in RSS feeds). */
    val durationSeconds: Long = -1,
    val viewCount: Long = -1,
    val uploadedText: String? = null,
    /** Upload time in epoch milliseconds, used to sort the subscription feed. */
    val uploadedAtMillis: Long? = null,
    val isLive: Boolean = false,
) : Item

data class Channel(
    val id: String,
    override val url: String,
    val name: String,
    val avatarUrl: String?,
) : Item

data class Playlist(
    val id: String,
    override val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val videoCount: Long = -1,
) : Item

/** One page of results; pass [next] back to the same call to load more. */
data class Page<out T>(
    val items: List<T>,
    val next: Any? = null,
)

data class Account(
    val name: String,
    val avatarUrl: String?,
)

fun youtubeWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
fun youtubeChannelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"
fun youtubePlaylistUrl(playlistId: String) = "https://www.youtube.com/playlist?list=$playlistId"
