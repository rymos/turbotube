package io.github.rymos.turbotube.core.twitch

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One line in the chat. [color] is the name colour the viewer picked, as #RRGGBB, if any. */
data class ChatMessage(val id: Long, val name: String, val color: String?, val text: String, val action: Boolean)

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
        return ChatMessage(id, name, color, text, action)
    }

    private fun unescape(value: String) = value
        .replace("\\s", " ")
        .replace("\\:", ";")
        .replace("\\\\", "\\")

    private val COLOR = Regex("#[0-9A-Fa-f]{6}")
}
