package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository to fetch remote app configuration (update version, store URL, force update flag).
 *
 * Successful reads are cached in memory for [CACHE_TTL_MS] and persisted to disk
 * so the very first frame after a cold start can show the update dialog without
 * waiting for the network. Callers that need the freshest data (manual
 * "Check for updates" in Profile, background worker) pass forceRefresh = true.
 */
class AppConfigRepository(
    private val supabase: SupabaseClient,
    private val appContext: android.content.Context? = null,
) {

    @Volatile
    private var cachedConfig: AppConfig? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    private val prefs by lazy {
        appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    init {
        // Nạp cache đĩa ngay khi khởi động — lần mở app đầu không phải chờ mạng.
        loadFromDisk()?.let {
            cachedConfig = it
            cachedAtMs = System.currentTimeMillis()
        }
    }

    private fun loadFromDisk(): AppConfig? = try {
        prefs?.getString(KEY_CONFIG_JSON, null)?.let { json.decodeFromString<AppConfig>(it) }
    } catch (_: Exception) {
        null
    }

    private fun saveToDisk(config: AppConfig) {
        try {
            prefs?.edit()?.putString(KEY_CONFIG_JSON, json.encodeToString(config))?.apply()
        } catch (_: Exception) {
            // Non-critical nếu ghi đĩa thất bại.
        }
    }

    /**
     * Fetches current app configuration for Android client.
     * When forceRefresh = false and the network fails, falls back to the last
     * known config (disk/RAM) so the app opens fail-open; a manual forced
     * refresh returns null on failure so the UI can show an honest error.
     */
    suspend fun getAppConfig(forceRefresh: Boolean = false): AppConfig? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedConfig?.let { cached ->
                if (now - cachedAtMs < CACHE_TTL_MS) return@withContext cached
            }
        }
        try {
            val config = supabase.from("app_configs")
                .select {
                    filter { eq("id", "android_client") }
                    limit(1)
                }
                .decodeList<AppConfig>()
                .firstOrNull()
            if (config != null) {
                cachedConfig = config
                cachedAtMs = System.currentTimeMillis()
                saveToDisk(config)
            }
            if (config == null && forceRefresh) null else config ?: cachedConfig
        } catch (_: Exception) {
            if (forceRefresh) null else cachedConfig
        }
    }

    /**
     * Admin-only: updates the remote update configuration via RPC
     * admin_update_app_config (migration 019) and refreshes the cache.
     * Throws on failure so the caller can surface an error.
     */
    suspend fun updateAppConfig(
        minVersionCode: Int,
        latestVersionCode: Int,
        latestVersionName: String,
        forceUpdate: Boolean,
        title: String?,
        message: String?,
        storeUrl: String?,
    ): AppConfig = withContext(Dispatchers.IO) {
        val updated = supabase.postgrest
            .rpc(
                "admin_update_app_config",
                AdminUpdateAppConfigParams(
                    minVersionCode = minVersionCode,
                    latestVersionCode = latestVersionCode,
                    latestVersionName = latestVersionName,
                    forceUpdate = forceUpdate,
                    title = title,
                    message = message,
                    storeUrl = storeUrl,
                )
            )
            .decodeAs<AppConfig>()
        cachedConfig = updated
        cachedAtMs = System.currentTimeMillis()
        saveToDisk(updated)
        updated
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val PREFS_NAME = "app_config_cache"
        private const val KEY_CONFIG_JSON = "android_client_config"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
