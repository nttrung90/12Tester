package com.ntt.crosstest.data

import android.content.Context
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import com.ntt.crosstest.util.VnTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of the user's latest session data for instant, offline-first app opening.
 *
 * Campaign của tab "Đang mở" không được lưu riêng — mỗi [CampaignDisplayItem]
 * đã chứa sẵn campaign, lưu thêm list song song sẽ làm file JSON phình gần
 * gấp đôi; bên đọc derive lại qua `displayItems.map { it.campaign }`.
 */
@Serializable
data class CachedSessionData(
    val userId: String? = null,
    val userProfile: Profile? = null,
    val displayItems: List<CampaignDisplayItem> = emptyList(),
    val pausedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val completedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val myCampaigns: List<Campaign> = emptyList(),
    val joinedCampaigns: List<Campaign> = emptyList(),
    val leaderboard: List<LeaderboardUser> = emptyList(),
    val myRank: MyRank? = null,
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
    val dateVn: String? = null,
    val sessionGeneration: Long = SessionCacheManager.currentGeneration(),
) {
    /**
     * Lọc và làm sạch dữ liệu nếu snapshot thuộc về ngày cũ ở Việt Nam.
     * Tránh việc mở app ngày mới nhưng các chỉ số "hôm nay" vẫn hiển thị dữ liệu hôm qua.
     */
    fun sanitizeForDate(): CachedSessionData {
        val todayStr = VnTime.today().toString()
        val isOldDate = if (dateVn != null) {
            dateVn != todayStr
        } else if (timestamp > 0) {
            try {
                java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .toLocalDate()
                    .toString() != todayStr
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (isOldDate) {
            return copy(
                todayCheckinCount = 0,
                displayItems = displayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) },
                pausedDisplayItems = pausedDisplayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) },
                completedDisplayItems = completedDisplayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) },
                dailyTask = if (dailyTask?.statusDate != null && dailyTask.statusDate != todayStr) null else dailyTask,
                questSummary = questSummary?.copy(dailyCount = 0),
                ownerStatsMap = ownerStatsMap.mapValues { (_, stats) -> stats.copy(testedTodayCount = 0) },
            )
        }
        return this
    }
}

/**
 * High-performance disk cache manager for the user's last session.
 * Employs atomic file writes, fast JSON serialization, and generation scoping.
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

    val generation = AtomicLong(1L)
    private val diskLock = Any()

    @Volatile
    private var inMemoryCache: CachedSessionData? = null

    @Volatile
    private var isTombstoned = false

    fun currentGeneration(): Long = generation.get()

    /**
     * Cập nhật nhanh in-memory cache sau các thao tác thay đổi số dư hoặc ưu tiên hiển thị.
     * Chặn ghi nhầm dữ liệu giữa các phiên/tài khoản bằng expectedUserId và expectedGeneration.
     */
    fun updateCachedData(
        expectedUserId: String? = null,
        expectedGeneration: Long? = null,
        transform: (CachedSessionData) -> CachedSessionData
    ): Boolean {
        val current = inMemoryCache ?: return false
        if (expectedUserId != null && current.userId != expectedUserId) return false
        if (expectedGeneration != null && generation.get() != expectedGeneration) return false
        inMemoryCache = transform(current)
        return true
    }

    /**
     * Synchronously returns the in-memory cache without performing any disk I/O.
     */
    fun peekMemory(): CachedSessionData? = if (isTombstoned) null else inMemoryCache?.sanitizeForDate()

    /**
     * Fast O(1) synchronous check to know if an authenticated session exists on disk.
     */
    fun hasCachedSession(context: Context): Boolean {
        if (isTombstoned) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SESSION, false)
    }

    /**
     * Synchronously loads the cached session from memory or disk on ViewModel startup.
     */
    fun loadSync(context: Context): CachedSessionData? {
        val initialGen = generation.get()
        if (isTombstoned) return null
        inMemoryCache?.let {
            if (generation.get() == initialGen && !isTombstoned) return it.sanitizeForDate()
        }
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return synchronized(diskLock) {
            if (generation.get() != initialGen || isTombstoned) return null
            inMemoryCache?.let { return it.sanitizeForDate() }
            if (!file.exists()) return null
            try {
                val content = file.readText()
                if (generation.get() != initialGen || isTombstoned) return null
                val data = json.decodeFromString<CachedSessionData>(content).sanitizeForDate()
                if (generation.get() != initialGen || isTombstoned) return null
                val expectedUid = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_USER_ID, null)
                if (data.userId != expectedUid) {
                    try {
                        file.delete()
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    } catch (_: Exception) {}
                    return null
                }
                inMemoryCache = data
                data
            } catch (_: Exception) {
                try {
                    file.delete()
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                } catch (_: Exception) {}
                null
            }
        }
    }

    /**
     * Làm nóng bản RAM từ đĩa trên thread IO ngay khi process khởi động.
     * Constructor ViewModel sau đó gọi [loadSync] trên main thread chỉ đọc RAM,
     * không phải chịu I/O đĩa + decode JSON ở lần đầu process.
     */
    fun preload(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch(Dispatchers.IO) { loadSync(context) }
    }

    /**
     * Atomically saves session snapshot to disk with generation check and unique staging file.
     */
    suspend fun save(context: Context, data: CachedSessionData, targetGeneration: Long? = null) = withContext(Dispatchers.IO) {
        val genAtStart = targetGeneration ?: data.sessionGeneration
        if (genAtStart != generation.get()) return@withContext

        val enrichedData = if (data.dateVn == null) {
            data.copy(dateVn = VnTime.today().toString())
        } else {
            data
        }

        synchronized(diskLock) {
            if (genAtStart != generation.get()) return@synchronized
            val stagingFile = File(context.filesDir, "$FILE_NAME.${UUID.randomUUID()}.tmp")
            try {
                val content = json.encodeToString(enrichedData)
                stagingFile.writeText(content)

                if (genAtStart != generation.get()) {
                    stagingFile.delete()
                    return@synchronized
                }

                val targetFile = File(context.filesDir, FILE_NAME)
                try {
                    Files.move(
                        stagingFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        stagingFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }

                if (genAtStart == generation.get()) {
                    isTombstoned = false
                    inMemoryCache = enrichedData
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_HAS_SESSION, enrichedData.userId != null)
                        .putString(KEY_LAST_USER_ID, enrichedData.userId)
                        .commit()
                } else {
                    targetFile.delete()
                }
            } catch (_: Exception) {
                // Non-critical if disk write fails
            } finally {
                if (stagingFile.exists()) {
                    stagingFile.delete()
                }
            }
        }
    }

    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Clears all cached session data from memory and disk on user sign out or session termination.
     * Increments generation to safely invalidate any pending or in-flight save operations.
     */
    fun clear(context: Context) {
        val clearGen = generation.incrementAndGet()
        isTombstoned = true
        inMemoryCache = null
        val isMainThread = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Exception) {
            false
        }
        if (isMainThread) {
            scope.launch { clearDisk(context, clearGen) }
        } else {
            clearDisk(context, clearGen)
        }
    }

    fun clearSync(context: Context) {
        val clearGen = generation.incrementAndGet()
        isTombstoned = true
        inMemoryCache = null
        clearDisk(context, clearGen)
    }

    private fun clearDisk(context: Context, clearGen: Long) {
        synchronized(diskLock) {
            if (generation.get() != clearGen) return
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists()) file.delete()
                val dir = context.filesDir
                dir.listFiles { _, name -> name.startsWith(FILE_NAME) }?.forEach { it.delete() }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            } catch (_: Exception) {}
        }
    }
}
