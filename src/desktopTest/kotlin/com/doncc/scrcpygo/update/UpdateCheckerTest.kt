package com.doncc.scrcpygo.update

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun reportsNoUpdateWhenRemoteIsOlder() {
        val result = evaluate(releaseJson("v1.0.1", "linux"), current = "1.0.2")
        assertEquals(UpdateCheckResult.NoUpdate, result)
    }

    @Test
    fun reportsNoUpdateWhenVersionsAreEqual() {
        val result = evaluate(releaseJson("v1.0.2", "linux"), current = "1.0.2")
        assertEquals(UpdateCheckResult.NoUpdate, result)
    }

    @Test
    fun reportsAvailableWithMatchingAsset() {
        val result = evaluate(releaseJson("v1.1.0", "linux"), current = "1.0.2")
        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.1.0", available.newVersion)
        assertEquals("1.0.2", available.currentVersion)
        assertTrue(available.downloadUrl!!.endsWith("scrcpy-go-1.1.0-linux-x64.zip"))
        assertEquals("https://github.com/doncc/scrcpy-go/releases/tag/v1.1.0", available.pageUrl)
    }

    @Test
    fun skipsVersionAlreadyMarked() {
        val result = evaluate(releaseJson("v1.1.0", "linux"), current = "1.0.2", skipped = "1.1.0")
        assertEquals(UpdateCheckResult.NoUpdate, result)
    }

    @Test
    fun stillPromptsForVersionNewerThanSkipped() {
        val result = evaluate(releaseJson("v1.2.0", "linux"), current = "1.0.2", skipped = "1.1.0")
        assertIs<UpdateCheckResult.Available>(result)
    }

    @Test
    fun corruptSkipRecordDoesNotBlockUpdate() {
        val result = evaluate(releaseJson("v1.1.0", "linux"), current = "1.0.2", skipped = "garbage")
        assertIs<UpdateCheckResult.Available>(result)
    }

    @Test
    fun missingAssetStillOffersReleasePage() {
        val result = evaluate("""{"tag_name":"v1.1.0","assets":[]}""", current = "1.0.2")
        val available = assertIs<UpdateCheckResult.Available>(result)
        assertNull(available.downloadUrl)
        assertEquals("https://github.com/doncc/scrcpy-go/releases/tag/v1.1.0", available.pageUrl)
    }

    @Test
    fun fetchFailureIsSilent() {
        val result = UpdateChecker.evaluate(
            currentVersion = "1.0.2",
            skippedVersion = null,
            platform = "linux",
            arch = "x64",
            fetcher = ReleaseFetcher { throw IOException("offline") }
        )
        assertEquals(UpdateCheckResult.Failed, result)
    }

    @Test
    fun invalidPayloadIsSilent() {
        assertEquals(UpdateCheckResult.Failed, evaluate("<html>blocked</html>", current = "1.0.2"))
    }

    private fun evaluate(
        json: String,
        current: String,
        skipped: String? = null
    ): UpdateCheckResult = UpdateChecker.evaluate(
        currentVersion = current,
        skippedVersion = skipped,
        platform = "linux",
        arch = "x64",
        fetcher = ReleaseFetcher { json }
    )

    private fun releaseJson(tag: String, platform: String): String {
        val version = tag.removePrefix("v")
        val fileName = "scrcpy-go-" + version + "-" + platform + "-x64.zip"
        val downloadUrl = "https://github.com/doncc/scrcpy-go/releases/download/" + tag + "/" + fileName
        val quote = "\""
        return "{" + quote + "tag_name" + quote + ":" + quote + tag + quote + "," +
            quote + "assets" + quote + ": [{" + quote + "name" + quote + ":" + quote + fileName + quote + "," +
            quote + "browser_download_url" + quote + ":" + quote + downloadUrl + quote + "}]}"
    }
}
