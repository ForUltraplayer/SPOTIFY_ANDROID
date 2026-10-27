// @file LocalPlaylistFilesTest.kt
// @author: ForUltraplayer
// @brief Check phone-list export, import into a new list, and refusal of foreign or unreadable data.
package com.music.spotui.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistFilesTest {
    private val item = LocalItem("k1", "myspoti-yt://video/rKQptWhAvO0", "Song", "Artist", "YouTube", 1000, "https://i.ytimg.com/vi/rKQptWhAvO0/hqdefault.jpg")

    @Test fun exportedListComesBackAsANewList() {
        val original = LocalPlaylist("source", "Mixed", listOf(item, item.copy(key = "k2")), 1)
        LocalPlaylists.playlists[original.id] = original
        try {
            val imported = LocalPlaylists.import(LocalPlaylists.export(original.id)!!)
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
