package com.doncc.scrcpygo.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ReleaseParserTest {
    @Test
    fun parsesTagAndAssets() {
        val release = assertNotNull(ReleaseParser.parse(SAMPLE_JSON))
        assertEquals("v1.1.0", release.tagName)
        assertEquals("1.1.0", release.version)
        assertEquals("https://github.com/doncc/scrcpy-go/releases/tag/v1.1.0", release.pageUrl)
        assertEquals(2, release.assets.size)
        assertEquals("scrcpy-go-1.1.0-linux-x64.zip", release.assets[0].name)
        assertEquals("scrcpy-go-1.1.0-windows-x64.zip", release.assets[1].name)
    }

    @Test
    fun ignoresUnrelatedDownloadUrls() {
        val json = SAMPLE_JSON.replace(
            "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-windows-x64.zip",
            "https://example.com/scrcpy-go-1.1.0-windows-x64.zip"
        )
        val release = assertNotNull(ReleaseParser.parse(json))
        assertEquals(1, release.assets.size)
        assertEquals("scrcpy-go-1.1.0-linux-x64.zip", release.assets[0].name)
    }

    @Test
    fun returnsNullForInvalidPayloads() {
        assertNull(ReleaseParser.parse(""))
        assertNull(ReleaseParser.parse("<html>rate limited</html>"))
        assertNull(ReleaseParser.parse("""{"assets": []}"""))
        assertNull(ReleaseParser.parse("""{"tag_name": "latest"}"""))
    }

    @Test
    fun unescapesDownloadUrl() {
        val json = """{"tag_name":"v1.2.0","assets":[{"browser_download_url":"https:\/\/github.com\/doncc\/scrcpy-go\/releases\/download\/v1.2.0\/scrcpy-go-1.2.0-linux-x64.zip"}]}"""
        val release = assertNotNull(ReleaseParser.parse(json))
        assertEquals("scrcpy-go-1.2.0-linux-x64.zip", release.assets.single().name)
    }

    companion object {
        val SAMPLE_JSON = """
            {
              "url": "https://api.github.com/repos/doncc/scrcpy-go/releases/1",
              "assets_url": "https://api.github.com/repos/doncc/scrcpy-go/releases/1/assets",
              "html_url": "https://github.com/doncc/scrcpy-go/releases/tag/v1.1.0",
              "author": {"login": "doncc", "html_url": "https://github.com/doncc"},
              "tag_name": "v1.1.0",
              "name": "",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "url": "https://api.github.com/repos/doncc/scrcpy-go/releases/assets/1",
                  "name": "scrcpy-go-1.1.0-linux-x64.zip",
                  "browser_download_url": "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-linux-x64.zip"
                },
                {
                  "url": "https://api.github.com/repos/doncc/scrcpy-go/releases/assets/2",
                  "name": "scrcpy-go-1.1.0-windows-x64.zip",
                  "browser_download_url": "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-windows-x64.zip"
                }
              ]
            }
        """.trimIndent()
    }
}
