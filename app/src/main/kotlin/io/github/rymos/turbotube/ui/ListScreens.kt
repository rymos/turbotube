package io.github.rymos.turbotube.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.rymos.turbotube.core.YouTube

@Composable
fun ChannelScreen(screen: Screen.Channel) {
    val paged = rememberPaged("channel:${screen.url}") { next ->
        YouTube.channelVideos(screen.url, next)
    }
    Column {
        SectionTitle(screen.name)
        ItemGrid(paged, emptyText = "Dieser Kanal hat keine Videos.")
    }
}

@Composable
fun PlaylistScreen(screen: Screen.Playlist) {
    val container = LocalContainer.current
    val paged = rememberPaged("playlist:${screen.url}") { next ->
        if (screen.own) {
            container.accountApi.playlistVideos(screen.id, next)
        } else {
            YouTube.playlistVideos(screen.url, next)
        }
    }
    Column {
        SectionTitle(screen.title)
        ItemGrid(paged, emptyText = "Diese Playlist ist leer.")
    }
}
