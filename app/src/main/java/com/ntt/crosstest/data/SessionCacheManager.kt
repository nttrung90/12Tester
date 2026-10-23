package com.ntt.crosstest.data

import android.content.Context
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Snapshot of the user's latest session data for instant, offline-first app opening.
 */
@Serializable
data class CachedSessionData(
    val userId: String? = null,
    val userProfile: Profile? = null,
    val openCampaigns: List<Campaign> = emptyList(),
    val displayItems: List<CampaignDisplayItem> = emptyList(),
    val pausedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val completedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val myCampaigns: List<Campaign> = emptyList(),
    val joinedCampaigns: List<Campaign> = emptyList(),
    val leaderboard: List<LeaderboardUser> = emptyList(),
    val ownerStatsMap: Map<String, CampaignOwnerStats> = emptyMap(),
    val userCredits: Int = 0,
    val todayCheckinCount: Int = 0,
    val missedStreak: Int = 0,
    val dailyTask: DailyTaskStatus? = null,
    val pendingReportsCount: Int = 0,
    val notifications: List<UserNotification> = emptyList(),
    val unreadNotificationsCount: Int = 0,
    val questSummary: QuestSummary? = null,
    val timestamp: Long = 0L,
)

/**
 * High-performance disk cache manager for the user's last session.
 * Employs atomic file writes and fast JSON serialization.
 */
object SessionCacheManager {
    private const val FILE_NAME = "last_session_cache.json"
    private const val PREFS_NAME = "session_cache_meta"
    private const val KEY_HAS_SESSION = "has_cached_session"
    private const val KEY_LAST_USER_ID = "last_user_id"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var inMemoryCache: CachedSessionData? = null

    /**
     * Fast O(1) synchronous check to know if an authenticated session exists on disk.
     */
    fun hasCachedSession(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SESSION, false)
    }

    /**
     * Synchronously loads the cached session (< 2ms) from disk on ViewModel startup.
     */
    fun loadSync(context: Context): CachedSessionData? {
        inMemoryCache?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            val data = json.decodeFromString<CachedSessionData>(content)
            inMemoryCache = data
            data
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomically saves session snapshot to disk.
     */
    suspend fun save(context: Context, data: CachedSessionData) = withContext(Dispatchers.IO) {
        inMemoryCache = data
        try {
            val file = File(context.filesDir, FILE_NAME)
            val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
            val content = json.encodeToString(data)
            tempFile.writeText(content)
            if (tempFile.renameTo(file) || (file.delete() && tempFile.renameTo(file))) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    // Guest snapshots (userId == null) must not mark an
                    // authenticated session, otherwise cold start would skip
                    // the login screen with no real session.
                    .putBoolean(KEY_HAS_SESSION, data.userId != null)
                    .putString(KEY_LAST_USER_ID, data.userId)
                    .apply()
            }
        } catch (_: Exception) {
            // Non-critical if disk write fails
        }
    }

    /**
     * Clears all cached session data from disk on user sign out.
     */
    fun clear(context: Context) {
        inMemoryCache = null
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
            val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
            if (tempFile.exists()) tempFile.delete()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (_: Exception) {}
    }
}
