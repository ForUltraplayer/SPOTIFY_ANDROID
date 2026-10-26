// @file LyricsPlusResolver.kt
// @author: ForUltraplayer
// @brief Resolve the same recording with bounded metadata candidates and transient-server cooldowns.
package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal class LyricsPlusResolver(
    private val request: (String, Int) -> Reply,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Query(val title: String, val artist: String, val album: String, val durationMs: Long)
    data class Reply(val status: Int, val body: String? = null, val retryAfter: String? = null)
    data class Result(val lyrics: Lyrics?, val retryable: Boolean = false)
    private data class Cooldown(val until: Long)
    private val cooldowns = ConcurrentHashMap<String, Cooldown>()
    private val bases = listOf("https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id")

    fun resolve(info: Query): Result {
        val deadline = now() + 18_000
        var retryable = false
        fun load(base: String, path: String, parameters: Map<String, String>): JSONObject? {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if ((cooldowns[base]?.until ?: 0) > now()) { retryable = true; return null }
            val remaining = deadline - now()
            if (remaining <= 0) { retryable = true; return null }
            val query = parameters.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            return try {
                val reply = request("$base$path?$query", remaining.coerceAtMost(4500).toInt())
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (reply.status == 429 || reply.status >= 500) {
                    retryable = true
                    val delay = retryDelay(reply.retryAfter, now())
                    cooldowns[base] = Cooldown(now() + delay)
                    null
                } else if (reply.status != 200 || reply.body == null) null
                else JSONObject(reply.body)
            } catch (interrupted: InterruptedException) { throw interrupted }
            catch (_: Exception) { retryable = true; null }
        }
        val clean = cleanTitle(info.title)
        val primary = info.artist.substringBefore(',').trim()
        val candidates = listOf(info,
            info.copy(title = clean, artist = primary), info.copy(title = clean)).distinct()
        for ((index, candidate) in candidates.withIndex()) {
            for (base in bases) {
                if (now() >= deadline) return Result(null, true)
                val parameters = linkedMapOf("title" to candidate.title, "artist" to candidate.artist)
                if (candidate.album.isNotBlank()) parameters["album"] = candidate.album
                if (candidate.durationMs > 0) parameters["duration"] = (candidate.durationMs / 1000.0).toString()
                val payload = load(base, "/v2/lyrics/get", parameters) ?: continue
                var metadata = payload.optJSONObject("metadata") ?: JSONObject()
                if (metadata.optString("title").isBlank() || metadata.optString("artist").isBlank()) {
                    val identity = load(base, "/v1/metadata/get", linkedMapOf("title" to candidate.title,
                        "artist" to candidate.artist))?.optJSONObject("metadata") ?: continue
                    if (metadata.optString("source").isBlank() || identity.optString("source") != metadata.optString("source")) continue
                    metadata = JSONObject(metadata.toString()).apply {
                        for (name in identity.keys()) put(name, identity.get(name))
                    }
                }
                // Validation is required for exact as well as normalized requests:
                // a provider can return another recording even for an exact query.
                if (!sameRecording(metadata, info)) continue
                val lyrics = parse(payload, metadata, info.durationMs, index > 0) ?: continue
                return Result(lyrics)
            }
        }
        return Result(null, retryable)
    }

    private fun parse(payload: JSONObject, metadata: JSONObject, durationMs: Long, normalized: Boolean): Lyrics? {
        val rows = payload.optJSONArray("lyrics") ?: return null
        val lines = ArrayList<LyricLine>()
        var lastEnd = 0L
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val start = row.optLong("time", 0)
            val duration = row.optLong("duration", 0)
            if (start < 0 || duration < 0) return null
            lastEnd = maxOf(lastEnd, start + duration)
            val syllables = row.optJSONArray("syllabus")
            val words = syllables?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filterNot { it.optBoolean("isBackground") }
                .map { word -> val time = word.optLong("time"); LyricWord(time,
                    time + word.optLong("duration"), word.optString("text")) }
            }?.takeIf { it.isNotEmpty() }
            val text = words?.joinToString("") { it.text }?.trim() ?: row.optString("text").trim()
            if (text.isNotEmpty()) lines += LyricLine(start, text, words)
        }
        if (lines.isEmpty()) return null
        if (normalized && durationMs > 0 && (lastEnd > durationMs + 3000 || lastEnd < durationMs * .6)) return null
        val source = metadata.optString("source")
        return Lyrics(lines, synced = lines.any { it.timeMs > 0 },
            language = metadata.optString("language").takeIf { it.isNotBlank() },
            provider = if (source.contains("apple", true)) "Apple Music · LyricsPlus" else "LyricsPlus")
    }

    companion object {
        private val feat = Regex("""\s*[(（\[［]\s*(?:feat\.?|ft\.?|featuring)\s+[^)）\]］]+[)）\]］]""", RegexOption.IGNORE_CASE)
        internal fun cleanTitle(title: String) = title.replace(feat, "").trim()
        private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun sameRecording(metadata: JSONObject, expected: Query): Boolean {
            val title = metadata.optString("title").ifBlank { metadata.optString("name") }
            if (key(cleanTitle(title)) != key(cleanTitle(expected.title)) || title.isBlank()) return false
            val wanted = key(expected.artist.substringBefore(',').trim())
            val artists = metadata.optString("artist").ifBlank { metadata.optString("artistName") }
                .split('/', ',', ';').map { key(it.replace(Regex("""\s+feat\..*$""", RegexOption.IGNORE_CASE), "")) }
            // Japanese '、' belongs to the unit name 25時、ナイトコードで。.
            if (wanted.isEmpty() || wanted !in artists) return false
            val album = metadata.optString("album").ifBlank { metadata.optString("albumName") }
            if (album.isNotBlank() && expected.album.isNotBlank() && key(album) != key(expected.album)) return false
            val actual = metadata.optLong("durationMs").takeIf { it > 0 } ?: clock(metadata.optString("totalDuration"))
            return actual <= 0 || expected.durationMs <= 0 || kotlin.math.abs(actual - expected.durationMs) <= 3000
        }
        private fun clock(value: String): Long {
            var seconds = 0.0
            for (part in value.split(':')) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return 0)
            return (seconds * 1000).toLong()
        }
        internal fun retryDelay(value: String?, at: Long): Long {
            val seconds = value?.toLongOrNull()
            val delay = if (seconds != null) seconds.coerceAtMost(86400) * 1000 else try {
                java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - at
            } catch (_: Exception) { 60_000L }
            return delay.coerceIn(30_000L, 86_400_000L)
        }
    }
}
