package io.github.rymos.turbotube.core.twitch

/** A piece of a chat line: either plain text or an emote with Twitch's image id. */
data class ChatPart(val text: String, val emoteId: String?)

/**
 * Cuts a message into text and emote pieces. Twitch counts emote positions in code points, not
 * UTF-16 units, so text with emoji or other surrogate pairs keeps the right emotes.
 */
fun ChatMessage.parts(emotesEnabled: Boolean = true): List<ChatPart> {
    if (!emotesEnabled || emotes.isEmpty()) return listOf(ChatPart(text, null))

    val characters = text.codePoints().toArray().map { String(Character.toChars(it)) }
    val byStart = emotes.associateBy { it.start }
    val parts = mutableListOf<ChatPart>()
    val current = StringBuilder()
    var index = 0

    while (index < characters.size) {
        val emote = byStart[index]?.takeIf { it.end < characters.size }
        if (emote == null) {
            current.append(characters[index])
            index++
        } else {
            if (current.isNotEmpty()) {
                parts.add(ChatPart(current.toString(), null))
                current.clear()
            }
            parts.add(ChatPart(characters.subList(index, emote.end + 1).joinToString(""), emote.id))
            index = emote.end + 1
        }
    }
    if (current.isNotEmpty()) parts.add(ChatPart(current.toString(), null))
    return parts
}
