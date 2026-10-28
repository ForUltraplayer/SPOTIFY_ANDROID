// @file MixedOrder.kt
// @brief Merge a Spotify playlist with the links and phone files kept on this phone into one order of the user's.
package com.music.spotui.data.local

import com.music.spotui.data.entity.SongsModel

/** One row of a Spotify playlist shown with the items this phone keeps under it. */
sealed class MixedRow {
    /** Stable across reloads: `sp:` and the playlist row, or `lo:` and the phone item's key. */
    abstract val key: String

    data class Spotify(val song: SongsModel, override val key: String) : MixedRow()
    data class Local(val item: LocalItem) : MixedRow() {
        override val key: String get() = MixedOrder.LOCAL + item.key
    }
}

/**
 * The order of a Spotify playlist and the items added under it on this phone (as the PC version's
 * mixed playlists). Spotify's own order is never changed: the mixed order is a list of row keys
 * kept on this phone, and the rows are merged into it on every read.
 */
object MixedOrder {
    const val SPOTIFY = "sp:"
    const val LOCAL = "lo:"
    /** At most this many keys are kept for one playlist (the PC version keeps 4000). */
    const val MAX_KEYS = 4000

    /** A playlist row's key: its row id, else the song and which of its occurrences it is (a song can be there twice). */
    fun spotifyRows(songs: List<SongsModel>): List<MixedRow.Spotify> {
        val seen = HashMap<String, Int>()
        return songs.map { s ->
            val base = s.rowUid.ifBlank { s.spotifyTrackId.ifBlank { s.id.toString() } }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            MixedRow.Spotify(s, SPOTIFY + if (n == 1) base else "$base#$n")
        }
    }

    /**
     * The rows in the saved [order]. Without one, Spotify's order comes first and the phone's items
     * after it (where they showed before, so earlier additions keep their place at the end). Rows gone
     * from Spotify drop out; rows the order does not know yet (songs added on Spotify since, a new
     * item) come after the known ones, as Spotify adds songs at the end.
     */
    fun merge(songs: List<SongsModel>, items: List<LocalItem>, order: List<String>): List<MixedRow> {
        val live = spotifyRows(songs) + items.map { MixedRow.Local(it) }
        if (order.isEmpty()) return live
        val byKey = LinkedHashMap<String, MixedRow>().apply { live.forEach { putIfAbsent(it.key, it) } }
        val known = order.mapNotNull { byKey.remove(it) }
        return known + live.filter { byKey.containsKey(it.key) }
    }

    /** The keys once the row at [from] is moved to [to] (positions in [rows]). */
    fun move(rows: List<MixedRow>, from: Int, to: Int): List<String> {
        val keys = rows.map { it.key }.toMutableList()
        if (from !in keys.indices) return keys
        val key = keys.removeAt(from)
        keys.add(to.coerceIn(0, keys.size), key)
        return keys
    }

    /** [picked] first, then the other rows in random order (a shuffle play from a row). */
    fun shuffled(rows: List<MixedRow>, picked: Int, random: kotlin.random.Random = kotlin.random.Random.Default): List<MixedRow> {
        if (picked !in rows.indices) return rows.shuffled(random)
        return listOf(rows[picked]) + (rows.subList(0, picked) + rows.subList(picked + 1, rows.size)).shuffled(random)
    }

    /** The phone's rows from [index] up to the next Spotify row: what the local player plays at once. */
    fun localRun(rows: List<MixedRow>, index: Int): List<MixedRow.Local> =
        rows.drop(index.coerceAtLeast(0)).takeWhile { it is MixedRow.Local }.filterIsInstance<MixedRow.Local>()

    /** The Spotify songs of [rows] in play order, for the engine's order. */
    fun spotifyOrder(rows: List<MixedRow>): List<MixedRow.Spotify> = rows.filterIsInstance<MixedRow.Spotify>()

    /** The row after [index]; after the last, the first when the list repeats, else null (the list is over). */
    fun nextRow(rows: List<MixedRow>, index: Int, repeatAll: Boolean): Int? = when {
        index + 1 in rows.indices -> index + 1
        repeatAll && rows.isNotEmpty() -> 0
        else -> null
    }

    /** The first row of the phone's run holding the row at [index]: the local player is given the whole run. */
    fun runStart(rows: List<MixedRow>, index: Int): Int {
        var i = index
        while (i > 0 && rows[i - 1] is MixedRow.Local) i--
        return i
    }

    /** Where the list goes on once the run started at [start] is over: the row after it (the first when it repeats), or null. */
    fun afterRun(rows: List<MixedRow>, start: Int, repeatAll: Boolean = false): Int? =
        nextRow(rows, start + localRun(rows, start).size - 1, repeatAll)

    /** Where Previous goes from the row at [index], once its start is past: the row before it, or null at the top. */
    fun previousRow(index: Int): Int? = (index - 1).takeIf { it >= 0 }

    private fun trackAt(rows: List<MixedRow>, index: Int) = (rows.getOrNull(index) as? MixedRow.Spotify)?.song?.spotifyTrackId

    /**
     * Spotify moved by itself from the row at [position] to [trackId] (the switch before the song's
     * end came too late, a wrap of the repeated list, Spotify's autoplay at the end). The row the list
     * is at now: the phone's rows that come first when Spotify went on to the song after them (or
     * left the list), else where the song is in the list, after [position] first (a song picked in
     * the queue). Null: the song is not in the list and no phone rows come next (the list is over).
     */
    fun engineMoved(rows: List<MixedRow>, position: Int, trackId: String, repeatAll: Boolean = false): Int? {
        val found = rows.indices.filter { trackAt(rows, it) == trackId }
        val next = nextRow(rows, position, repeatAll)
        if (next != null && rows[next] is MixedRow.Local) {
            // The Spotify song the phone's rows lead to, past the list's end when it repeats.
            var after: Int? = next
            var steps = 0
            while (after != null && rows[after] is MixedRow.Local && steps++ < rows.size) after = nextRow(rows, after, repeatAll)
            if (found.isEmpty() || (after != null && trackAt(rows, after) == trackId)) return next
        }
        return found.firstOrNull { it > position } ?: found.firstOrNull()
    }
}
