package io.github.rymos.turbotube.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    private val release = """
        {"tag_name":"build-12","name":"Turbotube Build 12","assets":[
          {"name":"other.zip","browser_download_url":"https://example.com/other.zip","size":1},
          {"name":"turbotube.apk","browser_download_url":"https://github.com/rymos/turbotube/releases/download/build-12/turbotube.apk","size":23456789}
        ]}
    """

    @Test
    fun readsBuildNumberAndApk() {
        val update = UpdateChecker.parse(Json.parseToJsonElement(release).jsonObject)!!
        assertEquals(12, update.buildNumber)
        assertEquals("Turbotube Build 12", update.name)
        assertEquals("https://github.com/rymos/turbotube/releases/download/build-12/turbotube.apk", update.apkUrl)
        assertEquals(23456789L, update.sizeBytes)
    }

    @Test
    fun ignoresOtherTags() {
        assertNull(UpdateChecker.parse(Json.parseToJsonElement("""{"tag_name":"v1.0","assets":[]}""").jsonObject))
    }

    @Test
    fun onlyReportsNewerBuilds() {
        assertEquals(12, UpdateChecker.parse(Json.parseToJsonElement(release).jsonObject)?.buildNumber)
        assertNull(UpdateChecker.parse(Json.parseToJsonElement(release).jsonObject)?.takeIf { it.buildNumber > 12 })
    }
}
