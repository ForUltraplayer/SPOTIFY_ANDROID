// @file LyricsOffsetsTest.kt
// @brief Check how a per-song lyric timing adjustment is described to the user.
package com.music.spotui.data.api

import app.myspoti.engine.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOffsetsTest {
    // The Korean resources (values-ko/hangul_strings.xml): JVM tests have no Android resources.
    private val korean = mapOf(
        R.string.hangul_offset_none to "보정 없음",
        R.string.hangul_offset_earlier to "가사를 %1\$s 초 먼저 표시",
        R.string.hangul_offset_later to "가사를 %1\$s 초 나중에 표시",
    )
    private fun describe(ms: Long) = LyricsOffsets.describe(ms) { id, seconds -> korean.getValue(id).format(seconds) }

    @Test fun describesDirectionAndSeconds() {
        assertEquals("보정 없음", describe(0))
        assertEquals("가사를 0.25 초 먼저 표시", describe(250))
        assertEquals("가사를 1.5 초 나중에 표시", describe(-1500))
        assertEquals("가사를 60 초 먼저 표시", describe(60_000))
    }
}
