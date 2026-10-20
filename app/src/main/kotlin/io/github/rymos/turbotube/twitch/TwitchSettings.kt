package io.github.rymos.turbotube.twitch

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rymos.turbotube.core.twitch.TwitchChannel

/** Everything on the settings page shows a label, so one row builder can render all of them. */
interface Labelled {
    val label: String
}

enum class ChatSide(override val label: String) : Labelled {
    Right("Rechts"),
    Left("Links"),
}

enum class ChatWidth(override val label: String, val width: Dp) : Labelled {
    Narrow("Schmal", 260.dp),
    Normal("Normal", 320.dp),
    Wide("Breit", 400.dp),
    Huge("Sehr breit", 480.dp),
}

enum class ChatTextSize(override val label: String, val size: Int) : Labelled {
    Small("Klein", 11),
    Normal("Normal", 13),
    Large("Groß", 16),
    Huge("Sehr groß", 19),
}

/** Line height and gap between messages. */
enum class ChatDensity(override val label: String, val lineHeight: Float, val gap: Dp) : Labelled {
    Tight("Eng", 1.15f, 3.dp),
    Normal("Normal", 1.3f, 6.dp),
    Loose("Luftig", 1.55f, 10.dp),
}

enum class ChatOpacity(override val label: String, val alpha: Float) : Labelled {
    Clear("Glasig", 0.15f),
    Light("Halbtransparent", 0.55f),
    Solid("Deckend", 0.94f),
}

enum class ChatPalette(override val label: String) : Labelled {
    Twitch("Twitch-Farben"),
    Purple("Einheitlich lila"),
    Mono("Einheitlich weiß"),
}

enum class EmoteSize(override val label: String, val scale: Float) : Labelled {
    Small("Klein", 0.9f),
    Normal("Normal", 1.15f),
    Large("Groß", 1.5f),
}

/** How much of the picture the TV's overscan eats; the stream is inset by this much. */
enum class Overscan(override val label: String, val horizontal: Dp, val vertical: Dp) : Labelled {
    Off("Keine", 0.dp, 0.dp),
    Small("Schmal", 16.dp, 8.dp),
    Medium("Mittel", 40.dp, 24.dp),
    Large("Groß", 64.dp, 40.dp),
}

enum class InfoBarTime(override val label: String, val millis: Long) : Labelled {
    Short("3 Sekunden", 3_000L),
    Normal("5 Sekunden", 5_000L),
    Long("10 Sekunden", 10_000L),
    Never("Dauerhaft", 0L),
}

enum class CardSize(override val label: String, val width: Dp) : Labelled {
    Compact("Kompakt", 190.dp),
    Normal("Normal", 240.dp),
    Large("Groß", 300.dp),
}

/**
 * Order of the "Folge ich" list. Recommended keeps Twitch's own order, live first; the viewer
 * sorts put the busiest or quietest stream on top, offline channels follow alphabetically.
 */
enum class FollowingSort(override val label: String) : Labelled {
    Recommended("Empfohlen"),
    ViewersHigh("Viele Zuschauer"),
    ViewersLow("Wenige Zuschauer"),
    Name("Name"),
}

/** Live first, then by the chosen order; offline channels all have 0 viewers, so they sort by name. */
fun List<TwitchChannel>.sortedFor(sort: FollowingSort): List<TwitchChannel> {
    val liveFirst = compareByDescending<TwitchChannel> { it.live }
    return when (sort) {
        FollowingSort.Recommended -> sortedWith(liveFirst)
        FollowingSort.ViewersHigh ->
            sortedWith(liveFirst.thenByDescending { it.viewers }.thenBy { it.displayName.lowercase() })
        FollowingSort.ViewersLow ->
            sortedWith(liveFirst.thenBy { it.viewers }.thenBy { it.displayName.lowercase() })
        FollowingSort.Name -> sortedBy { it.displayName.lowercase() }
    }
}

/**
 * Every adjustable part of the Twitch side, stored on the device. Writes go straight to the
 * preferences, reads are Compose state, so a changed value redraws the player immediately.
 */
class TwitchSettings(private val prefs: SharedPreferences) {

    inner class Toggle(private val key: String, defaultValue: Boolean) {
        private val fallback = defaultValue
        private var current by mutableStateOf(prefs.getBoolean(key, defaultValue))

        var value: Boolean
            get() = current
            set(newValue) {
                current = newValue
                prefs.edit().putBoolean(key, newValue).apply()
            }

        fun toggle() {
            value = !value
        }

        fun reset() {
            value = fallback
        }
    }

    inner class Choice<T>(
        private val key: String,
        private val values: List<T>,
        defaultValue: T,
    ) where T : Enum<T>, T : Labelled {
        private val fallback = defaultValue
        private var current by mutableStateOf(
            values.firstOrNull { it.name == prefs.getString(key, null) } ?: defaultValue,
        )

        var value: T
            get() = current
            set(newValue) {
                current = newValue
                prefs.edit().putString(key, newValue.name).apply()
            }

        val label: String get() = value.label

        fun next(step: Int) {
            value = values[Math.floorMod(values.indexOf(value) + step, values.size)]
        }

        fun reset() {
            value = fallback
        }
    }

    /** A number of fixed steps, stored as the number itself (quality keeps working after old builds). */
    inner class Steps(
        private val key: String,
        private val values: List<Int>,
        defaultValue: Int,
        private val label: (Int) -> String,
    ) {
        private val fallback = defaultValue
        private var current by mutableStateOf(
            prefs.getInt(key, defaultValue).takeIf { it in values } ?: defaultValue,
        )

        var value: Int
            get() = current
            set(newValue) {
                current = newValue
                prefs.edit().putInt(key, newValue).apply()
            }

        val text: String get() = label(value)

        fun next(step: Int) {
            value = values[Math.floorMod(values.indexOf(value) + step, values.size)]
        }

        fun reset() {
            value = fallback
        }
    }

    val chatVisible = Toggle("chat", true)
    val chatSide = Choice("chatSide", ChatSide.entries, ChatSide.Right)
    val chatWidth = Choice("chatWidth", ChatWidth.entries, ChatWidth.Normal)
    val chatTextSize = Choice("chatTextSize", ChatTextSize.entries, ChatTextSize.Normal)
    val chatDensity = Choice("chatDensity", ChatDensity.entries, ChatDensity.Normal)
    val chatOpacity = Choice("chatOpacity", ChatOpacity.entries, ChatOpacity.Solid)
    val chatPalette = Choice("chatPalette", ChatPalette.entries, ChatPalette.Twitch)
    val chatEmotes = Toggle("chatEmotes", true)
    val emoteSize = Choice("emoteSize", EmoteSize.entries, EmoteSize.Normal)
    val chatTimestamps = Toggle("chatTimestamps", false)
    val chatAutoScroll = Toggle("chatAutoScroll", true)

    val pictureFit = Choice("fit", PictureFit.entries, PictureFit.Fit)
    val quality = Steps("quality", QUALITIES, 0) { if (it == 0) "Auto" else "${it}p" }
    val overscan = Choice("overscan", Overscan.entries, Overscan.Off)
    val infoBar = Choice("infoBar", InfoBarTime.entries, InfoBarTime.Normal)

    val cardSize = Choice("cardSize", CardSize.entries, CardSize.Normal)
    val showHero = Toggle("showHero", true)
    val showViewers = Toggle("showViewers", true)
    val followingSort = Choice("followingSort", FollowingSort.entries, FollowingSort.Recommended)

    fun resetAll() {
        chatVisible.reset()
        chatSide.reset()
        chatWidth.reset()
        chatTextSize.reset()
        chatDensity.reset()
        chatOpacity.reset()
        chatPalette.reset()
        chatEmotes.reset()
        emoteSize.reset()
        chatTimestamps.reset()
        chatAutoScroll.reset()
        pictureFit.reset()
        quality.reset()
        overscan.reset()
        infoBar.reset()
        cardSize.reset()
        showHero.reset()
        showViewers.reset()
        followingSort.reset()
    }

    private companion object {
        val QUALITIES = listOf(0, 1080, 720, 480, 360)
    }
}
