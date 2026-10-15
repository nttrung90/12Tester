package com.ntt.crosstest.data

import android.content.Context
import android.content.res.Resources
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "testcheo_settings")

/** Tiny DataStore wrapper for local app settings (no server involved). */
class SettingsRepository(private val context: Context) {

    private val reminderEnabledKey = booleanPreferencesKey("reminder_enabled")
    private val appLanguageKey = stringPreferencesKey("app_language")
    private val testingGuideConfirmedKey = booleanPreferencesKey("testing_guide_confirmed")

    val reminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[reminderEnabledKey] ?: true }

    suspend fun setReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[reminderEnabledKey] = enabled }
    }

    val isTestingGuideConfirmed: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[testingGuideConfirmedKey] ?: false }

    suspend fun setTestingGuideConfirmed(confirmed: Boolean = true) {
        context.dataStore.edit { prefs -> prefs[testingGuideConfirmedKey] = confirmed }
    }

    /**
     * Stored language setting:
     * - "system": follow system language (Vietnamese if device is vi, else English)
     * - "vi": force Vietnamese
     * - "en": force English
     * Default is "system".
     */
    val appLanguage: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[appLanguageKey] ?: LANGUAGE_SYSTEM }

    suspend fun setAppLanguage(languageCode: String) {
        // Save to SharedPreferences for fast synchronous bootstrap on app start
        context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, languageCode)
            .apply()

        context.dataStore.edit { prefs -> prefs[appLanguageKey] = languageCode }
    }

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_EN = "en"

        private const val PREFS_SYNC_NAME = "testcheo_settings_sync"
        private const val KEY_APP_LANGUAGE = "app_language"

        /** Read saved language synchronously for base context configuration. */
        fun getSavedLanguageSync(context: Context): String {
            return context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        }

        /**
         * Resolves the effective [Locale] to use.
         * Default behavior:
         * - If "vi": returns Locale("vi")
         * - If "en": returns Locale("en")
         * - If "system" (default):
         *     Checks the device's system language. If Vietnamese, returns Locale("vi").
         *     If the device language is different from Vietnamese, returns Locale("en").
         */
        fun resolveLocale(setting: String, systemLocale: Locale? = null): Locale {
            return when (setting) {
                LANGUAGE_VI -> Locale.forLanguageTag("vi")
                LANGUAGE_EN -> Locale.forLanguageTag("en")
                else -> {
                    val sysLoc = systemLocale ?: try {
                        Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
                    } catch (_: Exception) {
                        Locale.getDefault()
                    }
                    if (sysLoc.language.equals("vi", ignoreCase = true)) {
                        Locale.forLanguageTag("vi")
                    } else {
                        Locale.forLanguageTag("en")
                    }
                }
            }
        }
    }
}
