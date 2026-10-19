package io.github.rymos.turbotube.core.twitch

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One emote inside a message, at [start]..[end] (inclusive) of [ChatMessage.text], counted in code points. */
data class ChatEmote(val id: String, val start: Int, val end: Int)

/**
 * One line in the chat. [color] is the name colour the viewer picked, as #RRGGBB, if any.
 * [timestamp] is when Twitch received the message (epoch millis), if the message carried it.
 */
data class ChatMessage(
    val id: Long,
    val name: String,
    val color: String?,
    val text: String,
    val action: Boolean,
    val emotes: List<ChatEmote> = emptyList(),
    val timestamp: Long? = null,
)

/**
 * Reads a channel's chat anonymously, the way Twitch lets logged-out viewers watch it: no
 * account, read only. Messages arrive on OkHttp's thread.
 */
class TwitchChat(
    private val http: OkHttpClient,
    private val channel: String,
    private val onMessage: (ChatMessage) -> Unit,
) {
    private var socket: WebSocket? = null
    private var closed = false
    private var nextId = 0L

    fun connect() {
        if (closed) return
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK justinfan${(10_000..99_999).random()}")
                webSocket.send("JOIN #${channel.lowercase()}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split("\r\n")) {
                    if (line.startsWith("PING")) {
                        webSocket.send(line.replaceFirst("PING", "PONG"))
                        continue
                    }
                    ChatParser.parse(line, nextId)?.let {
                        nextId++
                        onMessage(it)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Chat is a nice-to-have; try again quietly
                if (!closed) Thread { Thread.sleep(RECONNECT_MS); connect() }.start()
            }
        })
    }

    fun close() {
        closed = true
        socket?.close(1000, null)
        socket = null
    }

    private companion object {
        const val RECONNECT_MS = 5_000L
    }
}

/** Turns an IRC line from Twitch chat into a [ChatMessage]; null for everything but messages. */
object ChatParser {
    fun parse(line: String, id: Long = 0): ChatMessage? {
        var rest = line
        val tags = HashMap<String, String>()
        if (rest.startsWith("@")) {
            val end = rest.indexOf(' ')
            if (end < 0) return null
            rest.substring(1, end).split(';').forEach {
                val eq = it.indexOf('=')
                if (eq > 0) tags[it.substring(0, eq)] = unescape(it.substring(eq + 1))
            }
            rest = rest.substring(end + 1)
        }
        if (!rest.startsWith(":")) return null
        val prefixEnd = rest.indexOf(' ')
        if (prefixEnd < 0) return null
        val nick = rest.substring(1, prefixEnd).substringBefore('!')
        rest = rest.substring(prefixEnd + 1)
        if (!rest.startsWith("PRIVMSG ")) return null
        val textStart = rest.indexOf(" :")
        if (textStart < 0) return null
        var text = rest.substring(textStart + 2)
        val action = text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")
        if (action) text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
        val name = tags["display-name"]?.takeIf { it.isNotBlank() } ?: nick
        val color = tags["color"]?.takeIf { COLOR.matches(it) }
        return ChatMessage(
            id = id,
            name = name,
            color = color,
            text = text,
            action = action,
            emotes = parseEmotes(tags["emotes"].orEmpty()),
            timestamp = tags["tmi-sent-ts"]?.toLongOrNull(),
        )
    }

    /** `25:0-4,12-16/1902:6-10` → one entry per occurrence. */
    private fun parseEmotes(value: String): List<ChatEmote> {
        if (value.isBlank()) return emptyList()
        return value.split('/').flatMap { entry ->
            val colon = entry.indexOf(':')
            if (colon <= 0) return@flatMap emptyList()
            val id = entry.substring(0, colon)
            entry.substring(colon + 1).split(',').mapNotNull { range ->
                val dash = range.indexOf('-')
                if (dash <= 0) return@mapNotNull null
                val start = range.substring(0, dash).toIntOrNull() ?: return@mapNotNull null
                val end = range.substring(dash + 1).toIntOrNull() ?: return@mapNotNull null
                if (end < start) null else ChatEmote(id, start, end)
            }
        }.sortedBy { it.start }
    }

    private fun unescape(value: String) = value
        .replace("\\s", " ")
        .replace("\\:", ";")
        .replace("\\\\", "\\")

    private val COLOR = Regex("#[0-9A-Fa-f]{6}")
}
