package com.music.spotui.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** The app's own language (per-app locale), independent of the phone's. */
object AppLanguage {
    /** Follow the phone. */
    const val SYSTEM = ""

    /**
     * Languages the app has every string in, as BCP 47 tags, after "automatic": the Hangul build's
     * androidResources.localeFilters (build.gradle.kts), so picking one never shows a half-translated app.
     */
    val AVAILABLE: List<String> = listOf(SYSTEM) + listOf(
        "de", "el", "en", "es", "fr", "it", "ja", "ko", "pl", "pt", "ru", "zh",
    ).sortedBy { nativeName(it).lowercase() }

    fun current(context: Context): String =
        AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.toLanguageTag() ?: SYSTEM

    fun apply(context: Context, tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** The language's name in itself ("Français", "Deutsch"…). */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
