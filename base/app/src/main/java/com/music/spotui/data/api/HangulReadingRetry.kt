// @file HangulReadingRetry.kt
// @brief Bound current-song pronunciation retries and allow limited negative-cache bypasses.
package com.music.spotui.data.api

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal object HangulReadingRetry {
    const val MAX_RETRIES = 3
    const val DELAY_MS = 2_000L
    const val BUDGET_MS = 60_000L

    suspend fun <T : Any> resolve(
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) },
        request: suspend (attempt: Int, remainingMs: Long) -> T,
    ): T = withTimeoutOrNull(BUDGET_MS) {
        val deadline = now() + BUDGET_MS
        for (attempt in 0..MAX_RETRIES) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - now()
            if (remaining <= 0) throw IOException("한글 발음 조회 시간이 초과되었습니다.")
            try {
                return@withTimeoutOrNull request(attempt, remaining)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                // A finished search that found nothing would only find nothing again.
                if (error is HangulReadingEngine.NonRetryable || attempt == MAX_RETRIES || deadline - now() <= DELAY_MS) throw error
                pause(DELAY_MS)
            }
        }
        error("Unreachable reading retry state")
    } ?: throw IOException("한글 발음 조회 시간이 초과되었습니다.")
}

/** Owned by HangulReadings' resolution lock; UI reopening does not reset its source budget. */
internal class ReadingFailureCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val expires: Long, val retry: Int)
    private val entries = linkedMapOf<String, Entry>()

    fun allow(key: String, attempt: Int, force: Boolean): Boolean {
        require(attempt in 0..HangulReadingRetry.MAX_RETRIES)
        if (force) { entries.remove(key); return true }
        val entry = entries[key] ?: return true
        if (entry.expires <= now()) { entries.remove(key); return true }
        if (attempt <= entry.retry) return false
        entries[key] = entry.copy(retry = attempt)
        return true
    }

    /** No attempt of [key] may search again until it expires or is forced: report at once. */
    fun exhausted(key: String): Boolean {
        val entry = entries[key] ?: return false
        return entry.expires > now() && entry.retry >= HangulReadingRetry.MAX_RETRIES
    }

    /** A [definite] miss spends the whole retry budget: the same search would end the same way. */
    fun failed(key: String, definite: Boolean = false) {
        entries[key] = Entry(now() + 600_000, if (definite) HangulReadingRetry.MAX_RETRIES else entries[key]?.retry ?: 0)
        if (entries.size > 100) entries.remove(entries.keys.first())
    }

    fun clear(key: String) { entries.remove(key) }
}
