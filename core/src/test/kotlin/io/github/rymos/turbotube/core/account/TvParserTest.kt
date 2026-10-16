package io.github.rymos.turbotube.core.account

import io.github.rymos.turbotube.core.Channel
import io.github.rymos.turbotube.core.Playlist
import io.github.rymos.turbotube.core.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvParserTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val home = json(
        """
        {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
          "sectionListRenderer":{"contents":[
            {"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"abc123def45",
                "header":{"tileHeaderRenderer":{
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/s.jpg","width":120},{"url":"https://i.ytimg.com/b.jpg","width":480}]},
                  "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"1:02:03"},"style":"DEFAULT"}}]}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Ein Video"},
                  "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Kanal A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCkanalA"}}}]}}}]}},
                           {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"1.234 Aufrufe"}}},
                                                     {"lineItemRenderer":{"text":{"simpleText":"•"}}},
                                                     {"lineItemRenderer":{"text":{"simpleText":"vor 2 Tagen"}}}]}}]}},
                "onSelectCommand":{"watchEndpoint":{"videoId":"abc123def45"}}}},
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","contentId":"PLxyz",
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Meine Liste"},
                  "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"12 Videos"}}}]}}]}},
                "onSelectCommand":{"browseEndpoint":{"browseId":"VLPLxyz"}}}}
            ],"continuations":[{"nextContinuationData":{"continuation":"shelf-token"}}]}}}},
            {"itemSectionRenderer":{"contents":[
              {"lockupViewModel":{"contentId":"zzz999yyy88","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/l.jpg","width":640}]},
                  "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"4:05"}}]}}]}},
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Zweites Video"},
                  "metadata":{"contentMetadataViewModel":{"metadataRows":[
                    {"metadataParts":[{"text":{"content":"Kanal B"}}]},
                    {"metadataParts":[{"text":{"content":"10 Aufrufe"}}]}]}}}}}},
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"abc123def45",
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Doppelt"}}}}}
            ]}}
          ],"continuations":[{"nextContinuationData":{"continuation":"page-token"}}]}}}}}}}
        """,
    )

    @Test
    fun readsTilesAndLockups() {
        val page = TvParser.page(home)
        assertEquals(3, page.items.size)
        val video = page.items[0] as Video
        assertEquals("abc123def45", video.id)
        assertEquals("Ein Video", video.title)
        assertEquals("Kanal A", video.channelName)
        assertEquals("https://www.youtube.com/channel/UCkanalA", video.channelUrl)
        assertEquals(3723L, video.durationSeconds)
        assertEquals("https://i.ytimg.com/b.jpg", video.thumbnailUrl)
        assertEquals("1.234 Aufrufe · vor 2 Tagen", video.uploadedText)

        val playlist = page.items[1] as Playlist
        assertEquals("PLxyz", playlist.id)
        assertEquals(12L, playlist.videoCount)

        val lockup = page.items[2] as Video
        assertEquals("Zweites Video", lockup.title)
        assertEquals("Kanal B", lockup.channelName)
        assertEquals(245L, lockup.durationSeconds)
        assertEquals("https://i.ytimg.com/l.jpg", lockup.thumbnailUrl)
    }

    @Test
    fun takesTheOuterContinuation() {
        assertEquals("page-token", TvParser.page(home).next)
    }

    @Test
    fun emptyPageHasNoNext() {
        val page = TvParser.page(json("""{"continuationContents":{"x":{"continuations":[{"nextContinuationData":{"continuation":"t"}}]}}}"""))
        assertTrue(page.items.isEmpty())
        assertNull(page.next)
    }

    @Test
    fun readsSubscribedChannelsFromTabs() {
        val channels = TvParser.channelTabs(
            json(
                """
                {"contents":{"tvBrowseRenderer":{"content":{"tvSecondaryNavRenderer":{"sections":[{"tvSecondaryNavSectionRenderer":{"tabs":[
                  {"tabRenderer":{"title":"Alle","endpoint":{"browseEndpoint":{"browseId":"FEsubscriptions"}}}},
                  {"tabRenderer":{"title":{"simpleText":"Kanal A"},"thumbnail":{"thumbnails":[{"url":"//yt3.ggpht.com/a","width":88}]},
                    "endpoint":{"browseEndpoint":{"browseId":"UCaaaa"}}}},
                  {"tabRenderer":{"title":{"runs":[{"text":"Kanal B"}]},"endpoint":{"browseEndpoint":{"browseId":"UCbbbb"}}}}
                ]}}]}}}}}
                """,
            ),
        )
        assertEquals(listOf("UCaaaa", "UCbbbb"), channels.map(Channel::id))
        assertEquals("Kanal A", channels[0].name)
        assertEquals("https://yt3.ggpht.com/a", channels[0].avatarUrl)
    }

    @Test
    fun readsSelectedAccount() {
        val account = TvParser.account(
            json(
                """
                {"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
                  {"accountItem":{"accountName":{"simpleText":"Kanal"},"isSelected":false}},
                  {"accountItem":{"accountName":{"simpleText":"Danny"},"isSelected":true,
                    "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/d","width":48}]}}}
                ]}}]}}]}}}}]}
                """,
            ),
        )
        assertEquals("Danny", account?.name)
        assertEquals("https://yt3.ggpht.com/d", account?.avatarUrl)
    }

    @Test
    fun parsesTvPageAndBaseJs() {
        val page = """<script id="base-js" src="/s/_/kabuki/_/js/k=kabuki.base.de.abc/m=base" nonce="x"></script> "visitorData":"CgtWaXNpdG9y""""
        assertEquals("https://www.youtube.com/s/_/kabuki/_/js/k=kabuki.base.de.abc/m=base", TvClientInfo.parseBaseJsUrl(page))
        assertEquals("CgtWaXNpdG9y", TvClientInfo.parseVisitorData(page))
        val js = """foo;var a={clientId:"123-abc.apps.googleusercontent.com",\nzx:"SecretXyz"};"""
        assertEquals(null, TvClientInfo.parseCredentials(js)) // literal backslash-n is not a newline
        val js2 = "foo;var a={clientId:\"123-abc.apps.googleusercontent.com\",\nzx:\"SecretXyz\"};"
        assertEquals("123-abc.apps.googleusercontent.com" to "SecretXyz", TvClientInfo.parseCredentials(js2))
    }
}
