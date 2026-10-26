// @file LyricsPlusResolverTest.kt
// @author: ForUltraplayer
// @brief Verify cover identity, normalized candidates, throttling and bounded network behavior.
package com.music.spotui.data.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class LyricsPlusResolverTest {
    private val track = LyricsPlusResolver.Query("テスト (feat. Guest)", "25時、ナイトコードで。", "Album", 133_000)
    private fun params(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    private fun payload(title: String = "テスト", artist: String = track.artist,
                        album: String = track.album, duration: String = "2:13.000", end: Long = 130_000): String =
        JSONObject().put("metadata", JSONObject().put("title", title).put("artist", artist).put("album", album)
            .put("totalDuration", duration).put("source", "QQ Music"))
            .put("lyrics", JSONArray().put(JSONObject().put("time", 1000).put("duration", end - 1000).put("text", "あいうえお")))
            .toString()
    private fun reply(body: String) = LyricsPlusResolver.Reply(200, body)

    @Test fun exactRecordingReturnsFirstWithoutChangingTimestamps() {
        var calls = 0
        val resolver = LyricsPlusResolver({ _, _ -> calls++; reply(payload()) })
        val result = resolver.resolve(track)
        assertEquals(1, calls)
        assertEquals(1000L, result.lyrics!!.lines.single().timeMs)
        assertFalse(result.retryable)
    }

    @Test fun featOnlyCandidatePreservesCoverArtistAlbumAndDuration() {
        val asked = ArrayList<Map<String, String>>()
        val resolver = LyricsPlusResolver({ url, _ ->
            val query = params(url); asked += query
            if (query["title"] == track.title) LyricsPlusResolver.Reply(404) else reply(payload())
        })
        assertNotNull(resolver.resolve(track).lyrics)
        assertEquals(listOf(track.title, track.title, "テスト"), asked.map { it["title"] })
        assertTrue(asked.all { it["artist"] == track.artist && it["album"] == "Album" && it["duration"] == "133.0" })
    }

    @Test fun jointArtistCandidatePreservesFullWidthSearchPunctuation() {
        val info = track.copy(title = "我らテスト！", artist = "MORE MORE JUMP!, Hatsune Miku")
        val resolver = LyricsPlusResolver({ url, _ ->
            val query = params(url)
            assertEquals(info.title, query["title"])
            if (query["artist"] == "MORE MORE JUMP!") reply(payload(info.title, "MORE MORE JUMP！/初音未来"))
            else LyricsPlusResolver.Reply(404)
        })
        assertNotNull(resolver.resolve(info).lyrics)
    }

    @Test fun japaneseCommaIsPartOfTheUnitName() {
        assertTrue(LyricsPlusResolver.sameRecording(JSONObject().put("title", "テスト")
            .put("artist", "25時、ナイトコードで。/宵崎奏/巡音流歌"), track))
    }

    @Test fun liveAndRemixVersionLabelsArePreserved() {
        assertEquals("テスト (Live)", LyricsPlusResolver.cleanTitle("テスト (Live) (feat. Guest)"))
        assertEquals("テスト - Remix", LyricsPlusResolver.cleanTitle("テスト - Remix （feat. Guest）"))
        assertFalse(LyricsPlusResolver.sameRecording(JSONObject().put("title", "テスト")
            .put("artist", track.artist), track.copy(title = "テスト (Live)")))
    }

    @Test fun wrongIdentityDoesNotPreventTryingAnotherMirror() {
        var calls = 0
        val resolver = LyricsPlusResolver({ _, _ -> calls++; reply(payload(artist = if (calls == 1) "Other Singer" else track.artist)) })
        assertNotNull(resolver.resolve(track).lyrics)
        assertEquals(2, calls)
    }

    @Test fun differentAlbumDurationAndTitlePrefixAreRejected() {
        for (body in listOf(payload(album = "Live Album"), payload(duration = "1:30.000"), payload(title = "テスト Again"))) {
            assertNull(LyricsPlusResolver({ _, _ -> reply(body) }).resolve(track).lyrics)
        }
    }

    @Test fun missingIdentityRequiresCorroborationFromTheSameSource() {
        val raw = JSONObject(payload()).put("metadata", JSONObject().put("source", "QQ Music")).toString()
        val resolver = LyricsPlusResolver({ url, _ ->
            if (URI(url).path == "/v1/metadata/get") reply(JSONObject().put("metadata", JSONObject()
                .put("name", "テスト").put("artistName", "25時、ナイトコードで。/Guest")
                .put("source", "QQ Music").put("durationMs", 133_000)).toString()) else reply(raw)
        })
        assertNotNull(resolver.resolve(track).lyrics)
    }

    @Test fun anotherSourceCannotValidateMissingIdentity() {
        val raw = JSONObject(payload()).put("metadata", JSONObject().put("source", "QQ Music")).toString()
        val resolver = LyricsPlusResolver({ url, _ ->
            if (URI(url).path == "/v1/metadata/get") reply(JSONObject().put("metadata", JSONObject()
                .put("name", "テスト").put("artistName", track.artist).put("source", "Other")).toString()) else reply(raw)
        })
        assertNull(resolver.resolve(track).lyrics)
    }

    @Test fun truncatedNormalizedRecordingIsRejected() {
        val resolver = LyricsPlusResolver({ url, _ ->
            if (params(url)["title"] == track.title) LyricsPlusResolver.Reply(404) else reply(payload(end = 40_000))
        })
        assertNull(resolver.resolve(track).lyrics)
    }

    @Test fun rateLimitAndOverloadSkipFurtherSongsUntilRetryAfterExpires() {
        var at = 1000L
        var calls = 0
        var recovered = false
        val resolver = LyricsPlusResolver({ url, _ ->
            calls++
            if (recovered) reply(payload()) else LyricsPlusResolver.Reply(if (URI(url).host.contains("binimum")) 429 else 503, retryAfter = "120")
        }, { at })
        assertTrue(resolver.resolve(track).retryable)
        assertTrue(resolver.resolve(track.copy(title = "Other")).retryable)
        assertEquals(2, calls)
        at += 120_001; recovered = true
        assertNotNull(resolver.resolve(track).lyrics)
        assertEquals(3, calls)
    }

    @Test fun missingLyricsAndTemporaryFailuresRemainDistinct() {
        assertFalse(LyricsPlusResolver({ _, _ -> LyricsPlusResolver.Reply(404) }).resolve(track).retryable)
        assertTrue(LyricsPlusResolver({ _, _ -> LyricsPlusResolver.Reply(502) }).resolve(track).retryable)
        assertTrue(LyricsPlusResolver({ _, _ -> throw java.io.IOException("network unavailable") }).resolve(track).retryable)
    }

    @Test fun totalLookupBudgetStopsFurtherRequests() {
        var at = 0L
        var calls = 0
        val resolver = LyricsPlusResolver({ _, timeout ->
            assertTrue(timeout in 1..4500); calls++; at += 9000; LyricsPlusResolver.Reply(404)
        }, { at })
        assertTrue(resolver.resolve(track).retryable)
        assertEquals(2, calls)
    }

    @Test fun interruptionStopsBeforeAnotherRequest() {
        var calls = 0
        val resolver = LyricsPlusResolver({ _, _ -> calls++; Thread.currentThread().interrupt(); LyricsPlusResolver.Reply(404) })
        try {
            resolver.resolve(track)
            fail("Interrupted lookup must stop")
        } catch (_: InterruptedException) {
            assertEquals(1, calls)
        } finally { Thread.interrupted() }
    }

    @Test fun retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(120_000L, LyricsPlusResolver.retryDelay("120", 0))
        assertEquals(120_000L, LyricsPlusResolver.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertEquals(60_000L, LyricsPlusResolver.retryDelay("invalid", 0))
    }
}
