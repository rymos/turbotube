package io.github.rymos.turbotube.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val release = """
        {"tag_name":"build-12","name":"Turbotube Build 12","assets":[
          {"name":"other.zip","browser_download_url":"https://example.com/other.zip","size":1},
          {"name":"turbotube.apk","browser_download_url":"https://github.com/rymos/turbotube/releases/download/build-12/turbotube.apk","size":23456789}
        ]}
    """

    private fun parse(json: String) = UpdateChecker.parse(Json.parseToJsonElement(json).jsonObject)

    private fun parseAll(json: String) = UpdateChecker.parseAll(Json.parseToJsonElement(json).jsonArray)

    @Test
    fun readsBuildNumberAndApk() {
        val update = parse(release)!!
        assertEquals(12, update.buildNumber)
        assertEquals("Turbotube Build 12", update.name)
        assertEquals("https://github.com/rymos/turbotube/releases/download/build-12/turbotube.apk", update.apkUrl)
        assertEquals(23456789L, update.sizeBytes)
    }

    @Test
    fun readsReleaseNotes() {
        val withNotes = """
            {"tag_name":"build-40","body":"- Update-Streifen beim Start\n- Renderer-Crash abgefangen","assets":[
              {"name":"turbotube.apk","browser_download_url":"https://example.com/40.apk","size":5}]}
        """
        assertEquals("- Update-Streifen beim Start\n- Renderer-Crash abgefangen", parse(withNotes)?.notes)
        // Older releases were published without text; the strip then just shows the build number.
        assertNull(parse(release)?.notes)
        assertNull(parse("""{"tag_name":"build-41","body":"   \n","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/41.apk"}]}""")?.notes)
    }

    @Test
    fun ignoresOtherTags() {
        assertNull(parse("""{"tag_name":"v1.0","assets":[]}"""))
        assertNull(parse("""{"tag_name":"build-x","assets":[]}"""))
    }

    @Test
    fun ignoresReleasesWithoutApk() {
        assertNull(parse("""{"tag_name":"build-3","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/a"}]}"""))
    }

    @Test
    fun onlyReportsNewerBuilds() {
        assertEquals(12, parse(release)?.buildNumber)
        assertNull(parse(release)?.takeIf { it.buildNumber > 12 })
    }

    @Test
    fun highestBuildWinsOverReleaseOrder() {
        // A re-run or an out-of-order publish can put an older build in front of the list.
        val releases = """
            [
              {"tag_name":"build-9","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/9.apk","size":1}]},
              {"tag_name":"build-19","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/19.apk","size":2}]},
              {"tag_name":"build-15","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/15.apk","size":3}]}
            ]
        """
        assertEquals(19, newest(releases, newerThan = 10)?.buildNumber)
        assertEquals("https://example.com/19.apk", newest(releases, newerThan = 10)?.apkUrl)
        // Already up to date: nothing to offer.
        assertNull(newest(releases, newerThan = 19))
        assertEquals(22, newest("""[{"tag_name":"build-22","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/22.apk"}]}]""", newerThan = 19)?.buildNumber)
    }

    @Test
    fun skipsDraftsAndVersionTags() {
        val releases = """
            [
              {"tag_name":"build-20","draft":true,"assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/20.apk"}]},
              {"tag_name":"v1.0","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/1.apk"}]},
              {"tag_name":"build-18","assets":[{"name":"turbotube.apk","browser_download_url":"https://example.com/18.apk"}]}
            ]
        """
        assertEquals(listOf(18), parseAll(releases).map { it.buildNumber })
        assertEquals(18, newest(releases, newerThan = 0)?.buildNumber)
    }

    @Test
    fun emptyReleaseListYieldsNoUpdate() {
        assertTrue(parseAll("[]").isEmpty())
        assertNull(newest("[]", newerThan = 0))
    }

    private fun newest(json: String, newerThan: Int) =
        UpdateChecker.newest(Json.parseToJsonElement(json).jsonArray, newerThan)
}
