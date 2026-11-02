// @file AudioOrder.kt
// @brief The audio order picked for this session, the source's own quality line and the recent misses, as on Android.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Manifest
import app.myspoti.extensions.SettingSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

// Ported from the Android app's SessionSource.kt, SectionSettings.kt and AudioRoute.kt (AudioMisses).

/**
 * The audio source the user put first from the source picker, for this run of the runner only: in
 * memory, never in the registry, so the saved order stays as it was. Spotify first: no extension
 * is asked. An extension turned off, removed or no longer a source leaves it ([valid]).
 */
object SessionSource {
    fun effective(saved: List<String>, lead: String?): List<String> =
        if (lead == null || lead !in saved) saved else listOf(lead) + saved.filter { it != lead }

    fun valid(lead: String?, saved: List<String>, enabled: (String) -> Boolean): String? =
        lead?.takeIf { it in saved && (it == SPOTIFY_SOURCE || enabled(it)) }

    /** The lead [id] gives: [id], or null when it is the source the saved order asks first already. */
    fun pick(id: String, saved: List<String>, enabled: (String) -> Boolean): String? =
        valid(id, saved, enabled)?.takeIf { it != SectionSettings.leader(saved, enabled) }

    /** What the source picker offers: Spotify and the extensions on, in the [effective] order. */
    fun choices(saved: List<String>, lead: String?, enabled: (String) -> Boolean): List<String> =
        effective(saved, valid(lead, saved, enabled)).filter { it == SPOTIFY_SOURCE || enabled(it) }
}

object SectionSettings {
    /** The first source of [line] that is asked: Spotify, or an extension turned on. */
    fun leader(line: List<String>, enabled: (String) -> Boolean): String? =
        line.firstOrNull { it == SPOTIFY_SOURCE || enabled(it) }

    /** "Quality: FLAC": the extension's own `role: quality` setting for [section] as it reads now; null without one. */
    fun qualityLine(m: Manifest, section: SettingSection, stored: (String) -> String?, metered: Boolean): String? {
        val s = m.qualitySetting(section) ?: return null
        val v = s.value(stored, metered)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            ?.let { runCatching { (it as JsonPrimitive).content }.getOrNull() } ?: return null
        return "${s.label}: $v"
    }
}

/**
 * The songs no extension had audio for, not asked again for [keepMs]: a miss asked ahead only keeps
 * further asks ahead away, the ask when the song starts still goes out. [clear] when an extension
 * is live again or changes.
 */
class AudioMisses(private val keepMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private class Miss(val at: Long, val ahead: Boolean)
    private val misses = ConcurrentHashMap<String, Miss>()

    fun record(trackId: String, ahead: Boolean) {
        if (trackId.isBlank()) return
        misses.compute(trackId) { _, m -> if (ahead && m != null && !m.ahead && fresh(m)) m else Miss(clock(), ahead) }
    }

    fun skips(trackId: String, ahead: Boolean): Boolean {
        val m = misses[trackId] ?: return false
        if (!fresh(m)) { misses.remove(trackId, m); return false }
        return ahead || !m.ahead
    }

    fun forget(trackId: String) { misses.remove(trackId) }

    fun clear() = misses.clear()

    private fun fresh(m: Miss) = clock() - m.at < keepMs
}
