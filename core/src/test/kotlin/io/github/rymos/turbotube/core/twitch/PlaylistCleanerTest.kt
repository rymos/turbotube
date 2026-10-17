package io.github.rymos.turbotube.core.twitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCleanerTest {
    private val header = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:100
        #EXT-X-TWITCH-ELAPSED-SECS:1000.000
    """.trimIndent()

    private fun live(n: Int) = "#EXT-X-PROGRAM-DATE-TIME:2026-10-17T12:00:0$n.000Z\n#EXTINF:2.000,live\nhttps://edge/live$n.ts"

    private fun ad(n: Int) = "#EXT-X-PROGRAM-DATE-TIME:2026-10-17T12:00:0$n.000Z\n#EXTINF:2.000,Amazon|123\nhttps://edge/ad$n.ts"

    private val adRange =
        "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2026-10-17T12:00:02.000Z\",DURATION=2"

    private fun playlist(vararg parts: String) = (listOf(header) + parts).joinToString("\n") + "\n"

    private fun segments(text: String) = text.lines().filter { it.startsWith("https://") }

    @Test
    fun `playlist without ads stays as it is`() {
        val text = playlist(live(0), live(1), "#EXT-X-TWITCH-PREFETCH:https://edge/live2.ts")
        val result = PlaylistCleaner().clean("a", text)
        assertEquals(text, result.text)
        assertFalse(result.hadAds)
        assertFalse(result.inAdBreak)
    }

    @Test
    fun `running break hands out the last clean playlist again`() {
        val cleaner = PlaylistCleaner()
        val clean = cleaner.clean("a", playlist(live(0), live(1))).text
        val result = cleaner.clean("a", playlist(live(1), adRange, ad(2), ad(3)))
        assertTrue(result.inAdBreak)
        assertEquals(clean, result.text)
    }

    @Test
    fun `break from the start keeps only what came before the ads`() {
        val result = PlaylistCleaner().clean("a", playlist(live(0), adRange, ad(2), ad(3)))
        assertTrue(result.inAdBreak)
        assertEquals(listOf("https://edge/live0.ts"), segments(result.text))
        assertFalse(result.text.contains("stitched"))
        assertTrue(result.text.contains("#EXT-X-MEDIA-SEQUENCE:100\n"))
    }

    @Test
    fun `finished break is cut from the front and the numbering moves on`() {
        val result = PlaylistCleaner().clean(
            "a",
            playlist(
                live(0),
                "#EXT-X-DISCONTINUITY",
                adRange,
                ad(2),
                ad(3),
                "#EXT-X-DISCONTINUITY",
                live(4),
                live(5),
                "#EXT-X-TWITCH-PREFETCH:https://edge/live6.ts",
            ),
        )
        assertFalse(result.inAdBreak)
        assertTrue(result.hadAds)
        assertEquals(listOf("https://edge/live4.ts", "https://edge/live5.ts"), segments(result.text))
        assertTrue(result.text.contains("#EXT-X-MEDIA-SEQUENCE:103\n"))
        assertTrue(result.text.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1\n"))
        assertFalse(result.text.contains("PREFETCH"))
        assertFalse(result.text.contains("stitched"))
        // The sequence lines appear once, after the target duration
        assertEquals(1, result.text.lines().count { it.startsWith("#EXT-X-MEDIA-SEQUENCE") })
    }

    @Test
    fun `ads are found by their date range even with a live title`() {
        val sneaky = "#EXT-X-PROGRAM-DATE-TIME:2026-10-17T12:00:03.000Z\n#EXTINF:2.000,live\nhttps://edge/ad3.ts"
        val result = PlaylistCleaner().clean("a", playlist(live(0), adRange, sneaky, live(6).replace(":06.", ":59.")))
        assertEquals(listOf("https://edge/live6.ts"), segments(result.text))
    }
}
