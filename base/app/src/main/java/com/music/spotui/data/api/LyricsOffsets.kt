// @file LyricsOffsets.kt
// @brief Keep a bounded per-song lyric timing adjustment, as the PC version's sync control does.
package com.music.spotui.data.api

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.music.spotui.di.Graph
import org.json.JSONObject

/**
 * Whole-lyric timing adjustment per song (LyricsApi.Track.key), in ms. Positive shows the lyrics
 * earlier. Kept on this phone only, at most [MAX_SONGS] songs, the oldest change dropped first.
 */
object LyricsOffsets {
    const val MAX_MS = 60_000L
    const val STEP_MS = 250L
    private const val MAX_SONGS = 500
    private val values = mutableStateMapOf<String, Long>()
    private var loaded = false

    private fun prefs() = Graph.app.getSharedPreferences("lyrics_offsets", Context.MODE_PRIVATE)

    private fun load() {
        if (loaded) return
        loaded = true
        val saved = runCatching { JSONObject(prefs().getString("offsets", "{}").orEmpty()) }.getOrElse { JSONObject() }
        saved.keys().forEach { key -> saved.optJSONObject(key)?.let { values[key] = it.optLong("ms").coerceIn(-MAX_MS, MAX_MS) } }
    }

    /** Read by the lyrics position on every frame; a Compose state, so a change shows at once. */
    fun of(key: String?): Long {
        if (key.isNullOrEmpty()) return 0
        load()
        return values[key] ?: 0
    }

    fun set(key: String, ms: Long) {
        load()
        val value = ms.coerceIn(-MAX_MS, MAX_MS)
        val saved = runCatching { JSONObject(prefs().getString("offsets", "{}").orEmpty()) }.getOrElse { JSONObject() }
        if (value == 0L) { values.remove(key); saved.remove(key) }
        else {
            values[key] = value
            saved.put(key, JSONObject().put("ms", value).put("at", System.currentTimeMillis()))
        }
        while (saved.length() > MAX_SONGS) {
            val oldest = saved.keys().asSequence().minByOrNull { saved.optJSONObject(it)?.optLong("at") ?: 0L } ?: break
            saved.remove(oldest); values.remove(oldest)
        }
        prefs().edit().putString("offsets", saved.toString()).apply()
    }

    fun describe(ms: Long): String =
        if (ms == 0L) "보정 없음"
        else "가사를 " + java.math.BigDecimal(kotlin.math.abs(ms)).movePointLeft(3).stripTrailingZeros().toPlainString() +
            " 초 " + (if (ms > 0) "먼저" else "나중에") + " 표시"
}
