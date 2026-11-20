package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
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

    private val cacheLock = Any()
    private var mutationSeq: Long = 0L
    private var latestPublishedMutation: Long = 0L
    private var inFlightMutations: Int = 0
    private var requestSeq: Long = 0L
    private var latestPublishedRequest: Long = 0L

    init {
        // Nạp cache đĩa ngay khi khởi động — lần mở app đầu không phải chờ mạng.
        loadFromDisk()?.let {
            cachedConfig = it
            cachedAtMs = System.currentTimeMillis()
            EgressOptimizationConfig.applyFromAppConfig(it)
        }
    }

    private fun loadFromDisk(): AppConfig? = try {
        prefs?.getString(KEY_CONFIG_JSON, null)?.let { json.decodeFromString<AppConfig>(it) }
    } catch (_: Exception) {
        null
    }

    private fun saveToDisk(config: AppConfig) {
        try {
            prefs?.edit()?.putString(KEY_CONFIG_JSON, json.encodeToString(config))?.commit()
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
        val (requestId, mutationGenAtStart, hadInFlightMutationsAtStart) = synchronized(cacheLock) {
            requestSeq++
            Triple(requestSeq, mutationSeq, inFlightMutations > 0)
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
                synchronized(cacheLock) {
                    val hasMutationRace = hadInFlightMutationsAtStart ||
                        inFlightMutations > 0 ||
                        mutationSeq != mutationGenAtStart ||
                        latestPublishedMutation > mutationGenAtStart

                    val isOlderRequest = requestId < latestPublishedRequest

                    if (!hasMutationRace && !isOlderRequest) {
                        latestPublishedRequest = requestId
                        cachedConfig = config
                        cachedAtMs = System.currentTimeMillis()
                        saveToDisk(config)
                        EgressOptimizationConfig.applyFromAppConfig(config)
                    }
                }
            }
            if (config == null && forceRefresh) null else cachedConfig ?: config
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        titleVi: String? = null,
        titleEn: String? = null,
        messageVi: String? = null,
        messageEn: String? = null,
    ): AppConfig = withContext(Dispatchers.IO) {
        val mutationId = synchronized(cacheLock) {
            mutationSeq++
            inFlightMutations++
            mutationSeq
        }
        try {
            val updated = try {
                supabase.postgrest
                    .rpc(
                        "admin_update_app_config",
                        AdminUpdateAppConfigParams(
                            minVersionCode = minVersionCode,
                            latestVersionCode = latestVersionCode,
                            latestVersionName = latestVersionName,
                            forceUpdate = forceUpdate,
                            title = title ?: titleVi ?: titleEn,
                            message = message ?: messageVi ?: messageEn,
                            storeUrl = storeUrl,
                            titleVi = titleVi,
                            titleEn = titleEn,
                            messageVi = messageVi,
                            messageEn = messageEn,
                        )
                    )
                    .decodeAs<AppConfig>()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message.orEmpty().lowercase()
                val isPermissionError = msg.contains("42501") ||
                    msg.contains("permission denied") ||
                    msg.contains("forbidden") ||
                    msg.contains("unauthorized") ||
                    msg.contains("not_admin") ||
                    msg.contains("not_authenticated")
                if (isPermissionError) {
                    throw e
                }

                val isCapabilityError = msg.contains("pgrst202") ||
                    msg.contains("42883") ||
                    msg.contains("function not found") ||
                    msg.contains("could not find a function")

                val hasEnglish = !titleEn.isNullOrBlank() || !messageEn.isNullOrBlank()
                if (isCapabilityError && !hasEnglish) {
                    supabase.postgrest
                        .rpc(
                            "admin_update_app_config",
                            AdminUpdateAppConfigLegacyParams(
                                minVersionCode = minVersionCode,
                                latestVersionCode = latestVersionCode,
                                latestVersionName = latestVersionName,
                                forceUpdate = forceUpdate,
                                title = titleVi ?: title,
                                message = messageVi ?: message,
                                storeUrl = storeUrl,
                            )
                        )
                        .decodeAs<AppConfig>()
                } else {
                    throw e
                }
            }
            synchronized(cacheLock) {
                if (mutationId > latestPublishedMutation) {
                    latestPublishedMutation = mutationId
                    cachedConfig = updated
                    cachedAtMs = System.currentTimeMillis()
                    saveToDisk(updated)
                    EgressOptimizationConfig.applyFromAppConfig(updated)
                }
            }
            updated
        } finally {
            synchronized(cacheLock) {
                inFlightMutations--
            }
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val PREFS_NAME = "app_config_cache"
        private const val KEY_CONFIG_JSON = "android_client_config"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
