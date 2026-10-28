// @file MixedNavigator.kt
// @brief Decide which player plays which row of a mixed list on every Next, Previous and song change.
package com.music.spotui.data.local

/**
 * Where a mixed list (MixedOrder) is, and what plays on each move. It holds no player: it answers
 * with an [Action] for the caller to carry out, or null when the player that plays now goes there
 * by itself (the engine between two Spotify songs, the local player within a run of phone rows).
 * [rows] is the play order (the list as shown, or shuffled).
 */
class MixedNavigator(var rows: List<MixedRow>, private val repeatAll: () -> Boolean = { false }) {
    sealed class Action {
        /** The local player plays the run of phone rows starting at [start], from its row [offset]. */
        data class PlayRun(val start: Int, val offset: Int) : Action()
        /** Spotify plays the song at row [index] (the engine is given the list's order). */
        data class PlaySpotify(val index: Int) : Action()
    }

    /** The row playing. */
    var position = 0
        private set
    /** The first row of the run the local player plays; null while Spotify plays. */
    var runStart: Int? = null
        private set
    /** A Previous was left to the engine: the song it moves to next is the one before, not the list going on. */
    private var expectBackward = false

    private fun trackAt(index: Int) = (rows.getOrNull(index) as? MixedRow.Spotify)?.song?.spotifyTrackId

    /** The row at [index] plays in its player. */
    fun start(index: Int): Action {
        expectBackward = false
        return if (rows[index] is MixedRow.Local) {
            val start = MixedOrder.runStart(rows, index)
            runStart = start; position = index
            Action.PlayRun(start, index - start)
        } else {
            runStart = null; position = index
            Action.PlaySpotify(index)
        }
    }

    /** The local player moved within its run to its row [runIndex] (its own Next or Previous). */
    fun localMoved(runIndex: Int) {
        runStart?.let { position = it + runIndex }
    }

    /** Next while Spotify plays: a phone row next plays now; null: the engine goes on to the next song itself. */
    fun next(): Action? {
        if (runStart != null) return null
        val next = MixedOrder.nextRow(rows, position, repeatAll()) ?: return null
        return if (rows[next] is MixedRow.Local) start(next) else null
    }

    /**
     * Previous, [positionMs] into the row playing. Past [RESTART_MS] it starts over and in its first
     * seconds the row before plays: null when the player playing goes there itself (it restarts the
     * song, the engine's previous between two Spotify songs, the local player's within its run).
     */
    fun previous(positionMs: Long): Action? {
        if (positionMs > RESTART_MS) return null
        val target = MixedOrder.previousRow(position) ?: return null
        val run = runStart
        if (rows[target] is MixedRow.Spotify && run == null) { expectBackward = true; return null }
        if (run != null && target >= run) return null
        return start(target)
    }

    /** The local player's run is over (it ended, or Next on its last row). Null: the list is over. */
    fun runEnded(): Action? {
        val start = runStart ?: return null
        return MixedOrder.afterRun(rows, start, repeatAll())?.let(::start)
    }

    /**
     * Spotify moved on to [trackId] by itself or on its own Next or Previous. An action: the phone's
     * rows come first and play now. Null with [over] false: Spotify goes on, the list with it.
     */
    fun engineMoved(trackId: String): Action? {
        if (runStart != null) return null
        if (expectBackward) {
            expectBackward = false
            val found = rows.indices.filter { trackAt(it) == trackId }
            (found.lastOrNull { it < position } ?: found.firstOrNull())?.let { position = it }
            return null
        }
        val at = MixedOrder.engineMoved(rows, position, trackId, repeatAll()) ?: run { over = true; return null }
        if (rows[at] is MixedRow.Local) return start(at)
        position = at
        return null
    }

    /** The list ended under Spotify (it went on to a song outside the list with no phone rows left). */
    var over = false
        private set

    /** The rows reordered (shuffle on or off): the row playing keeps playing, at its place in [newRows]. */
    fun reorder(newRows: List<MixedRow>): Action {
        val playing = rows[position]
        rows = newRows
        return start(newRows.indexOf(playing).coerceAtLeast(0))
    }

    /** The row playing. */
    val current: MixedRow get() = rows[position]

    companion object {
        /** As the players' own Previous: past this, the song starts over instead. */
        const val RESTART_MS = 3_000L
    }
}
