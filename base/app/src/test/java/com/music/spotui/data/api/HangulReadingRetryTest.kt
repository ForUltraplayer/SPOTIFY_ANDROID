// @file HangulReadingRetryTest.kt
// @brief Verify bounded pronunciation retries, cancellation and negative-cache recovery.
package com.music.spotui.data.api

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HangulReadingRetryTest {
    @Test fun successfulReadingsReturnWithoutWaiting() = runBlocking {
        var requests = 0
        val found = HangulReadingRetry.resolve(pause = { fail("No retry for success") }) { attempt, remaining ->
            requests++
            assertEquals(0, attempt)
            assertTrue(remaining in 1..60_000)
            "기존 발음"
        }
        assertEquals("기존 발음", found)
        assertEquals(1, requests)
    }

    @Test fun failuresRetryThreeTimesAtTwoSecondIntervals() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        val result = HangulReadingRetry.resolve(now = { clock }, pause = { waits += it; clock += it }) { attempt, remaining ->
            attempts += attempt
            assertEquals(60_000L - clock, remaining)
            if (attempt < 3) throw IOException("temporary source miss")
            "복구된 발음"
        }
        assertEquals("복구된 발음", result)
        assertEquals(listOf(0, 1, 2, 3), attempts)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), waits)
    }

    @Test fun repeatedMissStopsAfterTheFourthRequest() = runBlocking {
        var requests = 0
        try {
            HangulReadingRetry.resolve(pause = {}) { _, _ -> requests++; throw IOException("missing") }
            fail("Must report final failure")
        } catch (_: IOException) { }
        assertEquals(4, requests)
    }

    @Test fun cancellationDoesNotRetryOrBecomeAMiss() = runBlocking {
        var requests = 0
        try {
            HangulReadingRetry.resolve(pause = { fail("Cancelled request must not wait") }) { _, _ ->
                requests++; throw CancellationException("song changed")
            }
            fail("Must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    @Test fun cancellationDuringTheDelayPreventsTheNextSourceRequest() = runBlocking {
        var requests = 0
        try {
            HangulReadingRetry.resolve(pause = { throw CancellationException("song changed") }) { _, _ ->
                requests++; throw IOException("temporary")
            }
            fail("Must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    @Test fun nonNetworkFailuresAreNotRepeated() = runBlocking {
        var requests = 0
        try {
            HangulReadingRetry.resolve(pause = { fail("Invalid data must not retry") }) { _, _ ->
                requests++; throw IllegalArgumentException("invalid source")
            }
            fail("Must propagate invalid input")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, requests)
    }

    @Test fun totalBudgetPreventsAnotherSlowLookup() = runBlocking {
        var clock = 0L
        var requests = 0
        try {
            HangulReadingRetry.resolve(now = { clock }, pause = { fail("Budget exhausted") }) { _, _ ->
                requests++; clock = 59_000L; throw IOException("slow")
            }
            fail("Must report expired budget")
        } catch (_: IOException) { }
        assertEquals(1, requests)
    }

    @Test fun negativeCacheAllowsOnlyThreeExtraSourceLookupsAcrossUiReopening() {
        val failures = ReadingFailureCache { 100L }
        assertTrue(failures.allow("song", 0, false))
        failures.failed("song")
        assertFalse(failures.allow("song", 0, false))
        for (attempt in 1..3) {
            assertTrue(failures.allow("song", attempt, false))
            failures.failed("song")
        }
        for (attempt in 0..3) assertFalse(failures.allow("song", attempt, false))
        assertTrue(failures.allow("other source", 0, false))
    }

    @Test fun failureCacheCanRecoverAndExpireWithoutLosingSuccessfulReadings() {
        var clock = 0L
        val failures = ReadingFailureCache { clock }
        failures.failed("song")
        assertTrue(failures.allow("song", 1, false))
        failures.clear("song")
        assertTrue(failures.allow("song", 0, false))
        failures.failed("song")
        clock = 600_001
        assertTrue(failures.allow("song", 0, false))
        failures.failed("song")
        assertTrue(failures.allow("song", 0, true))
    }
}
