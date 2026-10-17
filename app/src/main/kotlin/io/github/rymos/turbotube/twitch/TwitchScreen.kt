package io.github.rymos.turbotube.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.rymos.turbotube.core.twitch.TwitchApi
import io.github.rymos.turbotube.core.twitch.TwitchCategory
import io.github.rymos.turbotube.core.twitch.TwitchChannel
import io.github.rymos.turbotube.ui.RemoteImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Purple = Color(0xFF9147FF)
private val Panel = Color(0xFF18181B)

/** What a list shows: still loading, the items, or why there are none. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val items: List<T>) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

enum class TwitchTab(val label: String) {
    Favorites("Favoriten"),
    Popular("Beliebt"),
    Categories("Kategorien"),
    Search("Suche"),
}

/** Everything the Twitch side shows, kept across recompositions and menu visits. */
class TwitchController(val api: TwitchApi, val favorites: Favorites, private val scope: CoroutineScope) {
    var tab by mutableStateOf(TwitchTab.Favorites)
        private set
    var channels by mutableStateOf<Load<TwitchChannel>>(Load.Loading)
        private set
    var categories by mutableStateOf<Load<TwitchCategory>>(Load.Loading)
        private set
    var category by mutableStateOf<TwitchCategory?>(null)
        private set
    var query by mutableStateOf("")
    var playing by mutableStateOf<TwitchChannel?>(null)
        private set
    var playerPanelOpen by mutableStateOf(false)

    /** False while Turbotube's menu lies on top, so the remote can't wander behind it. */
    var interactive by mutableStateOf(true)

    /** Counts up when the focus has to come back, after the menu closed. */
    var focusRequests by mutableIntStateOf(0)
        private set

    fun restoreFocus() {
        focusRequests++
    }

    private var job: Job? = null

    init {
        // Without favourites the popular streams are the better first page
        if (favorites.logins.isEmpty()) tab = TwitchTab.Popular
    }

    fun select(newTab: TwitchTab) {
        if (newTab == tab && channels !is Load.Failed) return
        tab = newTab
        category = null
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            when (tab) {
                TwitchTab.Favorites -> loadChannels {
                    // Live channels first, the rest in the order they were saved
                    api.channels(favorites.logins).sortedByDescending { it.live }
                }
                TwitchTab.Popular -> loadChannels { api.topStreams() }
                TwitchTab.Categories -> {
                    val open = category
                    if (open != null) {
                        loadChannels { api.categoryStreams(open.name) }
                    } else {
                        categories = Load.Loading
                        categories = runCatching { Load.Done(api.topCategories()) }
                            .getOrElse { Load.Failed(it.message ?: "Fehler beim Laden.") }
                    }
                }
                TwitchTab.Search -> if (query.isBlank()) {
                    channels = Load.Done(emptyList())
                } else {
                    loadChannels { api.search(query) }
                }
            }
        }
    }

    fun open(newCategory: TwitchCategory) {
        category = newCategory
        refresh()
    }

    fun play(channel: TwitchChannel) {
        playerPanelOpen = false
        playing = channel
    }

    /** Short press of Back. False when there is nothing left to go back to. */
    fun back(): Boolean = when {
        playing != null && playerPanelOpen -> {
            playerPanelOpen = false
            true
        }
        playing != null -> {
            playing = null
            // The favourite may have changed while watching
            if (tab == TwitchTab.Favorites) refresh()
            true
        }
        category != null -> {
            category = null
            refresh()
            true
        }
        else -> false
    }

    private suspend fun loadChannels(load: suspend () -> List<TwitchChannel>) {
        channels = Load.Loading
        channels = runCatching { Load.Done(load()) }
            .getOrElse { Load.Failed(it.message ?: "Fehler beim Laden.") }
    }
}

@Composable
fun TwitchScreen(controller: TwitchController) {
    LaunchedEffect(Unit) { controller.refresh() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
            val playing = controller.playing
            if (playing != null) {
                TwitchPlayerScreen(controller, playing)
            } else {
                Browse(controller)
            }
        }
    }
}

@Composable
private fun Browse(controller: TwitchController) {
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(controller.focusRequests) { runCatching { tabFocus.requestFocus() } }
    val focus = controller.focusWhenActive()

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Twitch", color = Purple, fontSize = 28.sp, modifier = Modifier.padding(end = 24.dp))
            TwitchTab.entries.forEach { tab ->
                val selected = tab == controller.tab
                Button(
                    onClick = { controller.select(tab) },
                    modifier = focus.then(if (selected) Modifier.focusRequester(tabFocus) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) Purple else Panel,
                        contentColor = Color.White,
                    ),
                ) { Text(tab.label) }
            }
        }
        Spacer(Modifier.padding(top = 16.dp))

        if (controller.tab == TwitchTab.Search) SearchField(controller)
        controller.category?.let {
            Text(it.displayName, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 12.dp))
        }

        if (controller.tab == TwitchTab.Categories && controller.category == null) {
            CategoryGrid(controller)
        } else {
            ChannelGrid(controller)
        }
    }
}

@Composable
private fun SearchField(controller: TwitchController) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = controller.query,
        onValueChange = { controller.query = it },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { controller.refresh() }),
        modifier = controller.focusWhenActive()
            .padding(bottom = 16.dp)
            .width(560.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(Panel, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Purple else Color(0xFF3A3A3D), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (controller.query.isEmpty()) {
                Text("Kanal suchen, dann OK drücken und Text eingeben", color = Color(0xFF8A8A8F), fontSize = 20.sp)
            }
            inner()
        },
    )
}

@Composable
private fun ChannelGrid(controller: TwitchController) {
    when (val load = controller.channels) {
        Load.Loading -> Hint("Lädt …")
        is Load.Failed -> Hint(load.message)
        is Load.Done -> if (load.items.isEmpty()) {
            Hint(
                when (controller.tab) {
                    TwitchTab.Favorites ->
                        "Noch keine Favoriten. Öffne einen Stream, drücke OK und wähle „Als Favorit speichern“."
                    TwitchTab.Search -> if (controller.query.isBlank()) "Gib oben einen Kanalnamen ein." else "Nichts gefunden."
                    else -> "Gerade läuft hier nichts."
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(load.items, key = { it.login }) { channel ->
                    ChannelCard(channel, controller.favorites.contains(channel.login), controller.focusWhenActive()) {
                        controller.play(channel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: TwitchChannel, favorite: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Box {
            RemoteImage(
                if (channel.live) channel.previewUrl ?: channel.avatarUrl else channel.avatarUrl,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Text(
                if (channel.live) "LIVE · ${viewers(channel.viewers)}" else "offline",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .background(if (channel.live) Color(0xFFEB0400) else Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                (if (favorite) "★ " else "") + channel.displayName,
                color = Color.White,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                channel.title.ifEmpty { " " },
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                channel.game.ifEmpty { " " },
                color = Color(0xFF8A8A8F),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryGrid(controller: TwitchController) {
    when (val load = controller.categories) {
        Load.Loading -> Hint("Lädt …")
        is Load.Failed -> Hint(load.message)
        is Load.Done -> LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(load.items, key = { it.name }) { category ->
                Card(onClick = { controller.open(category) }, modifier = controller.focusWhenActive()) {
                    RemoteImage(category.boxArtUrl, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                    Column(Modifier.padding(8.dp)) {
                        Text(category.displayName, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(viewers(category.viewers), color = Color(0xFF8A8A8F), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TwitchPlayerScreen(controller: TwitchController, channel: TwitchChannel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TwitchPlayer.State>(TwitchPlayer.State.Loading) }
    var showTitle by remember { mutableStateOf(true) }
    val twitchPlayer = remember(channel.login) {
        TwitchPlayer(context, controller.api, scope, channel.login) { state = it }
    }
    val screenFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    DisposableEffect(twitchPlayer) {
        twitchPlayer.start()
        onDispose { twitchPlayer.release() }
    }

    // In the background the stream pauses; coming back it starts again at the live edge
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, twitchPlayer) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    twitchPlayer.pause()
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    twitchPlayer.resume()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        delay(TITLE_VISIBLE_MS)
        showTitle = false
    }
    LaunchedEffect(controller.playerPanelOpen, controller.focusRequests) {
        runCatching { if (controller.playerPanelOpen) panelFocus.requestFocus() else screenFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(controller.focusWhenActive())
            .focusRequester(screenFocus)
            .onKeyEvent {
                val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                if (ok && it.type == KeyEventType.KeyUp && !controller.playerPanelOpen) {
                    controller.playerPanelOpen = true
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
                    player = twitchPlayer.exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        when (val current = state) {
            TwitchPlayer.State.Loading -> Banner("${channel.displayName} wird geladen …", Modifier.align(Alignment.Center))
            TwitchPlayer.State.AdBreak -> Banner(
                "Werbepause bei Twitch, Werbung wird herausgefiltert. Gleich geht es weiter.",
                Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
            is TwitchPlayer.State.Failed -> Banner(current.message, Modifier.align(Alignment.Center))
            TwitchPlayer.State.Playing -> Unit
        }

        if (controller.playerPanelOpen) {
            val favorite = controller.favorites.contains(channel.login)
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChannelTitle(channel)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { controller.favorites.toggle(channel.login) },
                        modifier = controller.focusWhenActive().focusRequester(panelFocus),
                    ) { Text(if (favorite) "Aus Favoriten entfernen" else "Als Favorit speichern") }
                    Button(onClick = { controller.playerPanelOpen = false }, modifier = controller.focusWhenActive()) {
                        Text("Ausblenden")
                    }
                }
                Text("Zurück beendet den Stream.", color = Color(0xFFAAAAAA), fontSize = 13.sp)
            }
        } else if (showTitle) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(48.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(16.dp),
            ) {
                ChannelTitle(channel)
                Text("OK: Favorit speichern", color = Color(0xFFAAAAAA), fontSize = 13.sp)
            }
        }
    }
}

/** Applied to everything the remote can land on in the Twitch interface. */
@Composable
private fun TwitchController.focusWhenActive(): Modifier {
    val allowed = interactive
    return Modifier.focusProperties { canFocus = allowed }
}

@Composable
private fun ChannelTitle(channel: TwitchChannel) {
    Text(channel.displayName, color = Color.White, fontSize = 22.sp)
    if (channel.title.isNotEmpty()) {
        Text(channel.title, color = Color(0xFFDDDDDD), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 18.sp,
        modifier = modifier
            .background(Color(0xDD18181B), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color(0xFFBBBBBB), fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
}

private fun viewers(count: Int): String = when {
    count >= 1_000_000 -> "%.1f Mio. Zuschauer".format(count / 1_000_000.0)
    count >= 10_000 -> "${count / 1000} Tsd. Zuschauer"
    else -> "$count Zuschauer"
}

private const val TITLE_VISIBLE_MS = 5_000L
