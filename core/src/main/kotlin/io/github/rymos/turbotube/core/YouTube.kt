package io.github.rymos.turbotube.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import org.schabi.newpipe.extractor.Page as NpPage

/**
 * Anonymous access to public YouTube content through NewPipeExtractor: search, channels,
 * public playlists, kiosks and the stream URLs used for playback. No Google API key and no
 * account is involved here.
 */
object YouTube {
    private val service get() = ServiceList.YouTube

    /** Start pages offered when nobody is logged in, keyed by NewPipe kiosk id. */
    val kiosks = listOf(
        "live" to "Live",
        "trending_music" to "Musik",
        "trending_gaming" to "Gaming",
        "trending_movies_and_shows" to "Filme & Serien",
        "trending_podcasts_episodes" to "Podcasts",
    )

    fun init(client: OkHttpClient, locale: Locale = Locale.getDefault()) {
        val country = locale.country.ifEmpty { "DE" }
        NewPipe.init(
            NewPipeDownloader(client),
            Localization.fromLocale(locale),
            ContentCountry(country),
        )
    }

    suspend fun search(query: String, next: Any? = null): Page<Item> = io {
        val handler = service.searchQHFactory.fromQuery(query)
        if (next is NpPage) {
            val more = SearchInfo.getMoreItems(service, handler, next)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        } else {
            val info = SearchInfo.getInfo(service, handler)
            Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList() else service.suggestionExtractor.suggestionList(query)
    }

    suspend fun kiosk(kioskId: String, next: Any? = null): Page<Item> = io {
        val url = service.kioskList.getListLinkHandlerFactoryByType(kioskId)
            .fromId(kioskId).url
        if (next is NpPage) {
            val more = KioskInfo.getMoreItems(service, url, next)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        } else {
            val info = KioskInfo.getInfo(service, url)
            Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
        }
    }

    /** Uploads of a channel, newest first. [next] continues an earlier page. */
    suspend fun channelVideos(channelUrl: String, next: Any? = null): Page<Item> = io {
        val tab = channelVideosTab(channelUrl)
        if (next is NpPage) {
            val more = ChannelTabInfo.getMoreItems(service, tab, next)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        } else {
            val info = ChannelTabInfo.getInfo(service, tab)
            Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
        }
    }

    suspend fun channel(channelUrl: String): Channel = io {
        val info = ChannelInfo.getInfo(service, channelUrl)
        Channel(info.id, info.url, info.name, info.avatars.pick(176))
    }

    private val channelTabs = mutableMapOf<String, org.schabi.newpipe.extractor.linkhandler.ListLinkHandler>()

    private fun channelVideosTab(channelUrl: String) = synchronized(channelTabs) {
        channelTabs.getOrPut(channelUrl) {
            val info = ChannelInfo.getInfo(service, channelUrl)
            info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                ?: info.tabs.first()
        }
    }

    /** Videos of a public playlist. Private playlists go through [YouTubeAccountApi]. */
    suspend fun playlistVideos(playlistUrl: String, next: Any? = null): Page<Item> = io {
        if (next is NpPage) {
            val more = PlaylistInfo.getMoreItems(service, playlistUrl, next)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        } else {
            val info = PlaylistInfo.getInfo(service, playlistUrl)
            Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
        }
    }

    /** Latest uploads of a channel from its public RSS feed, cheap enough to call per channel. */
    suspend fun channelFeed(channelId: String): List<Video> = io {
        val extractor = service.getFeedExtractor(youtubeChannelUrl(channelId))
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideo() }
    }

    /** Everything needed to play a video, including the stream URLs. */
    suspend fun stream(videoUrl: String): StreamInfo = io {
        StreamInfo.getInfo(service, videoUrl)
    }

    fun videoId(url: String): String = service.streamLHFactory.getId(url)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

fun InfoItem.toItem(): Item? = when (this) {
    is StreamInfoItem -> toVideo()
    is ChannelInfoItem -> Channel(
        id = runCatching { ServiceList.YouTube.channelLHFactory.getId(url) }.getOrDefault(url),
        url = url,
        name = name,
        avatarUrl = thumbnails.pick(176),
    )
    is PlaylistInfoItem -> Playlist(
        id = runCatching { ServiceList.YouTube.playlistLHFactory.getId(url) }.getOrDefault(url),
        url = url,
        title = name,
        thumbnailUrl = thumbnails.pick(360),
        videoCount = streamCount,
    )
    else -> null
}

fun StreamInfoItem.toVideo(): Video? {
    // Shorts are skipped: they make little sense on a TV and are vertical
    if (isShortFormContent) return null
    return Video(
        id = runCatching { YouTube.videoId(url) }.getOrDefault(url),
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl,
        thumbnailUrl = thumbnails.pick(360),
        durationSeconds = duration,
        viewCount = viewCount,
        uploadedText = textualUploadDate,
        uploadedAtMillis = uploadDate?.instant?.toEpochMilli(),
        isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
    )
}

/** Picks the smallest image that is at least [minHeight] tall, or the largest one available. */
fun List<Image>.pick(minHeight: Int): String? {
    if (isEmpty()) return null
    val known = filter { it.height > 0 }.sortedBy { it.height }
    return (known.firstOrNull { it.height >= minHeight } ?: known.lastOrNull() ?: last()).url
}
