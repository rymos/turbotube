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
    fun `emotes with several ranges`() {
        val line = "@emotes=25:0-4,12-16/1902:6-10;tmi-sent-ts=1730000000000 " +
            ":a!a@a.tmi.twitch.tv PRIVMSG #x :Kappa test Keepo Kappa"
        val message = ChatParser.parse(line)!!
        assertEquals(listOf(ChatEmote("25", 0, 4), ChatEmote("1902", 6, 10), ChatEmote("25", 12, 16)), message.emotes)
        assertEquals(1730000000000L, message.timestamp)
        assertEquals("Kappa test Keepo Kappa", message.text)
    }

    @Test
    fun `no emotes and no timestamp`() {
        val message = ChatParser.parse(":a!a@a.tmi.twitch.tv PRIVMSG #x :hallo")!!
        assertTrue(message.emotes.isEmpty())
        assertNull(message.timestamp)
    }

    @Test
    fun `everything else is ignored`() {
        assertNull(ChatParser.parse("PING :tmi.twitch.tv"))
        assertNull(ChatParser.parse(":tmi.twitch.tv 001 justinfan12345 :Welcome, GLHF!"))
        assertNull(ChatParser.parse("@emote-only=0 :tmi.twitch.tv ROOMSTATE #x"))
    }
}
