// @file MixedOrderTest.kt
// @brief Check the mixed playlist order: merging, persistence, Spotify-side changes and the order songs play in.
package com.music.spotui.data.local

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedOrderTest {
    private fun song(n: Int) = SongsModel(id = n, title = "Song $n", album = "", singer = "", coverUri = "", url = "spotify:track:t$n",
        spotifyTrackId = "t$n", rowUid = "r$n")
    private fun link(name: String) = LocalItem(name, "myspoti-yt://video/$name", name, "", "YouTube", 1000, "")

    private val songs = listOf(song(1), song(2), song(3))
    private val a = link("a")
    private val b = link("b")

    private fun keys(rows: List<MixedRow>) = rows.map { it.key }

    @Test fun earlierAdditionsFollowSpotifysOrder() {
        assertEquals(listOf("sp:r1", "sp:r2", "sp:r3", "lo:a", "lo:b"), keys(MixedOrder.merge(songs, listOf(a, b), emptyList())))
    }

    @Test fun movedRowsKeepTheirPlace() {
        val start = MixedOrder.merge(songs, listOf(a, b), emptyList())
        // The link a moved before the first song, then b between songs 1 and 2.
        val once = MixedOrder.merge(songs, listOf(a, b), MixedOrder.move(start, 3, 0))
        val twice = MixedOrder.move(once, 4, 2)
        assertEquals(listOf("lo:a", "sp:r1", "lo:b", "sp:r2", "sp:r3"), keys(MixedOrder.merge(songs, listOf(a, b), twice)))
    }

    @Test fun savedOrderSurvivesARestart() {
        val id = "mixedRestart"
        LocalPlaylists.additions[id] = listOf(a, b)
        LocalPlaylists.orders[id] = listOf("lo:a", "sp:r1", "lo:b", "sp:r2", "sp:r3")
        try {
            val saved = LocalPlaylists.write()
            LocalPlaylists.additions.remove(id); LocalPlaylists.orders.remove(id)
            assertTrue(LocalPlaylists.read(saved))
            assertEquals(listOf("lo:a", "sp:r1", "lo:b", "sp:r2", "sp:r3"),
                keys(MixedOrder.merge(songs, LocalPlaylists.additions[id].orEmpty(), LocalPlaylists.orders[id].orEmpty())))
        } finally { LocalPlaylists.additions.remove(id); LocalPlaylists.orders.remove(id) }
    }

    @Test fun storeFromBeforeMixedOrdersStillReads() {
        val id = "mixedOld"
        val old = """{"version":1,"playlists":[],"additions":{"$id":[{"key":"a","uri":"myspoti-yt://video/a","title":"a"}]}}"""
        try {
            assertTrue(LocalPlaylists.read(old))
            assertNull(LocalPlaylists.orders[id])
            assertEquals(listOf("sp:r1", "sp:r2", "sp:r3", "lo:a"), keys(MixedOrder.merge(songs, LocalPlaylists.additions[id].orEmpty(), emptyList())))
        } finally { LocalPlaylists.additions.remove(id) }
    }

    @Test fun spotifySideChangesKeepTheRestInPlace() {
        val order = listOf("lo:a", "sp:r1", "lo:b", "sp:r2", "sp:r3")
        // Song 2 removed on Spotify, song 4 added there (at its end).
        val changed = listOf(song(1), song(3), song(4))
        assertEquals(listOf("lo:a", "sp:r1", "lo:b", "sp:r3", "sp:r4"), keys(MixedOrder.merge(changed, listOf(a, b), order)))
        // The same song twice in the playlist: each row keeps its own place.
        val twice = listOf(song(1).copy(rowUid = ""), song(1).copy(rowUid = ""), song(2))
        assertEquals(listOf("sp:t1", "sp:t1#2", "sp:r2"), MixedOrder.spotifyRows(twice).map { it.key })
    }

    /**
     * Plays [rows] from [start] as the players do: a phone run plays whole, a Spotify song hands over
     * just before its end when a phone row comes next, else Spotify goes on in its order by itself
     * (wrapping when the list repeats, its autoplay after the last song).
     */
    private fun simulate(rows: List<MixedRow>, start: Int, repeatAll: Boolean = false, limit: Int = rows.size): List<String> {
        val played = mutableListOf<String>()
        val engine = MixedOrder.spotifyOrder(rows)
        var at: Int? = start
        while (at != null && played.size < limit) {
            if (rows[at] is MixedRow.Local) {
                val runStart = MixedOrder.runStart(rows, at)
                played += MixedOrder.localRun(rows, runStart).drop(at - runStart).map { it.key }
                at = MixedOrder.afterRun(rows, runStart, repeatAll)
                continue
            }
            played += rows[at].key
            val next = MixedOrder.nextRow(rows, at, repeatAll)
            if (next != null && rows[next] is MixedRow.Local) { at = next; continue }
            val following = engine.getOrNull(engine.indexOf(rows[at]) + 1) ?: if (repeatAll) engine.first() else null
            at = MixedOrder.engineMoved(rows, at, following?.song?.spotifyTrackId ?: "autoplay-song", repeatAll)
        }
        return played.take(limit)
    }

    private val mixedRows get() = MixedOrder.merge(songs, listOf(a, b), listOf("lo:a", "sp:r1", "lo:b", "sp:r2", "sp:r3"))

    @Test fun songsPlayInTheOrderShown() {
        val rows = mixedRows
        assertEquals(keys(rows), simulate(rows, 0))
        assertEquals(keys(rows).drop(1), simulate(rows, 1))
        // Links at the end play after Spotify's last song, before its autoplay.
        val atEnd = MixedOrder.merge(songs, listOf(a, b), emptyList())
        assertEquals(keys(atEnd), simulate(atEnd, 0))
        assertEquals(listOf("lo:b"), simulate(atEnd, 4))
    }

    @Test fun repeatedListPlaysItsPhoneRowsAtBothEnds() {
        val rows = MixedOrder.merge(songs, listOf(a, b), listOf("lo:a", "sp:r1", "sp:r2", "sp:r3", "lo:b"))
        assertEquals(keys(rows) + keys(rows), simulate(rows, 0, repeatAll = true, limit = rows.size * 2))
        // The engine wrapping around by itself (the switch came too late) still gives the phone's rows their turn.
        assertEquals(4, MixedOrder.engineMoved(rows, 3, "t1", repeatAll = true))
        assertEquals(0, MixedOrder.afterRun(rows, 4, repeatAll = true))
        assertNull(MixedOrder.afterRun(rows, 4, repeatAll = false))
    }

    @Test fun engineMovingOnLetsThePhoneRowsBeforeItPlay() {
        val rows = mixedRows
        // Spotify went from song 1 to song 2 by itself: the link b between them plays first.
        assertEquals(2, MixedOrder.engineMoved(rows, 1, "t2"))
        // Song 3 picked in the queue: the list goes on from it, the links are not forced in.
        assertEquals(4, MixedOrder.engineMoved(rows, 1, "t3"))
        // A song outside the list after the last one: the list is over.
        assertNull(MixedOrder.engineMoved(rows, 4, "elsewhere"))
    }

    @Test fun previousGoesToTheRowShownBefore() {
        val rows = mixedRows
        val engine = MixedOrder.spotifyOrder(rows)
        val visited = mutableListOf(rows.last().key)
        var at = rows.lastIndex
        while (true) {
            val target = MixedOrder.previousRow(at) ?: break
            if (rows[target] is MixedRow.Local) {
                // The phone's row plays from its run, at its own place in it.
                val runStart = MixedOrder.runStart(rows, target)
                assertEquals(rows[target], MixedOrder.localRun(rows, runStart)[target - runStart])
            } else if (rows[at] is MixedRow.Spotify) {
                // Two Spotify songs: the engine's own previous goes to the same song.
                assertEquals(rows[target], engine[engine.indexOf(rows[at]) - 1])
            }
            visited += rows[target].key
            at = target
        }
        assertEquals(keys(rows).reversed(), visited)
        // The phone's row right before a Spotify song, and a Spotify song right before a phone row.
        assertEquals(2, MixedOrder.previousRow(3))
        assertEquals(1, MixedOrder.previousRow(2))
        assertNull(MixedOrder.previousRow(0))
    }

    @Test fun shufflePlaysTheShuffledOrderWhole() {
        val rows = mixedRows
        repeat(20) { seed ->
            val shuffled = MixedOrder.shuffled(rows, 2, kotlin.random.Random(seed))
            assertEquals("lo:b", shuffled.first().key)
            assertEquals(keys(rows).toSet(), keys(shuffled).toSet())
            assertEquals(keys(shuffled), simulate(shuffled, 0))
        }
    }
}
