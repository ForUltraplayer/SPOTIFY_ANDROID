package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
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
    @Test fun ordersBetasAndReleases() {
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2-beta.6"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.6", "2.0.2-beta.7"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.10", "2.0.2-beta.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2", "2.0.2-beta.7"))
        assertFalse(UpdateChecker.isNewer("2.0.2-beta.7", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.0.3-beta.1", "2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.1.0-beta.1", "2.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0.2-beta.1", "2.0.1-beta.9"))
    }
    @Test fun notesKeepTheChangelogSectionAsPlainLines() {
        val body = "- **Local Files**: a folder\n  - nested [link](https://x.y)\n\n**Full Changelog**: https://github.com/a/b/compare/v1...v2"
        assertEquals("• Local Files: a folder\n  • nested link", UpdateChecker.cleanNotes(body))
    }
    @Test fun findsTheReleaseApkOfThisChannelOnly() {
        // An older release: one APK named after the version, whichever channel the release is.
        val legacy = org.json.JSONObject("""{"tag_name":"v2.0.0-beta.4","assets":[
            {"name":"checksums.txt","browser_download_url":"https://x/c.txt"},
            {"name":"myspoti-2.0.0-beta.4.apk","browser_download_url":"https://x/myspoti.apk"}]}""")
        assertEquals("https://x/myspoti.apk", UpdateChecker.apkAsset(legacy, beta = true, abis = arm64))
        assertEquals("https://x/myspoti.apk", UpdateChecker.apkAsset(legacy, beta = false, abis = arm64))
        val both = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"},
            {"name":"myspoti-beta-2.2.1.apk","browser_download_url":"https://x/beta.apk"}]}""")
        assertEquals("https://x/beta.apk", UpdateChecker.apkAsset(both, beta = true, abis = arm64))
        assertEquals("https://x/stable.apk", UpdateChecker.apkAsset(both, beta = false, abis = arm64))
        // Only the other channel's APK, or an unrelated .apk: no link, never the wrong build.
        val other = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"},
            {"name":"something-else.apk","browser_download_url":"https://x/else.apk"}]}""")
        assertEquals("", UpdateChecker.apkAsset(other, beta = true, abis = arm64))
        assertEquals("", UpdateChecker.apkAsset(org.json.JSONObject("{}"), beta = true, abis = arm64))
    }
    @Test fun findsTheApkOfThisPhonesAbi() {
        val split = org.json.JSONObject("""{"tag_name":"v2.3.0-beta.1","assets":[
            {"name":"myspoti-beta-2.3.0-beta.1-armeabi-v7a.apk","browser_download_url":"https://x/beta-v7a.apk"},
            {"name":"myspoti-beta-2.3.0-beta.1-arm64-v8a.apk","browser_download_url":"https://x/beta-arm64.apk"},
            {"name":"myspoti-stable-2.3.0-beta.1-arm64-v8a.apk","browser_download_url":"https://x/stable-arm64.apk"}]}""")
        // A 64-bit phone that also runs armeabi-v7a takes arm64-v8a, whatever the assets' order.
        assertEquals("https://x/beta-arm64.apk", UpdateChecker.apkAsset(split, beta = true, abis = arm64))
        assertEquals("https://x/beta-v7a.apk", UpdateChecker.apkAsset(split, beta = true, abis = listOf("armeabi-v7a", "armeabi")))
        assertEquals("https://x/stable-arm64.apk", UpdateChecker.apkAsset(split, beta = false, abis = arm64))
        // The other channel's APK for this ABI only: no link.
        assertEquals("", UpdateChecker.apkAsset(split, beta = false, abis = listOf("armeabi-v7a")))
        // An ABI the releases do not carry (x86 emulator, Chromebook): no link, the release page opens.
        assertEquals("", UpdateChecker.apkAsset(split, beta = true, abis = listOf("x86_64", "x86")))
        assertEquals("", UpdateChecker.apkAsset(split, beta = true, abis = emptyList()))
        // A release from before the ABI split: the channel's APK without a suffix, as before.
        val single = org.json.JSONObject("""{"tag_name":"v2.2.1","assets":[
            {"name":"myspoti-stable-2.2.1.apk","browser_download_url":"https://x/stable.apk"}]}""")
        assertEquals("https://x/stable.apk", UpdateChecker.apkAsset(single, beta = false, abis = listOf("armeabi-v7a")))
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

    // A version every test build is older than, so pending() offers it.
    private val known = UpdateChecker.Release("99.0.0", "https://x/99", "notes", "https://x/99.apk")
    private val later = UpdateChecker.Release("99.1.0", "https://x/99.1", "", "https://x/99.1.apk")
    private val start = 100 * 3_600_000L
    private fun checkedAt(release: UpdateChecker.Release?, at: Long) = MemoryPreferences().also {
        UpdateChecker.store(it, UpdateChecker.Fetch.Ok(release), at)
    }

    @Test fun failedCheckKeepsTheKnownReleaseAndIsNotCountedAsACheck() {
        val prefs = checkedAt(known, start)
        UpdateChecker.store(prefs, UpdateChecker.Fetch.Err(rateLimited = true), start + 1)
        assertEquals(known, UpdateChecker.pending(prefs))
        // Only the retry clock moves; the last successful check stays where it was.
        assertEquals(start, prefs.getLong("checked_at", 0L))
        assertEquals(start + 1, prefs.getLong("tried_at", 0L))
        UpdateChecker.store(prefs, UpdateChecker.Fetch.Ok(later), start + 2)
        assertEquals(later, UpdateChecker.pending(prefs))
        assertEquals(0L, prefs.getLong("tried_at", -1L))
    }

    @Test fun successfulCheckWithNothingNewerClearsTheOldOffer() {
        val prefs = checkedAt(known, start)
        UpdateChecker.store(prefs, UpdateChecker.Fetch.Ok(null), start + 1)
        assertNull(UpdateChecker.pending(prefs, manual = true))
    }

    @Test fun manualCheckOffersADismissedReleaseAgain() {
        val prefs = checkedAt(known, start)
        prefs.edit().putString("dismissed", known.version).apply()
        assertNull(UpdateChecker.pending(prefs))
        assertEquals(known, UpdateChecker.pending(prefs, manual = true))
    }
}
