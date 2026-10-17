package io.github.rymos.turbotube.core.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatParserTest {
    @Test
    fun `message with tags`() {
        val line = "@badge-info=;color=#1E90FF;display-name=Flushed\\sJulian;emotes= " +
            ":flushedjulian!flushedjulian@flushedjulian.tmi.twitch.tv PRIVMSG #papaplatte :alles wurde gut"
        val message = ChatParser.parse(line, 7)!!
        assertEquals(7L, message.id)
        assertEquals("Flushed Julian", message.name)
        assertEquals("#1E90FF", message.color)
        assertEquals("alles wurde gut", message.text)
    }

    @Test
    fun `message without tags or colour`() {
        val message = ChatParser.parse(":weird_7!weird_7@weird_7.tmi.twitch.tv PRIVMSG #x :hallo :D")!!
        assertEquals("weird_7", message.name)
        assertNull(message.color)
        assertEquals("hallo :D", message.text)
    }

    @Test
    fun `me action`() {
        val message = ChatParser.parse(":a!a@a.tmi.twitch.tv PRIVMSG #x :\u0001ACTION winkt\u0001")!!
        assertTrue(message.action)
        assertEquals("winkt", message.text)
    }

    @Test
    fun `everything else is ignored`() {
        assertNull(ChatParser.parse("PING :tmi.twitch.tv"))
        assertNull(ChatParser.parse(":tmi.twitch.tv 001 justinfan12345 :Welcome, GLHF!"))
        assertNull(ChatParser.parse("@emote-only=0 :tmi.twitch.tv ROOMSTATE #x"))
    }
}
