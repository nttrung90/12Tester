package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository to fetch remote app configuration (update version, store URL, force update flag).
 *
 * Successful reads are cached in memory for [CACHE_TTL_MS] so app launch does not
 * block on the network every time. Callers that need the freshest data (manual
 * "Check for updates" in Profile, background worker) pass forceRefresh = true.
 */
class AppConfigRepository(private val supabase: SupabaseClient) {

    @Volatile
    private var cachedConfig: AppConfig? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    /**
     * Fetches current app configuration for Android client.
     * Returns null if offline or error occurs, ensuring fail-open resilience.
     * A failed refresh does NOT clear the existing cache but also does not
     * return stale data, so callers can show an honest "cannot check" state.
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
            }
            config
        } catch (_: Exception) {
            null
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
        updated
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
