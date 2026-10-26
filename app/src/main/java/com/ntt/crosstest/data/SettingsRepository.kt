package com.ntt.crosstest.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    private val appThemeKey = stringPreferencesKey("app_theme")
    private val testingGuideConfirmedKey = booleanPreferencesKey("testing_guide_confirmed")
    private val updateNotifiedVersionCodeKey = intPreferencesKey("update_notified_version_code")

    val reminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[reminderEnabledKey] ?: true }

    suspend fun setReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[reminderEnabledKey] = enabled }
    }

    /**
     * latestVersionCode that a background update notification was already shown
     * for. Used to avoid notifying about the same version repeatedly.
     */
    val updateNotifiedVersionCode: Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[updateNotifiedVersionCodeKey] ?: 0 }

    suspend fun setUpdateNotifiedVersionCode(versionCode: Int) {
        context.dataStore.edit { prefs -> prefs[updateNotifiedVersionCodeKey] = versionCode }
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

    /**
     * Stored theme setting:
     * - "system": follow the device light/dark mode (default)
     * - "light": force light theme
     * - "dark": force dark theme
     */
    val appTheme: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[appThemeKey] ?: THEME_SYSTEM }

    suspend fun setAppTheme(theme: String) {
        // Mirrored to SharedPreferences so MainActivity can apply the theme to
        // system bars before the DataStore flow emits on a cold start.
        context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_THEME, theme)
            .apply()

        context.dataStore.edit { prefs -> prefs[appThemeKey] = theme }
    }

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_EN = "en"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val PREFS_SYNC_NAME = "testcheo_settings_sync"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_APP_THEME = "app_theme"

        /** Read saved language synchronously for base context configuration. */
        fun getSavedLanguageSync(context: Context): String {
            return context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        }

        /** Read the saved theme synchronously for the first frame / system bars. */
        fun getSavedThemeSync(context: Context): String {
            return context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        }

        /** Resolves whether the app should render in dark mode. */
        fun resolveDarkTheme(themeSetting: String, systemDark: Boolean): Boolean {
            return when (themeSetting) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> systemDark
            }
        }

        /**
         * Returns a [Context] wrapped with the in-app language setting.
         * Use for non-Compose callers (ViewModels, notifications, workers) so
         * resource lookups follow the app language, not the system language.
         */
        fun wrap(context: Context): Context {
            val locale = resolveLocale(getSavedLanguageSync(context))
            val config = Configuration(context.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            return context.createConfigurationContext(config)
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

/**
 * Tiny helper for ViewModels without a Compose context: resolves strings using
 * the current in-app language at call time (re-resolved on every [get], so it
 * never goes stale after the user switches language).
 */
class Localizer(private val appContext: Context) {
    fun get(@StringRes id: Int, vararg args: Any): String {
        val context = SettingsRepository.wrap(appContext)
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
