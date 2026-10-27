// @file YouTubeLinksTest.kt
// @author: ForUltraplayer
// @brief Check that a newly issued YouTube audio address is waited for only while it answers 403.
package com.music.spotui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class YouTubeLinksTest {
    private class Status(val code: Int) : IOException("HTTP $code")
    private val status = { e: Throwable -> (e as? Status)?.code }

    @Test fun waitsWhileANewAddressIsStillRefused() {
        var tries = 0
        val value = YouTubeLinks.untilServed(status) { if (++tries < 3) throw IOException("open", Status(403)) else "served" }
        assertEquals("served", value)
        assertEquals(3, tries)
    }

    @Test fun otherErrorsAreNotWaitedFor() {
        var tries = 0
        assertThrows(IOException::class.java) { YouTubeLinks.untilServed(status) { tries++; throw Status(404) } }
        assertEquals(1, tries)
    }

    @Test fun aLastingRefusalIsThrownWithinTheWait() {
        var tries = 0
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { YouTubeLinks.untilServed(status) { tries++; throw Status(403) } }
        // About one try a second for at most 6 s, then the caller asks for a new address.
        assert(tries in 5..7) { "tries=$tries" }
        assert(System.nanoTime() - started < 7_000_000_000L)
    }
}
