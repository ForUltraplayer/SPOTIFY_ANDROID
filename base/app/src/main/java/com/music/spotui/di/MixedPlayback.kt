// @file MixedPlayback.kt
// @brief Play a Spotify playlist with the phone's links and files among its songs, in the order shown.
package com.music.spotui.di

import com.music.spotui.data.local.MixedNavigator
import com.music.spotui.data.local.MixedOrder
import com.music.spotui.data.local.MixedRow

/**
 * A Spotify playlist played with the items this phone keeps under it, in the mixed order (as the
 * PC version's mixed playlists). Spotify plays its songs in that order (the engine is given it, so
 * its own next and previous follow it between two Spotify songs); where phone rows come in between,
 * the local player plays them and Spotify goes on after them. Which player plays which row on every
 * move is [MixedNavigator]'s; this carries it out. Shuffle is the list's own: the engine plays the
 * shuffled order with its shuffle off.
 */
object MixedPlayback {
    private class Session(val uri: String, val name: String, val shown: List<MixedRow>, val nav: MixedNavigator, var shuffle: Boolean)

    @Volatile private var session: Session? = null

    /** The list playing mixed, if any. */
    val uri: String? get() = session?.uri

    private fun repeatAll() = SongPlayer.repeatMode.value == 1

    /** [shown]'s row at [picked] plays (the list as shown, in [uri] named [name]); [shuffle]: in random order from it. */
    fun play(uri: String, name: String, shown: List<MixedRow>, picked: Int, shuffle: Boolean) {
        if (shown.isEmpty()) return
        val at = picked.coerceIn(0, shown.lastIndex)
        val s = Session(uri, name, shown, MixedNavigator(if (shuffle) MixedOrder.shuffled(shown, at) else shown, ::repeatAll), shuffle)
        session = s
        carryOut(s, s.nav.start(if (shuffle) 0 else at))
    }

    /** Something else plays (another list, a phone file): this list no longer steers the players. */
    fun end() { session = null }

    private fun carryOut(s: Session, action: MixedNavigator.Action?, positionMs: Long = 0L): Boolean {
        when (action) {
            null -> return false
            is MixedNavigator.Action.PlayRun -> {
                val run = MixedOrder.localRun(s.nav.rows, action.start)
                LocalPlayer.play(run.map { it.item.toTrack() }, action.offset, context = s.name, contextUri = s.uri, startMs = positionMs)
            }
            is MixedNavigator.Action.PlaySpotify -> {
                val song = (s.nav.rows[action.index] as MixedRow.Spotify).song
                SongPlayer.registerContextName(s.uri, s.name)
                // The order given is already the list's (shuffled or not): the engine's own shuffle would mix it again.
                SongPlayer.playSong(song.url, contextUri = s.uri, order = MixedOrder.spotifyOrder(s.nav.rows).map { "spotify:track:" + it.song.spotifyTrackId },
                    positionMs = positionMs.takeIf { it > 0 }, askExtensions = false, engineShuffleOff = true)
            }
        }
        return true
    }

    /** The local player's place in its run, kept by the navigator (its own Next and Previous move it). */
    private fun syncLocal(s: Session) {
        if (s.nav.runStart != null && LocalPlayer.active.value) s.nav.localMoved(LocalPlayer.currentIndex())
    }

    /**
     * Spotify moved on to [trackId] in [contextUri] (main thread). True: phone rows come first, the
     * local player plays them now and Spotify waits.
     */
    fun engineMoved(contextUri: String, trackId: String): Boolean {
        val s = session ?: return false
        if (contextUri != s.uri) { end(); return false }
        val played = carryOut(s, s.nav.engineMoved(trackId))
        if (s.nav.over) end()
        return played
    }

    /** Next with Spotify playing (app, notification, headphones). True: a phone row comes next and plays now. */
    fun next(): Boolean {
        val s = session ?: return false
        return carryOut(s, s.nav.next())
    }

    /**
     * Previous with either player playing (app, notification, headphones). True: the row before is in
     * the other player and plays there now. False: the player playing restarts the song or goes back itself.
     */
    fun previous(): Boolean {
        val s = session ?: return false
        val local = s.nav.runStart != null
        if (local != LocalPlayer.active.value) return false
        syncLocal(s)
        return carryOut(s, s.nav.previous(if (local) LocalPlayer.position() else SongPlayer.getCurrentPosition()))
    }

    /** The local player came to the end of a run (it ended, or Next). True: the list goes on from here. */
    fun runEnded(): Boolean {
        val s = session ?: return false
        if (s.nav.runStart == null) return false
        val action = s.nav.runEnded() ?: run { end(); return false }
        return carryOut(s, action)
    }

    /** A phone list or file starts in the local player ([contextUri]): not this list's run, so it ends. */
    fun localStarted(contextUri: String) {
        if (session?.uri != contextUri) end()
    }

    /** The shuffle to show while [contextUri] (or, without one, anything) plays mixed; null: the player's own. */
    fun shuffleShown(contextUri: String? = null): Boolean? = session?.takeIf { contextUri == null || it.uri == contextUri }?.shuffle

    /**
     * Shuffle turned on or off while the list plays: the rows after the current one are mixed (or put
     * back in the list's order) and the song playing goes on from where it is. False: not playing mixed.
     */
    fun setShuffle(on: Boolean): Boolean {
        val s = session ?: return false
        if (on == s.shuffle) return true
        syncLocal(s)
        val local = s.nav.runStart != null && LocalPlayer.active.value
        val positionMs = if (local) LocalPlayer.position() else SongPlayer.getCurrentPosition()
        val playing = s.nav.current
        s.shuffle = on
        carryOut(s, s.nav.reorder(if (on) listOf(playing) + (s.shown - playing).shuffled() else s.shown), positionMs)
        return true
    }
}
