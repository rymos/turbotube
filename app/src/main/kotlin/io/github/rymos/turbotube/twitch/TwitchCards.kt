package io.github.rymos.turbotube.twitch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.rymos.turbotube.core.twitch.TwitchCategory
import io.github.rymos.turbotube.core.twitch.TwitchChannel
import io.github.rymos.turbotube.ui.RemoteImage
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Twitch's colours. */
object TwitchColors {
    val Purple = Color(0xFF9147FF)
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF18181B)
    val SurfaceHigh = Color(0xFF26262C)
    val Text = Color(0xFFEFEFF1)
    val TextMuted = Color(0xFFADADB8)
    val Live = Color(0xFFEB0400)
}

/**
 * A surface the remote can land on, framed in purple when focused like on Twitch. [enabled]
 * false takes it out of the focus order (while a menu or the player lies on top).
 */
@Composable
fun FocusFrame(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusProperties { canFocus = enabled }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TwitchColors.Surface,
            contentColor = TwitchColors.Text,
            focusedContainerColor = TwitchColors.Surface,
            focusedContentColor = TwitchColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, TwitchColors.Purple), inset = 0.dp, shape = shape),
        ),
    ) { content() }
}

/** A live stream like in Twitch's lists: picture with LIVE and viewers, below it who and what. */
@Composable
fun StreamCard(
    channel: TwitchChannel,
    enabled: Boolean,
    onClick: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    focusRequester: FocusRequester? = null,
) {
    val requester = focusRequester ?: remember { FocusRequester() }
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())) {
        FocusFrame(onClick = { onClick(requester) }, enabled = enabled, focusRequester = requester) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                RemoteImage(
                    if (channel.live) channel.previewUrl ?: channel.avatarUrl else channel.avatarUrl,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                if (channel.live) {
                    Badge("LIVE", TwitchColors.Live, Modifier.align(Alignment.TopStart).padding(8.dp))
                    Badge(viewers(channel.viewers), Color(0xCC000000), Modifier.align(Alignment.BottomStart).padding(8.dp))
                } else {
                    Badge("Offline", Color(0xCC000000), Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(channel.avatarUrl, 30.dp)
            Column {
                Text(
                    channel.title.ifEmpty { channel.displayName },
                    color = TwitchColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(channel.displayName, color = TwitchColors.TextMuted, fontSize = 12.sp, maxLines = 1)
                if (channel.game.isNotEmpty()) {
                    Text(channel.game, color = TwitchColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A category's box art with its name and viewers, like in Twitch's directory. */
@Composable
fun CategoryCard(
    category: TwitchCategory,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())) {
        FocusFrame(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(4.dp)) {
            RemoteImage(category.boxArtUrl, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        }
        Spacer(Modifier.height(6.dp))
        Text(category.displayName, color = TwitchColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(viewers(category.viewers), color = TwitchColors.TextMuted, fontSize = 12.sp, maxLines = 1)
    }
}

/** An offline channel: round picture and name. */
@Composable
fun OfflineCard(channel: TwitchChannel, enabled: Boolean, onClick: (FocusRequester) -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FocusFrame(onClick = { onClick(requester) }, enabled = enabled, shape = CircleShape, focusRequester = requester) {
            RemoteImage(channel.avatarUrl, Modifier.size(96.dp).clip(CircleShape))
        }
        Spacer(Modifier.height(8.dp))
        Text(channel.displayName, color = TwitchColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Avatar(url: String?, size: Dp) {
    RemoteImage(url, Modifier.size(size).clip(CircleShape))
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "1,2 Tsd. Zuschauer", the way Twitch writes it in German. */
fun viewers(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.GERMANY, "%.1f Mio. Zuschauer", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.GERMANY, "%.1f Tsd. Zuschauer", count / 1_000.0).replace(",0 ", " ")
    count == 1 -> "1 Zuschauer"
    else -> "$count Zuschauer"
}

/** How long the stream has been live, as h:mm:ss like Twitch's player shows it. */
fun uptime(startedAt: String?, now: Instant = Instant.now()): String? {
    val start = startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val seconds = Duration.between(start, now).seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
