package io.github.rymos.turbotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.rymos.turbotube.core.Channel
import io.github.rymos.turbotube.core.Item
import io.github.rymos.turbotube.core.Page
import io.github.rymos.turbotube.core.Playlist
import io.github.rymos.turbotube.core.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A list that loads page by page as the user scrolls down. */
class PagedItems(
    private val scope: CoroutineScope,
    private val load: suspend (Any?) -> Page<Item>,
) {
    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore = true
        private set
    private var next: Any? = null

    val gridState = LazyGridState()
    var focusedIndex = -1

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        error = null
        scope.launch {
            try {
                val page = load(next)
                items = items + page.items
                next = page.next
                hasMore = page.next != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    fun reload() {
        items = emptyList()
        next = null
        hasMore = true
        focusedIndex = -1
        loadMore()
    }
}

/**
 * The main building block of every screen: a grid of video, channel and playlist cards.
 * [header] scrolls together with the grid.
 */
@Composable
fun ItemGrid(
    paged: PagedItems,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    emptyText: String = "Hier ist nichts.",
    onOpen: ((Item) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val open = onOpen ?: { item: Item -> openItem(navigator, item) }
    LaunchedEffect(paged) {
        if (paged.items.isEmpty() && paged.hasMore) paged.loadMore()
    }
    LaunchedEffect(paged) {
        snapshotFlow {
            val last = paged.gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= paged.gridState.layoutInfo.totalItemsCount - columns * 2
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && paged.items.isNotEmpty()) paged.loadMore()
        }
    }

    // Coming back from the player: put the focus back on the card that was opened
    val restore = remember { FocusRequester() }
    val restoreIndex = remember(paged) { paged.focusedIndex }
    LaunchedEffect(paged) {
        if (restoreIndex >= 0) {
            withFrameNanos { }
            runCatching { restore.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = paged.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        itemsIndexed(paged.items, key = { index, item -> "$index ${item.url}" }) { index, item ->
            val cardModifier = Modifier
                .onFocusChanged { if (it.isFocused) paged.focusedIndex = index }
                .let { if (index == restoreIndex) it.focusRequester(restore) else it }
            ItemCard(item, cardModifier, onOpen = open)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    paged.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Fehler: ${paged.error}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { paged.loadMore() }, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Nochmal versuchen")
                        }
                    }
                    paged.loading -> Text("Lädt …")
                    paged.items.isEmpty() -> Text(emptyText)
                }
            }
        }
    }
}

fun openItem(navigator: Navigator, item: Item) {
    when (item) {
        is Video -> navigator.push(Screen.Player(item.url))
        is Channel -> navigator.push(Screen.Channel(item.url, item.name))
        is Playlist -> navigator.push(
            Screen.Playlist(item.url, item.id, item.title, own = false),
        )
    }
}

@Composable
fun ItemCard(item: Item, modifier: Modifier = Modifier, onOpen: (Item) -> Unit) {
    val navigator = LocalNavigator.current
    when (item) {
        is Video -> VideoCard(
            item,
            modifier,
            onClick = { onOpen(item) },
            // Long press on a video opens its channel
            onLongClick = item.channelUrl?.let { url ->
                { navigator.push(Screen.Channel(url, item.channelName)) }
            },
        )
        is Channel -> ChannelCard(item, modifier) { onOpen(item) }
        is Playlist -> PlaylistCard(item, modifier) { onOpen(item) }
    }
}

@Composable
fun VideoCard(
    video: Video,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = when {
                video.isLive -> "LIVE"
                video.durationSeconds > 0 -> formatDuration(video.durationSeconds)
                else -> null
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            if (video.isLive) Color(0xFFD32F2F) else Color(0xCC000000),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    video.channelName.ifEmpty { null },
                    video.viewCount.takeIf { it >= 0 }?.let(::formatViews),
                    video.uploadedText ?: video.uploadedAtMillis?.let(::formatAge),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = channel.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f).clip(CircleShape),
            )
            Text(
                channel.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = playlist.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (playlist.videoCount >= 0) {
                Text(
                    "${playlist.videoCount} Videos",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatViews(views: Long): String = when {
    views >= 1_000_000 -> "%.1f Mio. Aufrufe".format(views / 1_000_000.0)
    views >= 1_000 -> "%d Tsd. Aufrufe".format(views / 1_000)
    else -> "$views Aufrufe"
}

fun formatAge(epochMillis: Long): String {
    val minutes = (System.currentTimeMillis() - epochMillis) / 60_000
    return when {
        minutes < 60 -> "vor $minutes Min."
        minutes < 60 * 24 -> "vor ${minutes / 60} Std."
        minutes < 60 * 24 * 30 -> "vor ${minutes / (60 * 24)} Tagen"
        minutes < 60 * 24 * 365 -> "vor ${minutes / (60 * 24 * 30)} Monaten"
        else -> "vor ${minutes / (60 * 24 * 365)} Jahren"
    }
}
