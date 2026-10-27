// @file LyricsOffsetsTest.kt
// @author: ForUltraplayer
// @brief Check how a per-song lyric timing adjustment is described to the user.
package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOffsetsTest {
    @Test fun describesDirectionAndSeconds() {
        assertEquals("보정 없음", LyricsOffsets.describe(0))
        assertEquals("가사를 0.25 초 먼저 표시", LyricsOffsets.describe(250))
        assertEquals("가사를 1.5 초 나중에 표시", LyricsOffsets.describe(-1500))
        assertEquals("가사를 60 초 먼저 표시", LyricsOffsets.describe(60_000))
    }
}
