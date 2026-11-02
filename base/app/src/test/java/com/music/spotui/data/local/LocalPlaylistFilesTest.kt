// @file LocalPlaylistFilesTest.kt
// @brief Check phone-list export, import into a new list (also of a PC list file), and refusal of foreign or unreadable data.
package com.music.spotui.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SPOTIFY_SONG = """{"kind":"spotify","uri":"spotify:track:6x7SB38tuekpu4xpH9OIPY","title":"地球儀","artist":"Kenshi Yonezu","duration":273,"entryKey":"recording-one","album":"Album","image":""}"""

class LocalPlaylistFilesTest {
    private val item = LocalItem("k1", "myspoti-yt://video/rKQptWhAvO0", "Song", "Artist", "YouTube", 1000, "https://i.ytimg.com/vi/rKQptWhAvO0/hqdefault.jpg")

    @Test fun exportedListComesBackAsANewList() {
        val original = LocalPlaylist("source", "Mixed", listOf(item, item.copy(key = "k2")), 1)
        LocalPlaylists.playlists[original.id] = original
        try {
            val result = LocalPlaylists.import(LocalPlaylists.export(original.id)!!)
            val imported = result.playlist
            assertEquals(0, result.leftOut)
            assertNotEquals(original.id, imported.id)
            assertEquals("Mixed", imported.name)
            assertEquals(original.items.map { it.uri }, imported.items.map { it.uri })
            // Each occurrence gets its own key again: the same video can be there twice.
            assertEquals(2, imported.items.map { it.key }.toSet().size)
            assertTrue(imported.items.none { it.key == "k1" || it.key == "k2" })
            assertEquals(original, LocalPlaylists.playlists[original.id])
            LocalPlaylists.playlists.remove(imported.id)
        } finally { LocalPlaylists.playlists.remove(original.id) }
    }

    @Test fun refusesFilesItDidNotMake() {
        assertThrows(IllegalArgumentException::class.java) { LocalPlaylists.import("{\"playlists\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { LocalPlaylists.import("not json") }
        val big = JSONObject().put("format", "myspoti-hangul-local-playlist").put("version", 1)
            .put("name", "x".repeat(LocalPlaylists.MAX_FILE_BYTES)).toString()
        assertThrows(IllegalArgumentException::class.java) { LocalPlaylists.import(big) }
    }

    /** What the PC version writes (youtube-library.js documentFor): Spotify songs, YouTube links, their mixed order and lyric search names. */
    private fun pcFile(tracks: String, order: String, native: String = SPOTIFY_SONG, schema: Int = 1) =
        """{"format":"SpotifyHangulMixedPlaylist","schema":$schema,"exportedAt":"2026-10-27T00:00:00.000Z","playlist":{"name":"PC 혼합 목록",
        "sourceUri":"spotify:playlist:5C3W78fpJbRdLpbr7kvAMe","nativeTracks":[$native],"tracks":[$tracks],"order":[$order]}}"""

    @Test fun pcListBringsItsYouTubeLinksInTheMixedOrder() {
        val tracks = """{"kind":"youtube","videoId":"rKQptWhAvO0","title":"Cover","artist":"Singer","duration":194.5,"after":"recording-one","lyricTitle":"東京テディベア","lyricArtist":"Neru"},
            {"kind":"youtube","videoId":"abcdefghijk","title":"Plain","artist":"Uploader","duration":60,"after":""}"""
        val result = LocalPlaylists.import("\uFEFF" + pcFile(tracks, "\"yt:abcdefghijk\",\"sp:recording-one\",\"yt:rKQptWhAvO0\""))
        try {
            assertEquals("PC 혼합 목록", result.playlist.name)
            // The Spotify song cannot be in a phone list: counted, so the screen can say so.
            assertEquals(1, result.leftOut)
            assertEquals(listOf(
                LocalItem("", "myspoti-yt://video/abcdefghijk", "Plain", "Uploader", "YouTube", 60_000, "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"),
                // The lyric search name is what the phone shows and looks the lyrics up by.
                LocalItem("", "myspoti-yt://video/rKQptWhAvO0", "東京テディベア", "Neru", "YouTube", 194_500, "https://i.ytimg.com/vi/rKQptWhAvO0/hqdefault.jpg"),
            ), result.playlist.items.map { it.copy(key = "") })
            assertEquals(2, result.playlist.items.map { it.key }.toSet().size)
            assertTrue(result.playlist.items.none { it.key == "rKQptWhAvO0" || it.key == "abcdefghijk" })
            // Exported again, it is this app's own list file.
            assertEquals("myspoti-hangul-local-playlist", JSONObject(LocalPlaylists.export(result.playlist.id)!!).getString("format"))
        } finally { LocalPlaylists.playlists.remove(result.playlist.id) }
    }

    @Test fun pcListEntriesThatAreNotValidLinksAreLeftOut() {
        val tracks = """{"kind":"youtube","videoId":"rKQptWhAvO0","title":7,"artist":null,"duration":-5},
            {"kind":"youtube","videoId":"rKQptWhAvO0","title":"Again"},
            {"kind":"youtube","videoId":"https://youtu.be/rKQptWhAvO0"},
            {"kind":"spotify","videoId":"abcdefghijk"}, 5,
            {"kind":"youtube","videoId":"abcdefghijk","title":"${"t".repeat(400)}","duration":"60"}"""
        val result = LocalPlaylists.import(pcFile(tracks, "\"yt:zzzzzzzzzzz\",7,\"sp:recording-one\",\"lo:x\""))
        try {
            // The Spotify song, the repeated video, the bad ID, the Spotify kind and the number.
            assertEquals(5, result.leftOut)
            val (first, second) = result.playlist.items
            // Not text or out of range: the video ID and no length, never "7" or "null".
            assertEquals(Triple("rKQptWhAvO0", "", 0L), Triple(first.title, first.artist, first.durationMs))
            assertEquals(300, second.title.length)
            assertEquals(0L, second.durationMs)
            assertEquals(listOf("myspoti-yt://video/rKQptWhAvO0", "myspoti-yt://video/abcdefghijk"), result.playlist.items.map { it.uri })
        } finally { LocalPlaylists.playlists.remove(result.playlist.id) }
    }

    @Test fun pcListOverTheLimitsOrOfAnotherSchemaIsRefused() {
        val link = """{"kind":"youtube","videoId":"rKQptWhAvO0"}"""
        fun refused(text: String) = assertThrows(IllegalArgumentException::class.java) { LocalPlaylists.import(text) }.message
        assertEquals(LocalPlaylists.REFUSED_FOREIGN, refused(pcFile(link, "", schema = 2)))
        assertEquals(LocalPlaylists.REFUSED_FOREIGN, refused("""{"format":"SpotifyHangulMixedPlaylist","schema":1,"playlist":[]}"""))
        assertEquals(LocalPlaylists.REFUSED_TOO_MANY, refused(pcFile(List(2001) { link }.joinToString(","), "")))
        assertEquals(LocalPlaylists.REFUSED_TOO_MANY, refused(pcFile(link, "", native = List(2001) { SPOTIFY_SONG }.joinToString(","))))
        assertEquals(LocalPlaylists.REFUSED_TOO_LARGE, refused(pcFile(link, "\"" + "x".repeat(LocalPlaylists.MAX_FILE_BYTES) + "\"")))
    }

    @Test fun linkAddedUnderASpotifyPlaylistIsKeptAcrossRestarts() {
        // What the playlist page's "+" and menu store: the link under the Spotify playlist's id.
        LocalPlaylists.additions["37i9dQZF1DXcBWIGoYBM5M"] = listOf(item)
        try {
            val saved = LocalPlaylists.write()
            LocalPlaylists.additions.clear()
            assertTrue(LocalPlaylists.read(saved))
            assertEquals(listOf(item), LocalPlaylists.additions["37i9dQZF1DXcBWIGoYBM5M"])
        } finally { LocalPlaylists.additions.remove("37i9dQZF1DXcBWIGoYBM5M") }
    }

    @Test fun storeOfAnotherVersionIsReportedUnreadable() {
        // init() keeps such a file aside instead of letting the next change replace it.
        assertFalse(LocalPlaylists.read("{\"version\":99,\"playlists\":[]}"))
        assertTrue(LocalPlaylists.read("{\"version\":1,\"playlists\":[]}"))
    }
}
