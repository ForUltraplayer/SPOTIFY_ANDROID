// @file HangulReadings.kt
// @brief Read authored Hangul lyrics with validated aliases and opt-in offline completion.
package com.music.spotui.data.api

import android.content.Context
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.di.Graph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

object HangulReadings {
    data class Result(val lines: List<String>, val source: String, val matched: Int, val total: Int, val generated: Int = 0)
    private val engine by lazy { HangulReadingEngine() }
    private val resolutionLock = ReentrantLock()
    private val failures = ReadingFailureCache()
    private const val LIFETIME = 30L * 86400 * 1000
    private fun prefs() = Graph.app.getSharedPreferences("hangul_readings", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs().getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs().edit().putBoolean("enabled", value).apply() }
    fun automaticEnabled(): Boolean = prefs().getBoolean("automatic", false)
    fun setAutomatic(value: Boolean) { prefs().edit().putBoolean("automatic", value).apply() }
    fun source(track: LyricsApi.Track): String = prefs().getString("source:${track.key}", "").orEmpty()
    fun setSource(track: LyricsApi.Track, url: String) {
        val value = if (url.isBlank()) "" else HangulReadingEngine.checkedUrl(url.trim())
        prefs().edit().putString("source:${track.key}", value).apply()
    }
    fun japanese(lyrics: Lyrics): Boolean = lyrics.language?.substringBefore('-') == "ja"
        || lyrics.lines.any { line -> line.text.any { it in '\u3040'..'\u30ff' } }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun aliases() = runCatching { JSONObject(prefs().getString("aliases-v2", "{}").orEmpty()) }.getOrElse { JSONObject() }
    private fun learnedSource(key: String): String = runCatching {
        val entry = aliases().optJSONObject(key) ?: return@runCatching ""
        if (System.currentTimeMillis() - entry.optLong("checkedAt") >= LIFETIME) return@runCatching ""
        HangulReadingEngine.checkedUrl(entry.getString("url"))
    }.getOrDefault("")
    private fun rememberSource(key: String, url: String) {
        if (!(url.startsWith("https://namu.wiki/w/") || url.startsWith("https://haneum.site/songs/"))) return
        val entries = aliases().put(key, JSONObject().put("url", url).put("checkedAt", System.currentTimeMillis()))
        while (entries.length() > 500) {
            val oldest = entries.keys().asSequence().minByOrNull { entries.optJSONObject(it)?.optLong("checkedAt") ?: 0L } ?: break
            entries.remove(oldest)
        }
        prefs().edit().putString("aliases-v2", entries.toString()).apply()
    }
    private fun complete(texts: List<String>, authored: Result): Result {
        if (!automaticEnabled()) return authored
        val lines = engine.completeAutomatically(texts, authored.lines)
        val generated = texts.indices.count { authored.lines[it] == HangulReadingEngine.MISSING && lines[it] != HangulReadingEngine.MISSING }
        return authored.copy(lines = lines, generated = generated)
    }

    /** The tokenizer and cache have one owner; cancelled waiters can leave immediately. */
    fun resolve(track: LyricsApi.Track, lyrics: Lyrics, force: Boolean = false, retryAttempt: Int = 0, remainingMs: Long = 32_000): Result {
        resolutionLock.lockInterruptibly()
        try { return resolveLocked(track, lyrics, force, retryAttempt, remainingMs) }
        finally { resolutionLock.unlock() }
    }
    private fun resolveLocked(track: LyricsApi.Track, lyrics: Lyrics, force: Boolean, retryAttempt: Int, remainingMs: Long): Result {
        val texts = lyrics.lines.map { it.text }
        val url = source(track)
        val key = digest(listOf("hangul-v2", track.title, track.artist, url, texts.joinToString("\n")).joinToString("|"))
        val aliasTitle = HangulReadingEngine.titles(track.title, track.artist).firstOrNull().orEmpty()
        val aliasKey = digest(HangulReadingEngine.normalize(aliasTitle) + "|" + HangulReadingEngine.normalize(track.artist))
        val directory = File(Graph.app.filesDir, "hangul_readings_cache").apply { mkdirs() }
        val file = File(directory, "$key.json")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() < LIFETIME) {
            val saved = runCatching {
                val data = JSONObject(file.readText())
                val rows = data.getJSONArray("lines")
                require(rows.length() == texts.size)
                Result(List(rows.length()) { rows.getString(it) }, data.getString("source"), data.getInt("matched"), data.getInt("total"))
            }.getOrNull()
            if (saved != null) return complete(texts, saved)
        }
        var searched = false
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (!failures.allow(key, retryAttempt, force))
                throw IOException("일치하는 기존 한글 발음 자료가 없습니다. 가사 메뉴에서 자료 주소를 지정해 주세요.")
            searched = true
            val found = engine.resolve(track.title, track.artist, texts, url, learnedSource(aliasKey), HangulReadingEngine::fetch, remainingMs)
            check(found.valid() && found.readings.size == texts.size)
            val result = Result(found.readings, found.source, found.matched, found.total)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            runCatching {
                val data = JSONObject().put("lines", JSONArray(result.lines)).put("source", result.source)
                    .put("matched", result.matched).put("total", result.total)
                val temporary = File(directory, "$key.tmp")
                temporary.writeText(data.toString())
                if (!temporary.renameTo(file)) { file.writeText(data.toString()); temporary.delete() }
                directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(500)?.forEach { it.delete() }
                rememberSource(aliasKey, result.source)
            }
            failures.clear(key)
            return complete(texts, result)
        } catch (error: Exception) {
            if (error is InterruptedException || error is CancellationException || Thread.currentThread().isInterrupted) throw error
            if (error !is IOException) throw error
            if (searched) failures.failed(key)
            if (!automaticEnabled()) throw error
            val empty = Result(texts.map { if (HangulReadingEngine.japanese(it)) HangulReadingEngine.MISSING else it }, "", 0, texts.count(HangulReadingEngine::japanese))
            return complete(texts, empty).also { if (it.generated == 0) throw error }
        }
    }
}
