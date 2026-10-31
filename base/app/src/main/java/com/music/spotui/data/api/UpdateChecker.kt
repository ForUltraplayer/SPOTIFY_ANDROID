package com.music.spotui.data.api

import android.content.Context
import app.myspoti.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * New version check against the GitHub releases of [BuildConfig.UPDATE_REPO]
 * (set by the CI build; empty in local builds, which never check).
 * At most one request every 3 hours (releases can come several a day). The release's
 * APK is downloaded and installed in the app ([AppUpdater]).
 */
object UpdateChecker {
    /** [apkUrl]: the release's signed APK; blank when the release has none (then its page opens). */
    data class Release(val version: String, val pageUrl: String, val notes: String = "", val apkUrl: String = "")

    private const val INTERVAL_MS = 3 * 3_600_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).addInterceptor(com.music.spotui.data.DataSaver.interceptor).build() }

    /** The launch check: at most every 3 hours, and a failed request only leaves the last answer. */
    suspend fun check(context: Context): Release? = check(context, manual = false)

    /** The Settings button: asks now, offers a dismissed release again, and throws when GitHub can't be read. */
    suspend fun checkNow(context: Context): Release? = check(context, manual = true)

    private suspend fun check(context: Context, manual: Boolean): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext null
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        check(prefs, System.currentTimeMillis(), manual) { fetch(repo) }
    }

    /**
     * [fetch] returns null when the list was read and has nothing for this build, and throws when
     * it could not be read. Only a read list is saved and counts as a check: a failure must not
     * erase a known update or hold off the next try for 3 hours.
     */
    internal fun check(prefs: android.content.SharedPreferences, now: Long, manual: Boolean, fetch: () -> Release?): Release? {
        if (!manual && now - prefs.getLong("checked_at", 0L) < INTERVAL_MS) return pending(prefs)
        val release = try { fetch() } catch (error: Exception) {
            if (manual || error is kotlinx.coroutines.CancellationException) throw error
            return pending(prefs)
        }
        prefs.edit().putLong("checked_at", now)
            .putString("version", release?.version).putString("url", release?.pageUrl)
            .putString("notes", release?.notes).putString("apk", release?.apkUrl).apply()
        return pending(prefs, includeDismissed = manual)
    }

    private fun fetch(repo: String): Release? {
        // Stable builds follow "latest", which never includes pre-releases. A beta build
        // also takes the newer betas, then the release they lead to.
        val url = if (isBeta) "https://api.github.com/repos/$repo/releases?per_page=10" else "https://api.github.com/repos/$repo/releases/latest"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        return http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub releases HTTP ${r.code}")
            val text = r.body.string()
            val list = if (isBeta) org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(text))
            newest(list, BuildConfig.VERSION_NAME)
        }
    }

    /**
     * The newest of GitHub's [releases] that a build of [version] takes. Betas and releases are two
     * apps: each one is offered its own channel only. The Hangul builds are one app published both
     * ways (pre-releases first, then releases), so they take either.
     */
    internal fun newest(releases: List<JSONObject>, version: String): Release? =
        releases.filter { !it.optBoolean("draft") && (hangulChannel(version) || !betaChannel(version) || it.optBoolean("prerelease")) }
            .map { Release(it.getString("tag_name").removePrefix("v"), it.getString("html_url"), cleanNotes(it.optString("body")), apkAsset(it)) }
            .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }

    /** The latest release known, if newer than this build and not dismissed (unless [includeDismissed]). */
    private fun pending(prefs: android.content.SharedPreferences, includeDismissed: Boolean = false): Release? {
        val version = prefs.getString("version", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        if (!includeDismissed && prefs.getString("dismissed", null) == version) return null
        return Release(version, url, prefs.getString("notes", null).orEmpty(), prefs.getString("apk", null).orEmpty()).takeIf { isNewer(version, BuildConfig.VERSION_NAME) }
    }

    /** The release's APK download link (the CI uploads one `myspoti-<version>.apk`). */
    internal fun apkAsset(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        // This release's own APK first: a release carrying several APKs (both
        // channels) must not hand a phone the other channel's build.
        val version = release.optString("tag_name").removePrefix("v")
        val hangul = version.contains("-hangul.")
        val want = if (hangul) "Myspoti-Hangul-$version.apk" else "myspoti-$version.apk"
        return (all.firstOrNull { it.optString("name") == want }
            ?: if (hangul) null else all.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) })
            ?.optString("browser_download_url").orEmpty()
    }

    fun dismiss(context: Context, release: Release) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("dismissed", release.version).apply()
    }

    /**
     * The release description as plain lines for the update window: the CHANGELOG section,
     * without GitHub's generated part, Markdown emphasis or link syntax.
     */
    fun cleanNotes(body: String): String = body
        .substringBefore("**Full Changelog**").substringBefore("## What's Changed")
        .lines().map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            line.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^(\\s*)[-*] "), "$1• ")
        }
        .trim()

    /** This build is a beta ("2.0.0-beta.1"). */
    internal fun betaChannel(version: String) = version.contains("-beta") || version.contains("-hangul.")
    /** A Hangul build ("2.0.1-hangul.9"): it reads the release list as betas do, and takes its releases too. */
    internal fun hangulChannel(version: String) = version.contains("-hangul.")
    val isBeta get() = betaChannel(BuildConfig.VERSION_NAME)

    /**
     * "1.10.0" > "1.9.2". A release is newer than its betas ("2.0.0" > "2.0.0-beta.3"), and
     * betas compare by number ("2.0.0-beta.2" > "2.0.0-beta.1").
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Same version number: release > beta, then the higher beta.
        fun beta(v: String) = if (!v.contains('-')) Int.MAX_VALUE else Regex("(\\d+)$").find(v)?.value?.toIntOrNull() ?: 0
        return beta(candidate) > beta(current)
    }
}
