package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesNumericallyNotAsText() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("0.9.1", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
    }
    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.8.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0-beta", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "2.0.0-beta.3"))
        assertTrue(UpdateChecker.isNewer("2.0.0-beta.2", "2.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("2.0.0-beta.1", "2.0.0-beta.1"))
        assertTrue(UpdateChecker.isNewer("2.0.1-beta.1", "2.0.0"))
    }
    @Test fun notesKeepTheChangelogSectionAsPlainLines() {
        val body = "- **Local Files**: a folder\n  - nested [link](https://x.y)\n\n**Full Changelog**: https://github.com/a/b/compare/v1...v2"
        assertEquals("• Local Files: a folder\n  • nested link", UpdateChecker.cleanNotes(body))
    }
    @Test fun findsTheReleaseApk() {
        val release = org.json.JSONObject("""{"assets":[
            {"name":"checksums.txt","browser_download_url":"https://x/c.txt"},
            {"name":"myspoti-2.0.0-beta.4.apk","browser_download_url":"https://x/myspoti.apk"}]}""")
        assertEquals("https://x/myspoti.apk", UpdateChecker.apkAsset(release))
        assertEquals("", UpdateChecker.apkAsset(org.json.JSONObject("{}")))
    }
    @Test fun hangulBuildsFollowTheBetaReleaseListAndCompareTheirOwnVersions() {
        assertTrue(UpdateChecker.betaChannel("2.0.1-hangul.5"))
        assertTrue(UpdateChecker.isNewer("2.0.1-hangul.5", "2.0.1-hangul.4"))
        assertFalse(UpdateChecker.isNewer("2.0.1-hangul.4", "2.0.1-hangul.5"))
        assertFalse(UpdateChecker.isNewer("2.0.1-hangul.5", "2.0.1-hangul.5"))
    }
    private fun release(version: String, prerelease: Boolean, draft: Boolean = false) = org.json.JSONObject()
        .put("tag_name", "v$version").put("html_url", "https://x/$version").put("prerelease", prerelease).put("draft", draft)
        .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "Myspoti-Hangul-$version.apk")
            .put("browser_download_url", "https://x/$version.apk")))

    /** What a build of [current] is offered from [releases]: the newest it takes, when newer than itself. */
    private fun offered(releases: List<org.json.JSONObject>, current: String) =
        UpdateChecker.newest(releases, current)?.takeIf { UpdateChecker.isNewer(it.version, current) }?.version

    @Test fun hangulBuildsTakeReleasesAndPreReleasesAlike() {
        // hangul.5 (a pre-release) is offered hangul.9 published as a release.
        assertEquals("2.0.1-hangul.9", offered(listOf(release("2.0.1-hangul.9", prerelease = false)), "2.0.1-hangul.5"))
        // Only pre-releases, as before.
        assertEquals("2.0.1-hangul.9", offered(listOf(release("2.0.1-hangul.9", prerelease = true), release("2.0.1-hangul.5", prerelease = true)), "2.0.1-hangul.5"))
        // Both kinds: the highest version, whichever kind it is.
        val mixed = listOf(release("2.0.1-hangul.10", prerelease = false), release("2.0.1-hangul.11", prerelease = true), release("2.0.1-hangul.9", prerelease = false))
        assertEquals("2.0.1-hangul.11", offered(mixed, "2.0.1-hangul.9"))
        assertEquals("https://x/2.0.1-hangul.11.apk", UpdateChecker.newest(mixed, "2.0.1-hangul.9")!!.apkUrl)
        // Nothing newer than the build itself, and drafts never.
        assertNull(offered(listOf(release("2.0.1-hangul.9", prerelease = false), release("2.0.1-hangul.8", prerelease = true)), "2.0.1-hangul.9"))
        assertNull(offered(listOf(release("2.0.1-hangul.12", prerelease = false, draft = true)), "2.0.1-hangul.9"))
    }

    @Test fun upstreamChannelsStayApart() {
        // An upstream beta still takes pre-releases only; a release build is given the latest release alone.
        assertNull(offered(listOf(release("2.0.2", prerelease = false)), "2.0.2-beta.3"))
        assertEquals("2.0.2-beta.4", offered(listOf(release("2.0.2-beta.4", prerelease = true)), "2.0.2-beta.3"))
        assertEquals("2.0.3", offered(listOf(release("2.0.3", prerelease = false)), "2.0.2"))
    }

    @Test fun hangulReleasesSelectOnlyTheirMatchingApk() {
        val release = org.json.JSONObject("""{"tag_name":"v2.0.1-hangul.5","assets":[
            {"name":"myspoti-2.0.1.apk","browser_download_url":"https://x/upstream.apk"},
            {"name":"Myspoti-Hangul-2.0.1-hangul.5.apk","browser_download_url":"https://x/hangul.apk"}]}""")
        assertEquals("https://x/hangul.apk", UpdateChecker.apkAsset(release))
        release.getJSONArray("assets").remove(1)
        assertEquals("", UpdateChecker.apkAsset(release))
    }
}
