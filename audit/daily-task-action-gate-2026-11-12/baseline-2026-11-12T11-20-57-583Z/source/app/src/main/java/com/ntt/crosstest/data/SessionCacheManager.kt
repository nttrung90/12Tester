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
    val isDailyTaskPendingRefresh: Boolean = false,
    val pendingReportsCount: Int = 0,
    val notifications: List<UserNotification> = emptyList(),
    val unreadNotificationsCount: Int = 0,
    val questSummary: QuestSummary? = null,
    val timestamp: Long = 0L,
    val dateVn: String? = null,
    val sessionGeneration: Long = SessionCacheManager.currentGeneration(),
    val dailyTaskMutationVersion: Long = 0L,
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
                dailyTask = dailyTask?.takeIf { it.statusDate == todayStr },
                isDailyTaskPendingRefresh = false,
                questSummary = questSummary?.copy(dailyCount = 0),
                ownerStatsMap = ownerStatsMap.mapValues { (_, stats) -> stats.copy(testedTodayCount = 0) },
            )
        }
        if (dailyTask?.statusDate != null && dailyTask.statusDate != todayStr) {
            return copy(dailyTask = null, isDailyTaskPendingRefresh = false)
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
    private val dailyTaskVersion = AtomicLong(0L)
    private val diskLock = Any()
    private val publicationLock = Any()

    @Volatile
    private var inMemoryCache: CachedSessionData? = null

    @Volatile
    private var isTombstoned = false

    fun currentGeneration(): Long = generation.get()
    fun currentDailyTaskVersion(): Long = dailyTaskVersion.get()
    fun nextDailyTaskVersion(): Long = dailyTaskVersion.incrementAndGet()

    /**
     * Cập nhật nhanh in-memory cache sau các thao tác thay đổi số dư hoặc ưu tiên hiển thị.
     * Chặn ghi nhầm dữ liệu giữa các phiên/tài khoản bằng expectedUserId và expectedGeneration.
     */
    fun updateCachedData(
        expectedUserId: String? = null,
        expectedGeneration: Long? = null,
        transform: (CachedSessionData) -> CachedSessionData
    ): Boolean {
        synchronized(publicationLock) {
            if (isTombstoned) return false
            val currentGen = generation.get()
            if (expectedGeneration != null && currentGen != expectedGeneration) return false

            val current = inMemoryCache ?: if (expectedUserId != null) {
                CachedSessionData(
                    userId = expectedUserId,
                    sessionGeneration = expectedGeneration ?: currentGen,
                    dateVn = VnTime.today().toString()
                )
            } else {
                return false
            }

            // Strict UID check: reject any mismatch, including null guest when expectedUserId is identified
            if (expectedUserId != null && current.userId != expectedUserId) return false

            val transformed = transform(current)
            val newVer = dailyTaskVersion.get()
            inMemoryCache = transformed.copy(dailyTaskMutationVersion = newVer)
            return true
        }
    }

    /**
     * Synchronously returns the in-memory cache without performing any disk I/O.
     */
    fun peekMemory(): CachedSessionData? {
        if (isTombstoned) return null
        return synchronized(publicationLock) {
            if (isTombstoned) null else inMemoryCache?.sanitizeForDate()
        }
    }

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
        synchronized(publicationLock) {
            inMemoryCache?.let {
                if (generation.get() == initialGen && !isTombstoned) return it.sanitizeForDate()
            }
        }
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return synchronized(diskLock) {
            if (generation.get() != initialGen) return null
            synchronized(publicationLock) {
                inMemoryCache?.let { return it.sanitizeForDate() }
            }
            if (!file.exists()) return null
            try {
                val content = file.readText()
                if (generation.get() != initialGen) return null
                val data = json.decodeFromString<CachedSessionData>(content).sanitizeForDate()
                if (generation.get() != initialGen) return null
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
                synchronized(publicationLock) {
                    if (generation.get() != initialGen) return null
                    val currentInMem = inMemoryCache
                    val finalData = if (currentInMem != null &&
                        currentInMem.userId == data.userId &&
                        currentInMem.dateVn == data.dateVn &&
                        (currentInMem.dailyTask != null || currentInMem.isDailyTaskPendingRefresh) &&
                        (currentInMem.dailyTaskMutationVersion > data.dailyTaskMutationVersion ||
                         (currentInMem.dailyTaskMutationVersion == data.dailyTaskMutationVersion &&
                          data.isDailyTaskPendingRefresh && !currentInMem.isDailyTaskPendingRefresh) ||
                         dailyTaskVersion.get() > data.dailyTaskMutationVersion)
                    ) {
                        data.copy(
                            dailyTask = currentInMem.dailyTask ?: data.dailyTask,
                            missedStreak = currentInMem.dailyTask?.missedStreak ?: currentInMem.missedStreak,
                            isDailyTaskPendingRefresh = currentInMem.isDailyTaskPendingRefresh,
                            dailyTaskMutationVersion = currentInMem.dailyTaskMutationVersion
                        )
                    } else {
                        data
                    }
                    isTombstoned = false
                    inMemoryCache = finalData
                    dailyTaskVersion.set(maxOf(dailyTaskVersion.get(), finalData.dailyTaskMutationVersion))
                    finalData
                }
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
        val dailyTaskVerAtStart = dailyTaskVersion.get()

        val enrichedData = if (data.dateVn == null) {
            data.copy(dateVn = VnTime.today().toString())
        } else {
            data
        }

        synchronized(diskLock) {
            if (genAtStart != generation.get()) return@synchronized
            val inMem = inMemoryCache
            val finalData = if (inMem != null &&
                inMem.userId == enrichedData.userId &&
                inMem.dateVn == enrichedData.dateVn &&
                (inMem.dailyTask != null || inMem.isDailyTaskPendingRefresh) &&
                (inMem.dailyTaskMutationVersion > enrichedData.dailyTaskMutationVersion ||
                 (inMem.dailyTaskMutationVersion == enrichedData.dailyTaskMutationVersion &&
                  enrichedData.isDailyTaskPendingRefresh && !inMem.isDailyTaskPendingRefresh) ||
                 dailyTaskVersion.get() != dailyTaskVerAtStart)
            ) {
                enrichedData.copy(
                    dailyTask = inMem.dailyTask ?: enrichedData.dailyTask,
                    missedStreak = inMem.dailyTask?.missedStreak ?: inMem.missedStreak,
                    isDailyTaskPendingRefresh = inMem.isDailyTaskPendingRefresh,
                    dailyTaskMutationVersion = inMem.dailyTaskMutationVersion
                )
            } else {
                val assignedVer = maxOf(enrichedData.dailyTaskMutationVersion, dailyTaskVersion.get())
                enrichedData.copy(
                    dailyTaskMutationVersion = assignedVer
                )
            }

            val stagingFile = File(context.filesDir, "$FILE_NAME.${UUID.randomUUID()}.tmp")
            try {
                val content = json.encodeToString(finalData)
                stagingFile.writeText(content)

                if (genAtStart != generation.get()) {
                    stagingFile.delete()
                    return@synchronized
                }

                synchronized(publicationLock) {
                    if (genAtStart == generation.get()) {
                        val currentInMem = inMemoryCache
                        val publishedData = if (currentInMem != null &&
                            currentInMem.userId == finalData.userId &&
                            currentInMem.dateVn == finalData.dateVn &&
                            (currentInMem.dailyTask != null || currentInMem.isDailyTaskPendingRefresh) &&
                            (currentInMem.dailyTaskMutationVersion > finalData.dailyTaskMutationVersion ||
                             (currentInMem.dailyTaskMutationVersion == finalData.dailyTaskMutationVersion &&
                              finalData.isDailyTaskPendingRefresh && !currentInMem.isDailyTaskPendingRefresh) ||
                             dailyTaskVersion.get() > finalData.dailyTaskMutationVersion)
                        ) {
                            val merged = finalData.copy(
                                dailyTask = currentInMem.dailyTask ?: finalData.dailyTask,
                                missedStreak = currentInMem.dailyTask?.missedStreak ?: currentInMem.missedStreak,
                                isDailyTaskPendingRefresh = currentInMem.isDailyTaskPendingRefresh,
                                dailyTaskMutationVersion = currentInMem.dailyTaskMutationVersion
                            )
                            stagingFile.writeText(json.encodeToString(merged))
                            merged
                        } else {
                            finalData
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

                        isTombstoned = false
                        inMemoryCache = publishedData
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_HAS_SESSION, publishedData.userId != null)
                            .putString(KEY_LAST_USER_ID, publishedData.userId)
                            .commit()
                    } else {
                        stagingFile.delete()
                    }
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
        synchronized(publicationLock) {
            isTombstoned = true
            inMemoryCache = null
            dailyTaskVersion.set(0L)
        }
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
        synchronized(publicationLock) {
            isTombstoned = true
            inMemoryCache = null
            dailyTaskVersion.set(0L)
        }
        clearDisk(context, clearGen)
    }

    private fun clearDisk(context: Context, clearGen: Long) {
        synchronized(diskLock) {
            if (generation.get() != clearGen) return
            // The generation change already reset the task token. A delayed disk
            // cleanup must not reset tokens issued to the new current session.
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
