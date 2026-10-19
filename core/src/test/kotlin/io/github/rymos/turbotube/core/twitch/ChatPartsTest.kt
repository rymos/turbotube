package io.github.rymos.turbotube.core.twitch

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPartsTest {
    private fun message(text: String, emotes: List<ChatEmote>) =
        ChatMessage(1, "a", null, text, false, emotes)

    @Test
    fun `text and emotes alternate`() {
        val parts = message("Kappa test Keepo", listOf(ChatEmote("25", 0, 4), ChatEmote("1902", 11, 15))).parts()
        assertEquals(
            listOf(ChatPart("Kappa", "25"), ChatPart(" test ", null), ChatPart("Keepo", "1902")),
            parts,
        )
    }

    @Test
    fun `positions count code points, not utf-16 units`() {
        // "😀 Kappa": the emoji is one code point but two UTF-16 units
        val text = "😀 Kappa"
        val parts = message(text, listOf(ChatEmote("25", 2, 6))).parts()
        assertEquals(listOf(ChatPart("😀 ", null), ChatPart("Kappa", "25")), parts)
    }

    @Test
    fun `emotes off keeps the whole message`() {
        val parts = message("Kappa", listOf(ChatEmote("25", 0, 4))).parts(emotesEnabled = false)
        assertEquals(listOf(ChatPart("Kappa", null)), parts)
    }

    @Test
    fun `broken range is ignored instead of crashing`() {
        val parts = message("hi", listOf(ChatEmote("25", 0, 99))).parts()
        assertEquals(listOf(ChatPart("hi", null)), parts)
    }
}
