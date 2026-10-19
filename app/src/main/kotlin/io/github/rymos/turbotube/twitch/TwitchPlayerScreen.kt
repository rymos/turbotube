@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.rymos.turbotube.twitch

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.rymos.turbotube.core.twitch.ChatMessage
import io.github.rymos.turbotube.core.twitch.TwitchChannel
import io.github.rymos.turbotube.core.twitch.TwitchChat
import io.github.rymos.turbotube.core.twitch.parts
import io.github.rymos.turbotube.ui.RemoteImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A running stream, laid out like Twitch: the picture, the chat on the right, and on OK the info
 * bar with Follow, Chat and Quality.
 */
@Composable
fun TwitchPlayerScreen(controller: TwitchController, channel: TwitchChannel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TwitchPlayer.State>(TwitchPlayer.State.Loading) }
    var showInfo by remember { mutableStateOf(true) }
    val twitchPlayer = remember(channel.login) {
        TwitchPlayer(context, controller.api, scope, channel.login) { state = it }
    }
    val screenFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val active = controller.interactive

    DisposableEffect(twitchPlayer) {
        twitchPlayer.start()
        onDispose { twitchPlayer.release() }
    }
    LaunchedEffect(twitchPlayer, controller.maxHeight) { twitchPlayer.setMaxHeight(controller.maxHeight) }

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
        val hideAfter = controller.settings.infoBar.value.millis
        if (hideAfter > 0) {
            delay(hideAfter)
            showInfo = false
        }
    }
    LaunchedEffect(controller.playerPanelOpen, controller.focusRequests) {
        runCatching { if (controller.playerPanelOpen) panelFocus.requestFocus() else screenFocus.requestFocus() }
    }

    val inset = controller.settings.overscan.value
    val chatLeft = controller.settings.chatSide.value == ChatSide.Left
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { canFocus = active }
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
                update = { it.resizeMode = controller.pictureFit.resizeMode() },
                // Some TVs cut the edges off (overscan); the setting insets the picture
                modifier = Modifier.fillMaxSize().then(
                    if (inset != Overscan.Off) {
                        Modifier.padding(horizontal = inset.horizontal, vertical = inset.vertical)
                    } else {
                        Modifier
                    },
                ),
            )

            when (val current = state) {
                TwitchPlayer.State.Loading -> Notice("${channel.displayName} wird geladen …", Modifier.align(Alignment.Center))
                TwitchPlayer.State.AdBreak -> Notice(
                    "Werbepause – Werbung wird herausgefiltert, gleich geht es weiter.",
                    Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                )
                is TwitchPlayer.State.Failed -> Notice(current.message, Modifier.align(Alignment.Center))
                TwitchPlayer.State.Playing -> Unit
            }
        }

        if (controller.chatVisible) {
            ChatPanel(
                controller,
                channel,
                Modifier.align(if (chatLeft) Alignment.CenterStart else Alignment.CenterEnd),
            )
        }

        if (controller.playerPanelOpen || showInfo) {
            InfoBar(
                controller,
                channel,
                controlsVisible = controller.playerPanelOpen,
                firstButton = panelFocus,
                active = active,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = if (controller.chatVisible && chatLeft) controller.settings.chatWidth.value.width else 0.dp),
            )
        }
    }
}

/** Twitch's info bar at the bottom: who is live, what, since when, and the buttons. */
@Composable
private fun InfoBar(
    controller: TwitchController,
    channel: TwitchChannel,
    controlsVisible: Boolean,
    firstButton: FocusRequester,
    active: Boolean,
    modifier: Modifier,
) {
    val live by produceState(uptime(channel.startedAt), channel.startedAt) {
        while (true) {
            delay(1_000)
            value = uptime(channel.startedAt)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(channel.avatarUrl, 56.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(channel.displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Badge("LIVE", TwitchColors.Live)
                }
                if (channel.title.isNotEmpty()) {
                    Text(channel.title, color = TwitchColors.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(
                        channel.game.takeIf { it.isNotEmpty() },
                        channel.viewers.takeIf { it > 0 }?.let { viewers(it) },
                        live,
                    ).joinToString("  ·  "),
                    color = TwitchColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }
        if (controlsVisible) {
            val onTwitch = channel.login in controller.accountFollows
            val following = onTwitch || controller.favorites.contains(channel.login)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    // Followed with the Twitch account: that list is changed on Twitch itself
                    onClick = { if (!onTwitch) controller.favorites.toggle(channel.login) },
                    modifier = Modifier.focusProperties { canFocus = active }.focusRequester(firstButton),
                    colors = twitchButtonColors(primary = !following),
                ) {
                    Text(
                        when {
                            onTwitch -> "♥  Gefolgt (Twitch)"
                            following -> "Entfolgen"
                            else -> "♥  Folgen"
                        },
                    )
                }
                Button(
                    onClick = { controller.toggleChat() },
                    modifier = Modifier.focusProperties { canFocus = active },
                    colors = twitchButtonColors(),
                ) { Text(if (controller.chatVisible) "Chat ausblenden" else "Chat einblenden") }
                Button(
                    onClick = { controller.nextQuality() },
                    modifier = Modifier.focusProperties { canFocus = active },
                    colors = twitchButtonColors(),
                ) { Text("Qualität: " + (controller.maxHeight?.let { "${it}p" } ?: "Auto")) }
                Button(
                    onClick = { controller.nextPictureFit() },
                    modifier = Modifier.focusProperties { canFocus = active },
                    colors = twitchButtonColors(),
                ) { Text("Bild: " + controller.pictureFit.label) }
            }
            Text("Zurück schließt diese Leiste, nochmal Zurück beendet den Stream. Mehr unter Einstellungen.", color = TwitchColors.TextMuted, fontSize = 12.sp)
        } else {
            Text("OK: Folgen, Chat, Qualität, Bildformat", color = TwitchColors.TextMuted, fontSize = 12.sp)
        }
    }
}

/** The stream chat, with Twitch emotes, laid out the way the settings say. */
@Composable
private fun ChatPanel(controller: TwitchController, channel: TwitchChannel, modifier: Modifier = Modifier) {
    val settings = controller.settings
    val login = channel.login
    val messages = remember(login) { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

    DisposableEffect(login) {
        val main = Handler(Looper.getMainLooper())
        val chat = TwitchChat(controller.http, login) { message ->
            main.post {
                messages.add(message)
                repeat(messages.size - MAX_CHAT_LINES) { messages.removeAt(0) }
            }
        }
        chat.connect()
        onDispose {
            chat.close()
            main.removeCallbacksAndMessages(null)
        }
    }
    val autoScroll = settings.chatAutoScroll.value
    LaunchedEffect(messages.size, autoScroll) {
        if (autoScroll && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    val density = settings.chatDensity.value
    Column(
        modifier
            .width(settings.chatWidth.value.width)
            .fillMaxHeight()
            .background(TwitchColors.Surface.copy(alpha = settings.chatOpacity.value.alpha)),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text("STREAM-CHAT", color = TwitchColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TwitchColors.SurfaceHigh))
        if (messages.isEmpty()) {
            Text(
                "Willkommen im Chat!",
                color = TwitchColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(density.gap),
        ) {
            items(messages, key = { it.id }) { message -> ChatLine(controller, message) }
        }
    }
}

/** One chat line: optional time, name, then text and emotes mixed the way Twitch shows them. */
@Composable
private fun ChatLine(controller: TwitchController, message: ChatMessage) {
    val settings = controller.settings
    val textSize = settings.chatTextSize.value.size
    val density = settings.chatDensity.value
    val emoteSize = (textSize * settings.emoteSize.value.scale)
    val emoteDp = emoteSize.dp
    val nameStyle = SpanStyle(
        color = when (settings.chatPalette.value) {
            ChatPalette.Twitch -> nameColor(message)
            ChatPalette.Purple -> TwitchColors.Purple
            ChatPalette.Mono -> Color.White
        },
        fontWeight = FontWeight.Bold,
    )

    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        val stamp = message.timestamp
        if (settings.chatTimestamps.value && stamp != null) {
            withStyle(SpanStyle(color = TwitchColors.TextMuted)) {
                append(TIME_FORMAT.format(Date(stamp)))
                append("  ")
            }
        }
        withStyle(nameStyle) { append(message.name) }
        append(if (message.action) " " else ": ")

        message.parts(settings.chatEmotes.value).forEachIndexed { index, part ->
            val emoteId = part.emoteId
            if (emoteId != null) {
                val key = "emote-$index"
                inline[key] = InlineTextContent(
                    Placeholder(width = emoteSize.sp, height = emoteSize.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
                ) {
                    RemoteImage(emoteUrl(emoteId), Modifier.size(emoteDp), ContentScale.Fit)
                }
                appendInlineContent(key, part.text)
            } else {
                withStyle(SpanStyle(fontStyle = if (message.action) FontStyle.Italic else FontStyle.Normal)) {
                    append(part.text)
                }
            }
        }
    }

    BasicText(
        text = text,
        style = TextStyle(
            color = TwitchColors.Text,
            fontSize = textSize.sp,
            lineHeight = (textSize * density.lineHeight).sp,
        ),
        inlineContent = inline,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun emoteUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.GERMAN)

private fun PictureFit.resizeMode(): Int = when (this) {
    PictureFit.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PictureFit.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PictureFit.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

/** Twitch gives people without a chosen colour one of a few defaults. */
private fun nameColor(message: ChatMessage): Color {
    message.color?.let { return Color(android.graphics.Color.parseColor(it)) }
    return DEFAULT_NAME_COLORS[Math.floorMod(message.name.hashCode(), DEFAULT_NAME_COLORS.size)]
}

private val DEFAULT_NAME_COLORS = listOf(
    Color(0xFFFF0000), Color(0xFF0000FF), Color(0xFF00FF00), Color(0xFFB22222), Color(0xFFFF7F50),
    Color(0xFF9ACD32), Color(0xFFFF4500), Color(0xFF2E8B57), Color(0xFFDAA520), Color(0xFFD2691E),
    Color(0xFF5F9EA0), Color(0xFF1E90FF), Color(0xFFFF69B4), Color(0xFF8A2BE2), Color(0xFF00FF7F),
)

@Composable
private fun Notice(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 16.sp,
        modifier = modifier
            .background(Color(0xDD18181B), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private const val INFO_VISIBLE_MS = 5_000L
private const val MAX_CHAT_LINES = 150
