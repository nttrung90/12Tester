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
    private val testModeKey = stringPreferencesKey("test_mode")
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

    /**
     * Stored language setting:
     * - "system": follow system language (Vietnamese if device is vi, else English)
     * - "vi": force Vietnamese
     * - "en": force English
     * Default is "system".
     */
    val appLanguage: Flow<String> =
        context.dataStore.data.map { prefs -> normalizeLanguageSetting(prefs[appLanguageKey] ?: LANGUAGE_SYSTEM) }

    suspend fun setAppLanguage(languageCode: String) {
        val normalized = normalizeLanguageSetting(languageCode)
        // Save to SharedPreferences for fast synchronous bootstrap on app start
        context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, normalized)
            .apply()

        context.dataStore.edit { prefs -> prefs[appLanguageKey] = normalized }
        try {
            com.ntt.crosstest.work.NotificationHelper.updateChannelsLocale(context)
        } catch (_: Exception) {}
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

    /**
     * Stored tester workflow mode:
     * - "normal": keep standard manual next-app suggestion & open/install steps (default)
     * - "turbo": after submitting result, auto-select next app and auto-open/install it
     */
    val testMode: Flow<String> =
        context.dataStore.data.map { prefs -> normalizeTestMode(prefs[testModeKey]) }

    suspend fun setTestMode(mode: String) {
        val normalized = normalizeTestMode(mode)
        context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEST_MODE, normalized)
            .apply()

        context.dataStore.edit { prefs -> prefs[testModeKey] = normalized }
    }

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_EN = "en"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val TEST_MODE_NORMAL = "normal"
        const val TEST_MODE_TURBO = "turbo"

        private const val PREFS_SYNC_NAME = "testcheo_settings_sync"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_TEST_MODE = "test_mode"

        /** Normalizes stored test mode to one of: "normal", "turbo". */
        fun normalizeTestMode(mode: String?): String {
            return when (mode?.trim()?.lowercase()) {
                TEST_MODE_TURBO -> TEST_MODE_TURBO
                else -> TEST_MODE_NORMAL
            }
        }

        /** Reads saved test mode synchronously for instant UI initialization. */
        fun getSavedTestModeSync(context: Context): String {
            val raw = context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TEST_MODE, TEST_MODE_NORMAL)
            return normalizeTestMode(raw)
        }

        /** Normalizes stored language setting to one of: "system", "vi", "en". */
        fun normalizeLanguageSetting(setting: String?): String {
            return when (setting?.trim()?.lowercase()) {
                LANGUAGE_VI -> LANGUAGE_VI
                LANGUAGE_EN -> LANGUAGE_EN
                else -> LANGUAGE_SYSTEM
            }
        }

        /** Read saved language synchronously for base context configuration. */
        fun getSavedLanguageSync(context: Context): String {
            val raw = context.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM)
            return normalizeLanguageSetting(raw)
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
         * Returns the real system locale from device resources, unaffected
         * by any in-app activity context overrides.
         */
        fun getSystemLocale(): Locale {
            return try {
                Resources.getSystem().configuration.locales[0]
            } catch (_: Exception) {
                null
            } ?: Locale.getDefault()
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
            return when (normalizeLanguageSetting(setting)) {
                LANGUAGE_VI -> Locale.forLanguageTag("vi")
                LANGUAGE_EN -> Locale.forLanguageTag("en")
                else -> {
                    val sysLoc = getSystemLocale()
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
    val context: Context get() = appContext
    fun get(@StringRes id: Int, vararg args: Any): String {
        val context = SettingsRepository.wrap(appContext)
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
