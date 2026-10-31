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

    // A version every test build is older than, so pending() offers it.
    private val known = UpdateChecker.Release("99.0.0", "https://x/99", "notes", "https://x/99.apk")
    private val later = UpdateChecker.Release("99.1.0", "https://x/99.1", "", "https://x/99.1.apk")
    private val hour = 3_600_000L
    // Well after "never checked" (0).
    private val start = 100 * hour
    private fun checkedAt(release: UpdateChecker.Release?, at: Long) = MemoryPreferences().also {
        UpdateChecker.check(it, at, manual = false) { release }
    }

    @Test fun launchCheckWaitsThreeHoursButTheManualOneAsksAtOnce() {
        val prefs = checkedAt(known, start)
        var requests = 0
        assertEquals(known, UpdateChecker.check(prefs, start + 3 * hour - 1, manual = false) { requests++; later })
        assertEquals(0, requests)
        // A release published since the last check is found by the button.
        assertEquals(later, UpdateChecker.check(prefs, start + 3 * hour - 1, manual = true) { requests++; later })
        assertEquals(1, requests)
        // The manual answer counts as a check: the launch waits 3 hours from it.
        assertEquals(later, UpdateChecker.check(prefs, start + 6 * hour - 2, manual = false) { requests++; known })
        assertEquals(1, requests)
        assertEquals(known, UpdateChecker.check(prefs, start + 6 * hour - 1, manual = false) { requests++; known })
        assertEquals(2, requests)
    }

    @Test fun failedCheckKeepsTheKnownReleaseAndTriesAgainNextLaunch() {
        val prefs = checkedAt(known, start)
        val saved = prefs.values.toMap()
        var requests = 0
        assertEquals(known, UpdateChecker.check(prefs, start + 4 * hour, manual = false) { requests++; throw java.io.IOException("HTTP 403") })
        assertEquals(saved, prefs.values)
        // Not counted as a check: the next launch asks again instead of waiting 3 hours.
        assertEquals(known, UpdateChecker.check(prefs, start + 4 * hour + 1, manual = false) { requests++; known })
        assertEquals(2, requests)
    }

    @Test fun failedManualCheckIsReportedNotTakenForUpToDate() {
        val prefs = checkedAt(known, start)
        val saved = prefs.values.toMap()
        try {
            UpdateChecker.check(prefs, start + hour, manual = true) { throw org.json.JSONException("truncated") }
            org.junit.Assert.fail("A failed manual check must not look like \"up to date\"")
        } catch (_: org.json.JSONException) { }
        assertEquals(saved, prefs.values)
    }

    @Test fun successfulCheckWithNothingNewerClearsTheOldOffer() {
        val prefs = checkedAt(known, start)
        assertNull(UpdateChecker.check(prefs, start + 4 * hour, manual = false) { null })
        assertNull(UpdateChecker.check(prefs, start + 4 * hour + 1, manual = false) { known })
    }

    @Test fun manualCheckOffersADismissedReleaseAgain() {
        val prefs = checkedAt(known, start)
        prefs.edit().putString("dismissed", known.version).apply()
        assertNull(UpdateChecker.check(prefs, start + hour, manual = false) { known })
        assertEquals(known, UpdateChecker.check(prefs, start + hour, manual = true) { known })
        // The dismissal stays for the launch prompt.
        assertNull(UpdateChecker.check(prefs, start + hour + 1, manual = false) { known })
    }
}
