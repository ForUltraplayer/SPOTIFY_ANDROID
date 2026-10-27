// @file MixedNavigatorTest.kt
// @author: ForUltraplayer
// @brief Walk mixed lists back and forth with both players and check the row playing is always the one shown.
package com.music.spotui.data.local

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixedNavigatorTest {
    private fun song(n: Int) = SongsModel(id = n, title = "Song $n", album = "", singer = "", coverUri = "", url = "spotify:track:t$n",
        spotifyTrackId = "t$n", rowUid = "r$n")
    private fun link(name: String) = LocalItem(name, "myspoti-yt://video/$name", name, "", "YouTube", 1000, "")

    /** A list from a layout such as "A y B": capitals are Spotify songs, small letters phone rows. */
    private fun rows(layout: String): List<MixedRow> {
        val parts = layout.split(" ")
        val songs = parts.filter { it[0].isUpperCase() }.map { song(it[0].code) }
        val items = parts.filter { it[0].isLowerCase() }.map { link(it) }
        val order = parts.map { if (it[0].isUpperCase()) "sp:r${it[0].code}" else "lo:$it" }
        return MixedOrder.merge(songs, items, order)
    }

    /** The two players as the app drives them: Spotify follows the order it was given, the local player its run. */
    private class Players(rows: List<MixedRow>, start: Int, repeatAll: Boolean = false) {
        val nav = MixedNavigator(rows) { repeatAll }
        private var run: List<MixedRow.Local>? = null
        private var runIndex = 0
        /** The row of the Spotify song the engine is on (playing, or paused while the local player plays). */
        private var engineAt = -1
        private val engine get() = MixedOrder.spotifyOrder(nav.rows)
        private val repeat = repeatAll
        var over = false

        init { apply(nav.start(start)) }

        private fun apply(action: MixedNavigator.Action?) {
            when (action) {
                is MixedNavigator.Action.PlayRun -> { run = MixedOrder.localRun(nav.rows, action.start); runIndex = action.offset }
                is MixedNavigator.Action.PlaySpotify -> { run = null; engineAt = action.index }
                null -> Unit
            }
        }

        fun playing(): MixedRow = run?.get(runIndex) ?: nav.rows[engineAt]

        /** The engine goes on to the song [step] places away in its order (its own Next, Previous or a song's end). */
        private fun engineGoes(step: Int) {
            val i = engine.indexOf(nav.rows[engineAt]) + step
            val to = engine.getOrNull(i) ?: if (repeat && step > 0) engine.first() else null
            if (to == null) {
                // Past its last song Spotify goes on with its autoplay, a song outside the list.
                if (step > 0) apply(nav.engineMoved("autoplay-song"))
                if (run == null || nav.over) over = true
                return
            }
            engineAt = nav.rows.indexOf(to)
            apply(nav.engineMoved(to.song.spotifyTrackId))
        }

        private fun localEnds() {
            val r = run!!
            if (runIndex + 1 < r.size) { runIndex++; nav.localMoved(runIndex); return }
            val action = nav.runEnded()
            if (action == null) over = true else apply(action)
        }

        /** The song playing ends by itself. */
        fun songEnds() = if (run != null) localEnds() else engineGoes(1)

        fun next() {
            if (run != null) return localEnds()
            nav.next()?.let { apply(it); return }
            engineGoes(1)
        }

        fun previous(positionMs: Long = 0L) {
            val r = run
            nav.previous(positionMs)?.let { apply(it); return }
            if (positionMs > MixedNavigator.RESTART_MS) return
            if (r != null) { if (runIndex > 0) { runIndex--; nav.localMoved(runIndex) } }
            else if (engine.indexOf(nav.rows[engineAt]) > 0) engineGoes(-1)
        }
    }

    private fun Players.at(): String = playing().key

    @Test fun backAndForthAcrossALinkFollowsTheList() {
        val list = rows("A y B")
        val p = Players(list, 0)
        val steps = listOf("next" to "lo:y", "next" to "sp:r66", "prev" to "lo:y", "prev" to "sp:r65", "next" to "lo:y",
            "prev" to "sp:r65", "next" to "lo:y", "next" to "sp:r66", "prev" to "lo:y", "next" to "sp:r66")
        assertEquals("sp:r65", p.at())
        steps.forEachIndexed { i, (move, expected) ->
            if (move == "next") p.next() else p.previous()
            assertEquals("step ${i + 1} ($move)", expected, p.at())
        }
    }

    @Test fun previousPastTheFirstSecondsStartsTheSameRowOver() {
        val p = Players(rows("A y B"), 2)
        p.previous(positionMs = 10_000)
        assertEquals("sp:r66", p.at())
        val q = Players(rows("A y B"), 1)
        q.previous(positionMs = 10_000)
        assertEquals("lo:y", q.at())
    }

    /** Random Next, Previous and song ends from every row; after each, the row playing is the one the list says. */
    private fun walk(layout: String, repeatAll: Boolean = false) {
        val list = rows(layout)
        for (seed in 0 until 30) {
            val random = kotlin.random.Random(seed)
            var expected = random.nextInt(list.size)
            val p = Players(list, expected, repeatAll)
            repeat(120) { n ->
                when (random.nextInt(3)) {
                    0 -> { if (expected > 0) { p.previous(); expected-- } else { p.previous(); assertEquals(list[0].key, p.at()) } }
                    else -> {
                        val last = expected == list.lastIndex
                        if (last && !repeatAll) return@repeat
                        if (random.nextBoolean()) p.next() else p.songEnds()
                        expected = if (last) 0 else expected + 1
                    }
                }
                assertEquals("$layout seed $seed move ${n + 1}", list[expected].key, p.at())
                assertEquals(false, p.over)
            }
        }
    }

    @Test fun linkBetweenSongs() = walk("A y B c D")
    @Test fun twoLinksInARow() = walk("A y z B C")
    @Test fun linksAtTheStart() = walk("y z A B")
    @Test fun linksAtTheEnd() = walk("A B y z")
    @Test fun linksAtBothEndsOfARepeatedList() = walk("y A B z", repeatAll = true)

    @Test fun nextOnTheLastRowEndsTheList() {
        val p = Players(rows("A B y"), 2)
        p.next()
        assertEquals(true, p.over)
        assertNull(MixedNavigator(rows("A B y")).apply { start(2) }.runEnded())
    }
}
