package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.LeaderboardUser
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.MyRank
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.TopupPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.ntt.crosstest.data.CachedSessionData
import com.ntt.crosstest.data.DailyTaskChangeEvent
import com.ntt.crosstest.data.EgressTriggerSource
import com.ntt.crosstest.data.CampaignStats
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.NotificationEvent
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.data.UserWarning
import com.ntt.crosstest.data.QuestSummary
import com.ntt.crosstest.ui.components.CreditReward
import com.ntt.crosstest.ui.components.CreditRewardType
import com.ntt.crosstest.util.NetworkMonitor
import com.ntt.crosstest.util.InstalledAppCache
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class CampaignDisplayItem(
    val campaign: Campaign,
    val ownerName: String = "",
    val installedCount: Int = 0,
    val todayTestedCount: Int = 0,
    val hasTestedToday: Boolean = false,
    /** App đang có sẵn trên máy của người test (kiểm tra qua PackageManager). */
    val isInstalledLocally: Boolean = false,
    val homeTestedCount: Int? = null,
    val statsDate: String? = null,
) {
    val dailyTarget: Int get() = campaign.dailyTarget
    val isTargetReached: Boolean get() = todayTestedCount >= dailyTarget
    val displayTestedCount: Int? get() = when {
        homeTestedCount != null -> homeTestedCount
        statsDate != null -> if (statsDate == vnToday()) todayTestedCount else null
        todayTestedCount > 0 -> todayTestedCount
        else -> null
    }
}

private fun vnToday(): String =
    LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()

/**
 * Chỉ dùng trạng thái của đúng ngày VN hiện tại. Member đang mở app nghĩa là
 * đã đăng nhập, kể cả khi RPC trả về chậm hơn thao tác ghi nhận.
 */
internal fun DailyTaskStatus?.forCurrentUi(expectedDate: String = vnToday()): DailyTaskStatus? {
    val task = this ?: return null
    if (task.statusDate != null && task.statusDate != expectedDate) return null
    return if (task.isMember && !task.loggedInToday) {
        task.copy(
            loggedInToday = true,
            met = true,
            rawMet = if (task.rawMet != null) true else null,
        )
    } else {
        task
    }
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val isBackgroundSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val isAuthenticated: Boolean = false,
    val openCampaigns: List<Campaign> = emptyList(),
    val displayItems: List<CampaignDisplayItem> = emptyList(),
    val pausedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val completedDisplayItems: List<CampaignDisplayItem> = emptyList(),
    val homeOrderDate: String? = null,
    /** null = not searching; non-null = search results for [query] */
    val searchResults: List<CampaignDisplayItem>? = null,
    val myCampaigns: List<Campaign> = emptyList(),
    val joinedCampaigns: List<Campaign> = emptyList(),
    val leaderboard: List<LeaderboardUser> = emptyList(),
    val isLeaderboardLoading: Boolean = false,
    /** Hạng của người dùng hiện tại theo bảng Top Credit (thẻ ghim trong tab Xếp hạng). */
    val myRank: MyRank? = null,
    /** Bảng xếp hạng theo tổng lượt test (sub-tab "Top Test"). */
    val testLeaderboard: List<LeaderboardUser> = emptyList(),
    val isTestLeaderboardLoading: Boolean = false,
    /** Hạng của người dùng hiện tại theo bảng Top Test (thẻ ghim trong sub-tab "Top Test"). */
    val testMyRank: MyRank? = null,
    val query: String = "",
    val error: String? = null,
    val todayCheckinCount: Int = 0,
    val userCredits: Int = 0,
    val missedStreak: Int = 0,
    /** Nhiệm vụ ngày mới; null khi server chưa deploy migration 036. */
    val dailyTask: DailyTaskStatus? = null,
    /** Đang chờ làm mới lại nhiệm vụ ngày sau khi thay đổi campaign (lỗi mạng tạm thời hoặc stale payload). */
    val isDailyTaskPendingRefresh: Boolean = false,
    val isDailyTaskError: Boolean = false,
    /** true khi lần tải lịch sử Credit gần nhất thất bại (mạng lỗi) — dialog báo tải lại. */
    val creditLogsLoadFailed: Boolean = false,
    val userProfile: Profile? = null,
    val pendingReportsCount: Int = 0,
    val ownerStatsMap: Map<String, CampaignOwnerStats> = emptyMap(),
    /** Thống kê theo campaign id cho danh sách "App đã tham gia" (đã cài / test hôm nay). */
    val joinedStatsMap: Map<String, CampaignStats> = emptyMap(),
    /** Tên chủ app theo owner id — dùng cho thẻ trong danh sách "App đã tham gia". */
    val ownerNameById: Map<String, String> = emptyMap(),
    val unreadWarning: UserWarning? = null,
    val weeklyWarningsCount: Int = 0,
    val creditLogs: List<CreditLog> = emptyList(),
    val topupPackages: List<TopupPackage> = com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES,
    val notifications: List<UserNotification> = emptyList(),
    val unreadNotificationsCount: Int = 0,
    val questSummary: QuestSummary? = null,
    val claimingMilestone: String? = null,
    /** Danh sách nhiệm vụ do người dùng tạo cho sub-tab "Nhiệm vụ App" (migration 039). */
    val missions: List<Mission> = emptyList(),
    val isMissionsLoading: Boolean = false,
    val missionsError: String? = null,
    val joiningMissionId: String? = null,
    /** Quest claim / top-up reward shown above the Home screen. */
    val reward: CreditReward? = null,
)

class HomeViewModel(
    private val repository: CampaignRepository,
    private val context: android.content.Context? = null,
    private val localizer: Localizer,
    private val reportSubmitManager: ReportSubmitManager? = null,
) : ViewModel() {

    private val refreshSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskRequestSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val missionRefreshSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val missionMutationVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private val staleFeedRetryCount = java.util.concurrent.atomic.AtomicInteger(0)

    private val _uiState = MutableStateFlow(
        run {
            val cached = context?.let { SessionCacheManager.peekMemory() }
            // Ignore a snapshot that provably belongs to a different account.
            // While the session is still loading (currentUid == null) the cache
            // is kept to preserve the offline-first instant paint.
            val currentUid = repository.currentUserId()
            val cacheMatchesSession = cached != null &&
                (cached.userId == null || currentUid == null || cached.userId == currentUid)
            if (cached != null && cacheMatchesSession) {
                val todayStr = repository.businessDay().toString()
                val sortedDisplay = DailyHomeOrder.sortOpenCampaigns(cached.displayItems, todayStr)
                val sortedPaused = DailyHomeOrder.sortOtherCampaigns(cached.pausedDisplayItems, todayStr)
                val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(cached.completedDisplayItems, todayStr)
                HomeUiState(
                    isLoading = false,
                    isBackgroundSyncing = true,
                    isAuthenticated = cached.userId != null,
                    openCampaigns = sortedDisplay.map { it.campaign },
                    displayItems = sortedDisplay,
                    pausedDisplayItems = sortedPaused,
                    completedDisplayItems = sortedCompleted,
                    homeOrderDate = todayStr,
                    myCampaigns = cached.myCampaigns,
                    joinedCampaigns = cached.joinedCampaigns,
                    leaderboard = cached.leaderboard,
                    myRank = cached.myRank,
                    ownerStatsMap = cached.ownerStatsMap,
                    ownerNameById = (sortedDisplay + sortedPaused + sortedCompleted)
                        .filter { it.ownerName.isNotBlank() }
                        .mapNotNull { item -> item.campaign.id?.let { it to item.ownerName } }
                        .toMap(),
                    joinedStatsMap = (sortedDisplay + sortedPaused + sortedCompleted)
                        .mapNotNull { item ->
                            item.campaign.id?.let { id ->
                                id to CampaignStats(
                                    installedCount = item.installedCount,
                                    todayTestedCount = item.todayTestedCount,
                                    hasTestedToday = item.hasTestedToday,
                                    homeTestedCount = item.homeTestedCount,
                                    statsDate = item.statsDate,
                                )
                            }
                        }
                        .toMap(),
                    userCredits = cached.userCredits,
                    todayCheckinCount = cached.todayCheckinCount,
                    missedStreak = cached.missedStreak,
                    dailyTask = cached.dailyTask.forCurrentUi(),
                    isDailyTaskPendingRefresh = cached.isDailyTaskPendingRefresh,
                    userProfile = cached.userProfile,
                    pendingReportsCount = cached.pendingReportsCount,
                    notifications = cached.notifications,
                    unreadNotificationsCount = cached.unreadNotificationsCount,
                    questSummary = cached.questSummary,
                )
            } else {
                HomeUiState()
            }
        }
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var dailyLoginJob: Job? = null
    private var midnightJob: Job? = null

    fun scheduleMidnightRefresh() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (true) {
                try {
                    val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
                    val currentDay = repository.businessDay()
                    val now = java.time.ZonedDateTime.now(zone)
                    val nextMidnight = currentDay.plusDays(1).atStartOfDay(zone).plusSeconds(1)
                    val delayMs = java.time.Duration.between(now, nextMidnight).toMillis().coerceIn(1000L, 86_400_000L)
                    kotlinx.coroutines.delay(delayMs)
                    refresh(forceRefresh = true)

                    // Chờ thêm đến 00:05:05 VN để tự động tải lại chỉ tiêu cộng dồn khi cron chốt ngày xong
                    val nextCronFinalize = currentDay.plusDays(1).atStartOfDay(zone).plusMinutes(5).plusSeconds(5)
                    val nowAfterMidnight = java.time.ZonedDateTime.now(zone)
                    if (nowAfterMidnight.isBefore(nextCronFinalize)) {
                        val waitCronMs = java.time.Duration.between(nowAfterMidnight, nextCronFinalize).toMillis()
                        kotlinx.coroutines.delay(waitCronMs.coerceIn(1000L, 360_000L))
                        refresh(forceRefresh = true)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(60_000L)
                }
            }
        }
    }

    fun stopMidnightScheduler() {
        midnightJob?.cancel()
        midnightJob = null
        stopPendingCarryoverRetry()
    }

    /** Gom dữ liệu cá nhân của Trang chủ, dùng chung cho fast path (dashboard RPC) và legacy. */
    private data class PersonalData(
        val mine: List<Campaign> = emptyList(),
        val joined: List<Campaign> = emptyList(),
        val leaderboard: List<LeaderboardUser> = emptyList(),
        val myRank: MyRank? = null,
        val todayCheckins: Int = 0,
        val myProfile: Profile? = null,
        val dailyTask: DailyTaskStatus? = null,
        val pendingReports: Int = 0,
        val unreadWarnings: List<UserWarning> = emptyList(),
        val weeklyWarnings: Int = 0,
        val logs: List<CreditLog> = emptyList(),
        val packages: List<TopupPackage> = com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES,
        val notifications: List<UserNotification> = emptyList(),
        val quests: QuestSummary? = null,
    )

    /** uid của phiên đã đồng bộ thành công — tránh refresh lặp lại mỗi lần token làm mới. */
    private var lastSyncedUid: String? = null
    private var renderedUserId: String? = null
    private var leaderboardLoaded = false
    private var testLeaderboardLoaded = false

    /**
     * Đánh dấu refresh() đã bỏ dở vì phiên còn đang khôi phục (Initializing/RefreshFailure).
     * Khi phiên sẵn sàng trở lại (kể cả cùng uid — ẩn app rồi mở lại), observeSession
     * sẽ refresh đúng một lần để dữ liệu không bị cũ.
     */
    private var refreshWhenSessionReady = false

    init {
        val initUid = repository.currentUserId()
        val initGen = SessionCacheManager.currentGeneration()
        val initDailyTaskVersion = SessionCacheManager.currentDailyTaskVersion()
        val initBusinessDay = repository.businessDay().toString()
        if (_uiState.value.openCampaigns.isEmpty() && context != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val loaded = SessionCacheManager.loadSync(context)
                val curUid = repository.currentUserId()
                val curGen = SessionCacheManager.currentGeneration()
                if (curGen == initGen && curUid == initUid && loaded != null && (loaded.userId == curUid)) {
                    val todayStr = repository.businessDay().toString()
                    val sortedDisplay = DailyHomeOrder.sortOpenCampaigns(loaded.displayItems, todayStr)
                    val sortedPaused = DailyHomeOrder.sortOtherCampaigns(loaded.pausedDisplayItems, todayStr)
                    val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(loaded.completedDisplayItems, todayStr)
                    val notifs = loaded.notifications.map {
                        if (curUid != null && repository.isNotificationLocallyRead(curUid, it.id)) it.copy(isRead = true) else it
                    }
                    _uiState.update { current ->
                        if (current.openCampaigns.isEmpty() && SessionCacheManager.currentGeneration() == initGen && repository.currentUserId() == initUid) {
                            val preserveCurrentTask = SessionCacheManager.currentDailyTaskVersion() != initDailyTaskVersion ||
                                repository.businessDay().toString() != initBusinessDay ||
                                current.dailyTask != null
                            val targetDailyTask = if (preserveCurrentTask) current.dailyTask else loaded.dailyTask?.forCurrentUi(todayStr)
                            val targetMissedStreak = if (preserveCurrentTask) current.missedStreak else loaded.missedStreak
                            val targetIsPendingRefresh = if (preserveCurrentTask) current.isDailyTaskPendingRefresh else loaded.isDailyTaskPendingRefresh

                            current.copy(
                                isLoading = false,
                                isBackgroundSyncing = true,
                                isAuthenticated = loaded.userId != null,
                                openCampaigns = sortedDisplay.map { it.campaign },
                                displayItems = sortedDisplay,
                                pausedDisplayItems = sortedPaused,
                                completedDisplayItems = sortedCompleted,
                                homeOrderDate = todayStr,
                                myCampaigns = loaded.myCampaigns,
                                joinedCampaigns = loaded.joinedCampaigns,
                                leaderboard = loaded.leaderboard,
                                myRank = loaded.myRank,
                                ownerStatsMap = loaded.ownerStatsMap,
                                userCredits = loaded.userCredits,
                                todayCheckinCount = loaded.todayCheckinCount,
                                missedStreak = targetMissedStreak,
                                dailyTask = targetDailyTask,
                                isDailyTaskPendingRefresh = targetIsPendingRefresh,
                                userProfile = loaded.userProfile,
                                pendingReportsCount = loaded.pendingReportsCount,
                                notifications = notifs,
                                unreadNotificationsCount = notifs.count { !it.isRead },
                                questSummary = loaded.questSummary,
                            )
                        } else current
                    }
                }
            }
        }
        refresh(context = context, forceRefresh = false, triggerSource = EgressTriggerSource.COLD_START)
        observeSession()
        observeSubmittedReports()
        observeNotificationEvents()
        observeDailyTaskEvents()
        observeInstalledPackages()
        scheduleMidnightRefresh()
    }

    private fun observeInstalledPackages() {
        val appContext = context ?: return
        viewModelScope.launch {
            InstalledAppCache.versionFlow.collect {
                recomputeInstalledFlags(appContext)
            }
        }
    }

    fun refreshInstalledStatus(context: android.content.Context? = null) {
        val appContext = context ?: this.context ?: return
        InstalledAppCache.invalidateFreshness()
        viewModelScope.launch(Dispatchers.IO) {
            InstalledAppCache.refreshPackages(appContext)
            recomputeInstalledFlags(appContext)
        }
    }

    private suspend fun recomputeInstalledFlags(appContext: android.content.Context) {
        val stateSnapshot = _uiState.value
        val allPackages = buildSet {
            stateSnapshot.displayItems.forEach { add(it.campaign.packageName) }
            stateSnapshot.pausedDisplayItems.forEach { add(it.campaign.packageName) }
            stateSnapshot.completedDisplayItems.forEach { add(it.campaign.packageName) }
            stateSnapshot.searchResults?.forEach { add(it.campaign.packageName) }
        }.filter { it.isNotBlank() }
        if (allPackages.isEmpty()) return

        val installFlags = withContext(Dispatchers.IO) {
            allPackages.associateWith { pkg -> InstalledAppCache.isInstalled(appContext, pkg) }
        }

        fun List<CampaignDisplayItem>.applyInstallFlags(): List<CampaignDisplayItem> = map { item ->
            val installed = installFlags[item.campaign.packageName] ?: item.isInstalledLocally
            if (item.isInstalledLocally == installed) item else item.copy(isInstalledLocally = installed)
        }

        _uiState.update { current ->
            current.copy(
                displayItems = current.displayItems.applyInstallFlags(),
                pausedDisplayItems = current.pausedDisplayItems.applyInstallFlags(),
                completedDisplayItems = current.completedDisplayItems.applyInstallFlags(),
                searchResults = current.searchResults?.applyInstallFlags(),
            )
        }
    }

    /**
     * RPC nộp báo cáo chạy nền ở [ReportSubmitManager]. Khi server đã commit, đánh
     * dấu campaign là "Đã test hôm nay" ngay (optimistic) rồi force-refresh Trang
     * chủ để lấy số dư Credit + stats thật. Nếu Home đang ở back stack, sự kiện
     * vẫn được nhận và trạng thái sẵn sàng trước khi người dùng quay lại.
     */
    private fun observeSubmittedReports() {
        val manager = reportSubmitManager ?: return
        viewModelScope.launch {
            manager.events.collect { event ->
                if (event is ReportSubmitManager.SubmitEvent.Success &&
                    event.campaignId.isNotBlank() &&
                    repository.isAuthenticated()
                ) {
                    if (!event.isMissionReport) {
                        markCampaignTestedToday(event.campaignId)
                        triggerDailyTaskReloadAfterReport()
                    }
                    refresh(forceRefresh = true, triggerSource = EgressTriggerSource.MUTATION)
                }
            }
        }
    }

    fun triggerDailyTaskReloadAfterReport() {
        if (repository.currentUserId() == null) return
        repository.invalidateDailyTaskDashboardCache()
        repository.bumpMutationVersion("daily_task")
        retryDailyTask()
    }

    private fun observeNotificationEvents() {
        viewModelScope.launch {
            repository.notificationEvents.collect { event ->
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (event.ownerId != currentUid || event.generation != currentGen) {
                    return@collect
                }
                _uiState.update { state ->
                    when (event) {
                        is NotificationEvent.MarkedRead -> {
                            val updated = state.notifications.map {
                                if (it.id == event.id) it.copy(isRead = true) else it
                            }
                            state.copy(
                                notifications = updated,
                                unreadNotificationsCount = updated.count { !it.isRead },
                            )
                        }
                        is NotificationEvent.MarkedAllRead -> {
                            val updated = state.notifications.map { it.copy(isRead = true) }
                            state.copy(
                                notifications = updated,
                                unreadNotificationsCount = 0,
                            )
                        }
                        is NotificationEvent.Deleted -> {
                            val updated = state.notifications.filterNot { it.id == event.id }
                            state.copy(
                                notifications = updated,
                                unreadNotificationsCount = updated.count { !it.isRead },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeDailyTaskEvents() {
        viewModelScope.launch {
            repository.dailyTaskEvents.collect { event ->
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                val currentDay = repository.businessDay().toString()
                val currentMutVer = repository.getMutationVersion("daily_task")
                when (event) {
                    is DailyTaskChangeEvent.Updated -> {
                        if (event.ownerId == currentUid &&
                            event.generation == currentGen &&
                            event.businessDate == currentDay &&
                            event.mutationVersion == currentMutVer
                        ) {
                            val owner = DailyTaskRequestOwner(event.ownerId, event.generation, event.businessDate, event.mutationVersion)
                            if (publishDailyTaskSnapshot(owner, dailyTaskRequestSequence.incrementAndGet(), event.status)) {
                                stopDailyTaskRetry()
                            }
                        }
                    }
                    is DailyTaskChangeEvent.StalePending -> {
                        if (event.ownerId == currentUid &&
                            event.generation == currentGen &&
                            event.businessDate == currentDay &&
                            event.mutationVersion == currentMutVer
                        ) {
                            repository.invalidateDailyTaskDashboardCache()
                            _uiState.update { it.copy(isDailyTaskPendingRefresh = true, isDailyTaskError = false) }
                            if (event.triggerSource != "stale_pending_retry") {
                                scheduleDailyTaskRetry(
                                    targetUid = event.ownerId,
                                    targetGen = event.generation,
                                    targetDay = event.businessDate,
                                    targetMutation = event.mutationVersion
                                )
                            }
                            checkAndSchedulePendingCarryoverRetry(_uiState.value.dailyTask)
                        }
                    }
                }
            }
        }
    }

    /** Cập nhật ngay chip "Đã test hôm nay" trong lúc chờ request làm mới feed. */
    private fun markCampaignTestedToday(campaignId: String) {
        fun List<CampaignDisplayItem>.markTested(): List<CampaignDisplayItem> = map { item ->
            if (item.campaign.id == campaignId) item.copy(hasTestedToday = true) else item
        }
        _uiState.update { state ->
            state.copy(
                displayItems = state.displayItems.markTested(),
                pausedDisplayItems = state.pausedDisplayItems.markTested(),
                completedDisplayItems = state.completedDisplayItems.markTested(),
                searchResults = state.searchResults?.markTested(),
                joinedStatsMap = state.joinedStatsMap[campaignId]?.let { stats ->
                    state.joinedStatsMap + (campaignId to stats.copy(hasTestedToday = true))
                } ?: state.joinedStatsMap,
            )
        }
    }

    /**
     * Session khôi phục từ đĩa bất đồng bộ: nếu refresh() chạy lúc còn `Initializing`
     * (isAuthenticated=false) thì KHÔNG được xoá dữ liệu cá nhân đang hiển thị;
     * khi session sẵn sàng, collector dưới đây sẽ tự refresh một lần.
     */
    private fun observeSession() {
        viewModelScope.launch {
            repository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val uid = repository.currentUserId()
                        if (uid != null && uid != lastSyncedUid) {
                            stopPendingCarryoverRetry()
                            stopDailyTaskRetry()
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        } else if (uid != null && refreshWhenSessionReady) {
                            // Phiên khôi phục xong (ẩn app rồi mở lại, mạng có lại):
                            // refresh đúng một lần dù uid không đổi.
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        stopPendingCarryoverRetry()
                        stopDailyTaskRetry()
                        refreshWhenSessionReady = false
                        val ctx = context
                        val hadSnapshot = ctx != null && SessionCacheManager.hasCachedSession(ctx)
                        if (lastSyncedUid != null || hadSnapshot) {
                            lastSyncedUid = null
                            if (ctx != null) SessionCacheManager.clear(ctx)
                            refresh(forceRefresh = false)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** Tải bảng xếp hạng khi người dùng mở tab (lazy) — không chặn tải Trang chủ. */
    fun ensureLeaderboardLoaded() {
        if (leaderboardLoaded) return
        leaderboardLoaded = true
        if (_uiState.value.leaderboard.isEmpty()) refreshLeaderboard()
    }

    /** Tải bảng Top Test khi người dùng mở sub-tab (lazy). */
    fun ensureTestLeaderboardLoaded() {
        if (testLeaderboardLoaded) return
        testLeaderboardLoaded = true
        if (_uiState.value.testLeaderboard.isEmpty()) refreshTestLeaderboard()
    }

    /**
     * Ghi nhận đăng nhập đúng một lần mỗi ngày VN. Dashboard cũng ghi nhận
     * server-side, nhưng lời gọi này bảo đảm hoạt động cả khi dashboard lỗi/cũ.
     */
    private fun ensureDailyLoginRecorded(appContext: android.content.Context?) {
        if (appContext == null) return
        val uid = repository.currentUserId() ?: return
        val prefs = appContext.getSharedPreferences("testcheo_sync", android.content.Context.MODE_PRIVATE)
        val key = "daily_login_recorded_date_$uid"
        val today = vnToday()
        if (prefs.getString(key, null) == today) return
        if (dailyLoginJob?.isActive == true) return
        dailyLoginJob = viewModelScope.launch {
            try {
                if (repository.recordDailyLogin()) {
                    prefs.edit().putString(key, today).apply()
                }
            } catch (_: Exception) {
                // Server cũ chưa có RPC — dashboard vẫn tự ghi nhận.
            }
        }
    }

    /** Đồng bộ vòng đời campaign tối đa 1 lần/giờ (pg_cron, nếu được bật, đã lo phần còn lại). */
    private fun maybeSyncCampaignLifecycle(appContext: android.content.Context?) {
        val now = System.currentTimeMillis()
        val prefs = appContext?.getSharedPreferences("testcheo_sync", android.content.Context.MODE_PRIVATE)
        if (prefs != null && now - prefs.getLong("lifecycle_sync_ms", 0L) < LIFECYCLE_SYNC_THROTTLE_MS) return
        prefs?.edit()?.putLong("lifecycle_sync_ms", now)?.apply()
        viewModelScope.launch { repository.syncCampaignLifecycle() }
    }

    internal companion object {
        const val LIFECYCLE_SYNC_THROTTLE_MS = 60 * 60 * 1000L
        val PENDING_CARRYOVER_BACKOFF_MS = longArrayOf(
            5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L,
            60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L
        )
    }

    fun refresh(
        context: android.content.Context? = null,
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ) {
        val appContext = context ?: this.context
        val seq = refreshSequence.incrementAndGet()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        val initialDay = repository.businessDay()
        val initialDailyTaskVersion = SessionCacheManager.currentDailyTaskVersion()
        viewModelScope.launch {
            val isOnline = appContext?.let { NetworkMonitor.isOnline(it) } ?: true
            _uiState.update { it.copy(isOffline = !isOnline) }

            val currentProfileId = _uiState.value.userProfile?.id
            val isAccountSwitch = (renderedUserId != null && renderedUserId != initialUid) ||
                (lastSyncedUid != null && lastSyncedUid != initialUid) ||
                (currentProfileId != null && currentProfileId != initialUid)
            val currentDayStr = repository.businessDay().toString()
            if (isAccountSwitch) {
                renderedUserId = initialUid
                lastSyncedUid = null
                stopDailyTaskRetry()
                stopPendingCarryoverRetry()
                _uiState.update {
                    val clearedOpen = it.displayItems.map { d -> d.copy(hasTestedToday = false) }
                    val clearedPaused = it.pausedDisplayItems.map { d -> d.copy(hasTestedToday = false) }
                    val clearedCompleted = it.completedDisplayItems.map { d -> d.copy(hasTestedToday = false) }
                    val sortedOpen = DailyHomeOrder.sortOpenCampaigns(clearedOpen, currentDayStr)
                    val sortedPaused = DailyHomeOrder.sortOtherCampaigns(clearedPaused, currentDayStr)
                    val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(clearedCompleted, currentDayStr)
                    it.copy(
                        userProfile = null,
                        userCredits = 0,
                        myCampaigns = emptyList(),
                        joinedCampaigns = emptyList(),
                        notifications = emptyList(),
                        unreadNotificationsCount = 0,
                        unreadWarning = null,
                        weeklyWarningsCount = 0,
                        pendingReportsCount = 0,
                        todayCheckinCount = 0,
                        questSummary = null,
                        dailyTask = null,
                        isDailyTaskPendingRefresh = false,
                        isDailyTaskError = false,
                        creditLogs = emptyList(),
                        openCampaigns = sortedOpen.map { d -> d.campaign },
                        displayItems = sortedOpen,
                        pausedDisplayItems = sortedPaused,
                        completedDisplayItems = sortedCompleted,
                        searchResults = it.searchResults?.map { d -> d.copy(hasTestedToday = false) },
                        ownerStatsMap = emptyMap(),
                        joinedStatsMap = emptyMap(),
                        homeOrderDate = currentDayStr,
                    )
                }
            }

            // Kiểm tra đổi ngày trước khi xét offline.
            // Nếu ngày đã sang ngày mới, làm sạch dữ liệu cũ theo quy tắc hiện có
            // rồi mới sắp xếp theo ngày mới, không chờ mạng và không đoán số thống kê mới.
            val stateOrderDate = _uiState.value.homeOrderDate
            val isCalendarDayChanged = stateOrderDate != null && stateOrderDate != currentDayStr
            if (isCalendarDayChanged) {
                stopDailyTaskRetry()
                stopPendingCarryoverRetry()
                _uiState.update { state ->
                    val sanitizedOpen = state.displayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                    val sanitizedPaused = state.pausedDisplayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                    val sanitizedCompleted = state.completedDisplayItems.map { it.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                    val sortedOpen = DailyHomeOrder.sortOpenCampaigns(sanitizedOpen, currentDayStr)
                    val sortedPaused = DailyHomeOrder.sortOtherCampaigns(sanitizedPaused, currentDayStr)
                    val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(sanitizedCompleted, currentDayStr)
                    val sanitizedJoined = state.joinedStatsMap.mapValues { (_, s) ->
                        s.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null)
                    }
                    val sanitizedSearch = state.searchResults?.map { d ->
                        d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null)
                    }
                    state.copy(
                        openCampaigns = sortedOpen.map { d -> d.campaign },
                        displayItems = sortedOpen,
                        pausedDisplayItems = sortedPaused,
                        completedDisplayItems = sortedCompleted,
                        searchResults = sanitizedSearch,
                        todayCheckinCount = 0,
                        dailyTask = null,
                        isDailyTaskPendingRefresh = false,
                        isDailyTaskError = false,
                        questSummary = state.questSummary?.copy(dailyCount = 0),
                        ownerStatsMap = state.ownerStatsMap.mapValues { (_, s) -> s.copy(testedTodayCount = 0) },
                        joinedStatsMap = sanitizedJoined,
                        homeOrderDate = currentDayStr,
                    )
                }
                if (_uiState.value.query.isNotBlank()) {
                    runSearch(_uiState.value.query)
                }
            } else if (stateOrderDate == null) {
                _uiState.update { state ->
                    val sortedOpen = DailyHomeOrder.sortOpenCampaigns(state.displayItems, currentDayStr)
                    val sortedPaused = DailyHomeOrder.sortOtherCampaigns(state.pausedDisplayItems, currentDayStr)
                    val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(state.completedDisplayItems, currentDayStr)
                    state.copy(
                        openCampaigns = sortedOpen.map { d -> d.campaign },
                        displayItems = sortedOpen,
                        pausedDisplayItems = sortedPaused,
                        completedDisplayItems = sortedCompleted,
                        homeOrderDate = currentDayStr,
                    )
                }
            }

            val hasContent = _uiState.value.displayItems.isNotEmpty() ||
                _uiState.value.pausedDisplayItems.isNotEmpty() ||
                _uiState.value.completedDisplayItems.isNotEmpty()

            if (!isOnline && hasContent && !forceRefresh) {
                _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false) }
                return@launch
            }

            // Kéo làm mới giữ nguyên dữ liệu đang hiển thị; chỉ hiện vòng xoay toàn màn hình khi chưa có gì.
            if (!hasContent) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                val isAuthed = repository.isAuthenticated()

                // Session đang khôi phục từ đĩa (auth Initializing): giữ nguyên snapshot,
                // KHÔNG ghi đè bằng dữ liệu rỗng. Collector sessionStatus sẽ tự refresh sau.
                if (!isAuthed && appContext != null && SessionCacheManager.hasCachedSession(appContext)) {
                    refreshWhenSessionReady = true
                    _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false) }
                    return@launch
                }

                // Đồng bộ vòng đời: throttle 1 lần/giờ, chạy nền để không chặn tải Trang chủ.
                if (isAuthed) maybeSyncCampaignLifecycle(appContext)
                if (isAuthed) ensureDailyLoginRecorded(appContext)

                // Fast path: 2 RPC gộp chạy SONG SONG (get_home_feed + get_my_dashboard) thay cho ~25 round-trip.
                val dashForceRefresh = forceRefresh || _uiState.value.isDailyTaskPendingRefresh
                val (feed, dashboard) = if (isAuthed) {
                    coroutineScope {
                        val feedDeferred = async { repository.getHomeFeed(forceRefresh = forceRefresh, triggerSource = triggerSource) }
                        val dashDeferred = async { repository.getMyDashboard(forceRefresh = dashForceRefresh, triggerSource = triggerSource) }
                        Pair(feedDeferred.await(), dashDeferred.await())
                    }
                } else {
                    Pair(null, null)
                }
                if (isAuthed) lastSyncedUid = repository.currentUserId()

                // Nhiệm vụ App: tải nền độc lập với sequence và error handling riêng (M08, M09)
                if (isAuthed) {
                    refreshMissions(forceRefresh = forceRefresh)
                } else {
                    _uiState.update { it.copy(missions = emptyList(), isMissionsLoading = false, missionsError = null) }
                }

                val open: List<Campaign>
                val paused: List<Campaign>
                val completed: List<Campaign>
                val ownerNameById: Map<String, String>
                val statsMap: Map<String, CampaignStats>

                if (feed != null) {
                    open = feed.open
                    paused = feed.paused
                    completed = feed.completed
                    ownerNameById = feed.ownerNames
                    statsMap = feed.stats
                } else {
                    val lists = coroutineScope {
                        val openDeferred = async { repository.listOpenCampaigns(forceRefresh = forceRefresh) }
                        val pausedDeferred = if (isAuthed) async { repository.listPausedCampaigns(forceRefresh = forceRefresh) } else null
                        val completedDeferred = if (isAuthed) async { repository.listCompletedCampaigns(forceRefresh = forceRefresh) } else null
                        Triple(
                            openDeferred.await(),
                            pausedDeferred?.await() ?: emptyList(),
                            completedDeferred?.await() ?: emptyList(),
                        )
                    }
                    open = lists.first
                    paused = lists.second
                    completed = lists.third
                    val allCampaigns = open + paused + completed
                    val derived = coroutineScope {
                        val statsDeferred = async {
                            repository.getCampaignsStatsMap(allCampaigns.mapNotNull { it.id }, forceRefresh = forceRefresh)
                        }
                        val ownerDeferred = async {
                            repository.getProfiles(allCampaigns.mapNotNull { it.ownerId }.distinct(), forceRefresh = forceRefresh)
                        }
                        Pair(statsDeferred.await(), ownerDeferred.await())
                    }
                    statsMap = derived.first
                    ownerNameById = derived.second.mapValues { (_, profile) ->
                        profile.displayName?.ifBlank { null } ?: profile.email?.substringBefore("@") ?: ""
                    }
                }

                // Trạng thái cài đặt trên máy của người test (tính trên IO, tránh chặn main thread).
                val installFlags = if (appContext != null) withContext(Dispatchers.IO) {
                    InstalledAppCache.ensureFresh(appContext)
                    (open + paused + completed).distinctBy { it.packageName }
                        .associate { it.packageName to InstalledAppCache.isInstalled(appContext, it.packageName) }
                } else emptyMap()
                val installCacheVersion = InstalledAppCache.version.get()

                fun toDisplayItem(c: Campaign): CampaignDisplayItem {
                    val stats = statsMap[c.id] ?: CampaignStats()
                    val name = ownerNameById[c.ownerId].orEmpty().ifBlank { localizer.get(R.string.campaign_owner_fallback) }
                    return CampaignDisplayItem(
                        campaign = c,
                        ownerName = name,
                        installedCount = stats.installedCount,
                        todayTestedCount = stats.todayTestedCount,
                        hasTestedToday = stats.hasTestedToday,
                        isInstalledLocally = installFlags[c.packageName] ?: false,
                        homeTestedCount = stats.homeTestedCount ?: stats.todayTestedCount,
                        statsDate = stats.statsDate ?: initialDay.toString(),
                    )
                }

                val displayList = open.map { toDisplayItem(it) }
                val pausedList = paused.map { toDisplayItem(it) }
                val completedList = completed.map { toDisplayItem(it) }

                var logsFailed = false
                val personal = if (dashboard != null) {
                    PersonalData(
                        mine = dashboard.myCampaigns,
                        joined = dashboard.joinedCampaigns,
                        leaderboard = dashboard.leaderboard,
                        todayCheckins = dashboard.todayCheckinCount,
                        myProfile = dashboard.profile,
                        dailyTask = verifiedDailyTask(dashboard.dailyTask, initialDay.toString()),
                        pendingReports = dashboard.pendingReportsCount,
                        unreadWarnings = dashboard.unreadWarnings,
                        weeklyWarnings = dashboard.weeklyWarningsCount,
                        logs = dashboard.creditLogs,
                        packages = dashboard.topupPackages.ifEmpty { com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES },
                        notifications = dashboard.notifications,
                        quests = dashboard.questSummary ?: repository.getQuestSummary(forceRefresh = forceRefresh),
                    )
                } else if (isAuthed) {
                    coroutineScope {
                        val mineDeferred = async { repository.myCampaigns(forceRefresh = forceRefresh) }
                        val joinedDeferred = async { repository.myJoinedCampaigns(forceRefresh = forceRefresh) }
                        val leaderboardDeferred = async { repository.getLeaderboard(forceRefresh = forceRefresh) }
                        val myRankDeferred = async { repository.getMyRank(forceRefresh = forceRefresh) }
                        val todayDeferred = async { repository.getTodayCheckinCount(forceRefresh = forceRefresh) }
                        val profileDeferred = async { repository.getMyProfile(forceRefresh = forceRefresh) }
                        val pendingDeferred = async { repository.getPendingReportsCountForOwner(forceRefresh = forceRefresh) }
                        val warningsDeferred = async { repository.getUnreadWarnings(forceRefresh = forceRefresh) }
                        val weeklyDeferred = async { repository.getWeeklyWarningsCount(forceRefresh = forceRefresh) }
                        val logsDeferred = async { repository.getMyCreditLogs(forceRefresh = forceRefresh) }
                        val packagesDeferred = async { repository.getTopupPackages(forceRefresh = forceRefresh) }
                        val notificationsDeferred = async { repository.getMyNotifications(forceRefresh = forceRefresh) }
                        val questsDeferred = async { repository.getQuestSummary(forceRefresh = forceRefresh) }
                        val dailyDeferred = async { repository.getMyDailyTaskStatus() }
                        PersonalData(
                            mine = mineDeferred.await(),
                            joined = joinedDeferred.await(),
                            leaderboard = leaderboardDeferred.await(),
                            myRank = try {
                                myRankDeferred.await()
                            } catch (_: Exception) {
                                null
                            },
                            todayCheckins = todayDeferred.await(),
                            myProfile = profileDeferred.await(),
                            dailyTask = verifiedDailyTask(dailyDeferred.await(), initialDay.toString()),
                            pendingReports = pendingDeferred.await(),
                            unreadWarnings = warningsDeferred.await(),
                            weeklyWarnings = weeklyDeferred.await(),
                            logs = try {
                                logsDeferred.await()
                            } catch (_: Exception) {
                                logsFailed = true
                                _uiState.value.creditLogs
                            },
                            packages = packagesDeferred.await(),
                            notifications = notificationsDeferred.await(),
                            quests = questsDeferred.await(),
                        )
                    }
                } else {
                    PersonalData()
                }

                val ownerStats = if (isAuthed && personal.mine.isNotEmpty()) {
                    if (dashboard != null) {
                        // Dashboard RPC (migration 020) đã gộp owner_stats — không N+1 theo từng app.
                        dashboard.ownerStats
                    } else {
                        coroutineScope {
                            personal.mine.mapNotNull { it.id }
                                .map { id -> async { id to repository.getCampaignOwnerStats(id, forceRefresh = forceRefresh) } }
                                .map { it.await() }
                                .toMap()
                        }
                    }
                } else emptyMap()

                // Stats cho danh sách "App đã tham gia": tận dụng stats của feed trước,
                // chỉ gọi thêm cho các campaign chưa có (ví dụ campaign đã lên production).
                val joinedStats = if (isAuthed && personal.joined.isNotEmpty()) {
                    val joinedIds = personal.joined.mapNotNull { it.id }
                    val fromFeed = joinedIds.mapNotNull { id -> statsMap[id]?.let { id to it } }.toMap()
                    val missing = joinedIds.filter { it !in statsMap }
                    if (missing.isEmpty()) {
                        fromFeed
                    } else {
                        fromFeed + try {
                            repository.getCampaignsStatsMap(missing, forceRefresh = forceRefresh)
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    }
                } else emptyMap()

                val notifs = personal.notifications
                val unreadNotifsCount = notifs.count { !it.isRead }
                val myProfile = personal.myProfile

                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }

                val currentBusinessDay = repository.businessDay().toString()
                val hasStaleStatsDate = statsMap.values.any { it.statsDate != null && it.statsDate != currentBusinessDay }
                val isDayRollover = repository.businessDay() != initialDay
                fun sanitizeItemIfStale(d: CampaignDisplayItem): CampaignDisplayItem {
                    val isItemStale = isDayRollover || (d.statsDate != null && d.statsDate != currentBusinessDay)
                    return if (isItemStale) {
                        d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null)
                    } else {
                        d
                    }
                }
                val sanitizedDisplayList = displayList.map { sanitizeItemIfStale(it) }
                val sanitizedPausedList = pausedList.map { sanitizeItemIfStale(it) }
                val sanitizedCompletedList = completedList.map { sanitizeItemIfStale(it) }
                val sanitizedJoinedStats = joinedStats.mapValues { (_, s) ->
                    val isStatStale = isDayRollover || (s.statsDate != null && s.statsDate != currentBusinessDay)
                    if (isStatStale) {
                        s.copy(homeTestedCount = null, statsDate = null, hasTestedToday = false, todayTestedCount = 0)
                    } else {
                        s
                    }
                }
                val finalDisplayList = DailyHomeOrder.sortOpenCampaigns(sanitizedDisplayList, currentBusinessDay)
                val finalPausedList = DailyHomeOrder.sortOtherCampaigns(sanitizedPausedList, currentBusinessDay)
                val finalCompletedList = DailyHomeOrder.sortOtherCampaigns(sanitizedCompletedList, currentBusinessDay)
                val isDailyTaskStale = SessionCacheManager.currentDailyTaskVersion() != initialDailyTaskVersion
                val finalTodayCheckins = if (isDayRollover) 0 else personal.todayCheckins
                val finalDailyTask = if (isDayRollover) null else if (isDailyTaskStale) _uiState.value.dailyTask else (personal.dailyTask ?: _uiState.value.dailyTask)
                val finalMissedStreak = if (isDailyTaskStale) (_uiState.value.dailyTask?.missedStreak ?: _uiState.value.missedStreak) else (finalDailyTask?.missedStreak ?: myProfile?.effectiveMissedStreak ?: 0)
                val finalIsPending = if (isDayRollover) false else if (isDailyTaskStale || personal.dailyTask == null) _uiState.value.isDailyTaskPendingRefresh else false
                val finalIsError = if (isDailyTaskStale) _uiState.value.isDailyTaskError else isAuthed && personal.dailyTask == null
                val finalQuestSummary = if (isDayRollover) personal.quests?.copy(dailyCount = 0) else personal.quests
                val finalOwnerStats = if (isDayRollover) ownerStats.mapValues { (_, s) -> s.copy(testedTodayCount = 0) } else ownerStats

                if (!isDayRollover && !isDailyTaskStale && personal.dailyTask != null) {
                    stopDailyTaskRetry()
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        isOffline = false,
                        isAuthenticated = isAuthed,
                        openCampaigns = finalDisplayList.map { d -> d.campaign },
                        displayItems = finalDisplayList,
                        pausedDisplayItems = finalPausedList,
                        completedDisplayItems = finalCompletedList,
                        homeOrderDate = currentBusinessDay,
                        myCampaigns = personal.mine,
                        joinedCampaigns = personal.joined,
                        // Dashboard không còn trả leaderboard (tải lazy theo tab) — giữ bảng đang hiển thị.
                        leaderboard = personal.leaderboard.ifEmpty { it.leaderboard },
                        myRank = personal.myRank ?: it.myRank,
                        todayCheckinCount = finalTodayCheckins,
                        userCredits = myProfile?.credits ?: it.userCredits,
                        missedStreak = finalMissedStreak,
                        dailyTask = finalDailyTask,
                        isDailyTaskPendingRefresh = finalIsPending,
                        isDailyTaskError = finalIsError,
                        userProfile = myProfile,
                        pendingReportsCount = personal.pendingReports,
                        ownerStatsMap = finalOwnerStats,
                        joinedStatsMap = sanitizedJoinedStats,
                        ownerNameById = ownerNameById,
                        unreadWarning = personal.unreadWarnings.firstOrNull(),
                        weeklyWarningsCount = personal.weeklyWarnings,
                        creditLogs = personal.logs,
                        creditLogsLoadFailed = logsFailed,
                        topupPackages = personal.packages,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = finalQuestSummary,
                        error = null,
                    )
                }
                if (isDayRollover) {
                    stopDailyTaskRetry()
                    stopPendingCarryoverRetry()
                } else {
                    if (isAuthed && finalIsPending && dailyTaskRetryJob?.isActive != true) {
                        currentDailyTaskOwner()?.let { owner ->
                            scheduleDailyTaskRetry(owner.uid, owner.generation, owner.day, owner.mutation, immediate = false)
                        }
                    }
                    checkAndSchedulePendingCarryoverRetry(finalDailyTask)
                }
                renderedUserId = initialUid
                lastSyncedUid = initialUid
                if (appContext != null && InstalledAppCache.version.get() != installCacheVersion) {
                    recomputeInstalledFlags(appContext)
                }

                // Persist fresh session snapshot to disk
                if (appContext != null) {
                    val snapshot = CachedSessionData(
                        userId = initialUid,
                        userProfile = myProfile,
                        displayItems = finalDisplayList,
                        pausedDisplayItems = finalPausedList,
                        completedDisplayItems = finalCompletedList,
                        myCampaigns = personal.mine,
                        joinedCampaigns = personal.joined,
                        leaderboard = personal.leaderboard,
                        myRank = personal.myRank ?: _uiState.value.myRank,
                        ownerStatsMap = finalOwnerStats,
                        userCredits = myProfile?.credits ?: _uiState.value.userCredits,
                        todayCheckinCount = finalTodayCheckins,
                        missedStreak = finalMissedStreak,
                        dailyTask = finalDailyTask,
                        isDailyTaskPendingRefresh = finalIsPending,
                        dailyTaskMutationVersion = if (isDailyTaskStale) SessionCacheManager.currentDailyTaskVersion() else initialDailyTaskVersion,
                        pendingReportsCount = personal.pendingReports,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = finalQuestSummary,
                        timestamp = System.currentTimeMillis(),
                        dateVn = currentBusinessDay,
                        sessionGeneration = initialGen,
                    )
                    SessionCacheManager.save(appContext, snapshot, targetGeneration = initialGen)
                }

                // Auto-sync missing icon for owner's campaigns if app is installed on this device
                if (appContext != null && isAuthed && personal.mine.isNotEmpty()) {
                    for (c in personal.mine) {
                        if (c.safeIconUrl.isBlank() && c.id != null) {
                            val drawable = com.ntt.crosstest.util.PlayStoreHelper.getInstalledAppDrawable(appContext, c.packageName)
                            if (drawable != null) {
                                viewModelScope.launch {
                                    try {
                                        val bitmap = com.ntt.crosstest.util.PlayStoreHelper.drawableToBitmap(drawable)
                                        val firebaseUrl = com.ntt.crosstest.util.FirebaseStorageHelper.uploadAppIconBitmap(bitmap, c.packageName)
                                        repository.updateCampaignIcon(c.id, firebaseUrl)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }

                // Re-run the current search against fresh data.
                if (_uiState.value.query.isNotBlank()) runSearch(_uiState.value.query)

                if (hasStaleStatsDate) {
                    if (staleFeedRetryCount.incrementAndGet() <= 2) {
                        viewModelScope.launch {
                            refresh(appContext, forceRefresh = true)
                        }
                    }
                } else {
                    staleFeedRetryCount.set(0)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }
                val isAccountSwitch = (renderedUserId != null && renderedUserId != initialUid) ||
                    (lastSyncedUid != null && lastSyncedUid != initialUid) ||
                    (_uiState.value.userProfile?.id != null && _uiState.value.userProfile?.id != initialUid)
                if (isAccountSwitch) {
                    renderedUserId = initialUid
                    lastSyncedUid = null
                }
                val currentBusinessDay = repository.businessDay().toString()
                val isDayRollover = repository.businessDay() != initialDay ||
                    (_uiState.value.homeOrderDate != null && _uiState.value.homeOrderDate != currentBusinessDay)
                _uiState.update {
                    if (isAccountSwitch) {
                        val clearedDisplay = it.displayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                        val clearedPaused = it.pausedDisplayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                        val clearedCompleted = it.completedDisplayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) }
                        val sortedDisplay = DailyHomeOrder.sortOpenCampaigns(clearedDisplay, currentBusinessDay)
                        val sortedPaused = DailyHomeOrder.sortOtherCampaigns(clearedPaused, currentBusinessDay)
                        val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(clearedCompleted, currentBusinessDay)
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            userProfile = null,
                            userCredits = 0,
                            myCampaigns = emptyList(),
                            joinedCampaigns = emptyList(),
                            notifications = emptyList(),
                            unreadNotificationsCount = 0,
                            unreadWarning = null,
                            weeklyWarningsCount = 0,
                            pendingReportsCount = 0,
                            todayCheckinCount = 0,
                            questSummary = null,
                            dailyTask = null,
                            isDailyTaskPendingRefresh = false,
                            isDailyTaskError = false,
                            creditLogs = emptyList(),
                            openCampaigns = sortedDisplay.map { d -> d.campaign },
                            displayItems = sortedDisplay,
                            pausedDisplayItems = sortedPaused,
                            completedDisplayItems = sortedCompleted,
                            searchResults = it.searchResults?.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) },
                            ownerStatsMap = emptyMap(),
                            joinedStatsMap = emptyMap(),
                            homeOrderDate = currentBusinessDay,
                            error = if (it.displayItems.isEmpty() && it.pausedDisplayItems.isEmpty() && it.completedDisplayItems.isEmpty()) friendlyError(e, localizer) else null,
                        )
                    } else {
                        val baseDisplayItems = if (isDayRollover) it.displayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) } else it.displayItems
                        val basePausedItems = if (isDayRollover) it.pausedDisplayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) } else it.pausedDisplayItems
                        val baseCompletedItems = if (isDayRollover) it.completedDisplayItems.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) } else it.completedDisplayItems
                        val sortedDisplay = DailyHomeOrder.sortOpenCampaigns(baseDisplayItems, currentBusinessDay)
                        val sortedPaused = DailyHomeOrder.sortOtherCampaigns(basePausedItems, currentBusinessDay)
                        val sortedCompleted = DailyHomeOrder.sortOtherCampaigns(baseCompletedItems, currentBusinessDay)
                        val baseSearchResults = if (isDayRollover) it.searchResults?.map { d -> d.copy(hasTestedToday = false, todayTestedCount = 0, homeTestedCount = null, statsDate = null) } else it.searchResults
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            openCampaigns = sortedDisplay.map { d -> d.campaign },
                            displayItems = sortedDisplay,
                            pausedDisplayItems = sortedPaused,
                            completedDisplayItems = sortedCompleted,
                            searchResults = baseSearchResults,
                            homeOrderDate = currentBusinessDay,
                            todayCheckinCount = if (isDayRollover) 0 else it.todayCheckinCount,
                            dailyTask = if (isDayRollover) null else it.dailyTask,
                            isDailyTaskPendingRefresh = if (isDayRollover) false else it.isDailyTaskPendingRefresh,
                            isDailyTaskError = if (isDayRollover) false else it.isDailyTaskError,
                            questSummary = if (isDayRollover) it.questSummary?.copy(dailyCount = 0) else it.questSummary,
                            ownerStatsMap = if (isDayRollover) it.ownerStatsMap.mapValues { (_, s) -> s.copy(testedTodayCount = 0) } else it.ownerStatsMap,
                            joinedStatsMap = if (isDayRollover) it.joinedStatsMap.mapValues { (_, s) -> s.copy(homeTestedCount = null, statsDate = null, hasTestedToday = false, todayTestedCount = 0) } else it.joinedStatsMap,
                            userProfile = if (it.userProfile?.id != initialUid) null else it.userProfile,
                            notifications = if (initialUid == null) emptyList() else it.notifications.filter { n -> n.userId == initialUid },
                            error = if (it.displayItems.isEmpty() && it.pausedDisplayItems.isEmpty() && it.completedDisplayItems.isEmpty()) friendlyError(e, localizer) else null
                        )
                    }
                }
                if (isAccountSwitch || isDayRollover) {
                    stopDailyTaskRetry()
                    stopPendingCarryoverRetry()
                } else if (repository.isAuthenticated()) {
                    if (_uiState.value.isDailyTaskPendingRefresh && dailyTaskRetryJob?.isActive != true) {
                        currentDailyTaskOwner()?.let { owner ->
                            scheduleDailyTaskRetry(owner.uid, owner.generation, owner.day, owner.mutation, immediate = false)
                        }
                    }
                    checkAndSchedulePendingCarryoverRetry(_uiState.value.dailyTask)
                }
                if (_uiState.value.query.isNotBlank()) {
                    runSearch(_uiState.value.query)
                }
            }
        }
    }

    fun clearCacheAndReload(context: android.content.Context? = null) {
        val appContext = context ?: this.context
        repository.clearMemoryCache()
        com.ntt.crosstest.util.InstalledAppCache.clear()
        if (appContext != null) {
            SessionCacheManager.clear(appContext)
            com.ntt.crosstest.util.InstalledAppCache.init(appContext)
            try {
                coil.Coil.imageLoader(appContext).memoryCache?.clear()
            } catch (_: Exception) {}
        }
        refresh(context = appContext, forceRefresh = true)
    }

    fun refreshLeaderboard(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLeaderboardLoading = true) }
            try {
                coroutineScope {
                    val topDeferred = async { repository.getLeaderboard(sort = "credit", forceRefresh = forceRefresh) }
                    val myRankDeferred = async { repository.getMyRank(sort = "credit", forceRefresh = forceRefresh) }
                    val topUsers = topDeferred.await()
                    val myRank = try {
                        myRankDeferred.await()
                    } catch (_: Exception) {
                        null
                    }
                    _uiState.update {
                        it.copy(isLeaderboardLoading = false, leaderboard = topUsers, myRank = myRank)
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLeaderboardLoading = false) }
            }
        }
    }

    fun refreshTestLeaderboard(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestLeaderboardLoading = true) }
            try {
                coroutineScope {
                    val topDeferred = async { repository.getLeaderboard(sort = "test", forceRefresh = forceRefresh) }
                    val myRankDeferred = async { repository.getMyRank(sort = "test", forceRefresh = forceRefresh) }
                    val topUsers = topDeferred.await()
                    val myRank = try {
                        myRankDeferred.await()
                    } catch (_: Exception) {
                        null
                    }
                    _uiState.update {
                        it.copy(
                            isTestLeaderboardLoading = false,
                            testLeaderboard = topUsers,
                            testMyRank = myRank,
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isTestLeaderboardLoading = false) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // simple debounce
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        val q = query.trim()
        val state = _uiState.value
        val combined = state.displayItems + state.pausedDisplayItems + state.completedDisplayItems
        val filtered = combined.filter {
            it.campaign.appName.contains(q, ignoreCase = true) ||
            it.campaign.packageName.contains(q, ignoreCase = true) ||
            it.ownerName.contains(q, ignoreCase = true)
        }
        _uiState.update { it.copy(searchResults = filtered, error = null) }
    }

    fun dismissWarning(warningId: String) {
        val previous = _uiState.value.unreadWarning
        viewModelScope.launch {
            // Optimistic: ẩn ngay, gọi server nền, hoàn tác nếu thất bại.
            _uiState.update { it.copy(unreadWarning = null) }
            try {
                repository.acknowledgeWarning(warningId)
            } catch (_: Exception) {
                _uiState.update { it.copy(unreadWarning = previous) }
            }
        }
    }

    fun markNotificationAsRead(id: String) {
        val previous = _uiState.value.notifications
        _uiState.update { state ->
            val updated = state.notifications.map {
                if (it.id == id) it.copy(isRead = true) else it
            }
            state.copy(
                notifications = updated,
                unreadNotificationsCount = updated.count { !it.isRead },
            )
        }
        viewModelScope.launch {
            try {
                repository.markNotificationAsRead(id)
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        notifications = previous,
                        unreadNotificationsCount = previous.count { n -> !n.isRead },
                    )
                }
            }
        }
    }

    fun markAllNotificationsAsRead() {
        val previous = _uiState.value.notifications
        _uiState.update { state ->
            state.copy(
                notifications = state.notifications.map { it.copy(isRead = true) },
                unreadNotificationsCount = 0,
            )
        }
        viewModelScope.launch {
            try {
                repository.markAllNotificationsAsRead()
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        notifications = previous,
                        unreadNotificationsCount = previous.count { n -> !n.isRead },
                    )
                }
            }
        }
    }

    fun deleteNotification(id: String) {
        val previous = _uiState.value.notifications
        _uiState.update { state ->
            val updated = state.notifications.filterNot { it.id == id }
            state.copy(
                notifications = updated,
                unreadNotificationsCount = updated.count { !it.isRead },
            )
        }
        viewModelScope.launch {
            try {
                repository.deleteNotification(id)
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        notifications = previous,
                        unreadNotificationsCount = previous.count { n -> !n.isRead },
                    )
                }
            }
        }
    }

    fun processTopup(pkg: TopupPackage, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val res = repository.processTopupOrder(
                    packageId = pkg.id,
                    purchaseToken = "gp_token_${System.currentTimeMillis()}",
                    orderRef = "GPA-${System.currentTimeMillis()}",
                    paymentMethod = "google_play",
                )
                // RPC có thể trả kết quả thất bại (status != success) mà không ném
                // exception — khi đó không được cộng dương tính số dư hay hiện
                // overlay thành công. "already_processed" là outcome idempotent
                // (credit đã cộng từ lần gọi trước cùng token) — vẫn tính là thành công.
                val isGranted = res.status.equals("success", ignoreCase = true) ||
                    res.status.equals("already_processed", ignoreCase = true)
                if (!isGranted) {
                    onResult(false, localizer.get(R.string.home_daily_reward_failed))
                    return@launch
                }
                val amount = res.creditsAwarded.takeIf { it > 0 } ?: pkg.totalCredits
                val newBalance = res.currentBalance.takeIf { it > 0 }
                    ?: (_uiState.value.userCredits + amount)
                // Paint the new balance + receipt immediately; refresh() runs in
                // the background and keeps the reward card intact.
                _uiState.update {
                    it.copy(
                        userCredits = newBalance,
                        reward = CreditReward(
                            type = CreditRewardType.TOPUP,
                            amount = amount,
                            newBalance = newBalance,
                        ),
                    )
                }
                refresh()
                val msg = localizer.get(R.string.home_topup_success_format, amount)
                onResult(true, msg)
            } catch (e: Exception) {
                onResult(false, friendlyError(e, localizer))
            }
        }
    }

    fun claimQuestReward(questType: String, milestone: Int, onResult: (Boolean, String) -> Unit) {
        val key = "${questType}_${milestone}"
        if (_uiState.value.claimingMilestone != null) return
        _uiState.update { it.copy(claimingMilestone = key) }
        viewModelScope.launch {
            try {
                val res = repository.claimQuestReward(questType, milestone)
                if (res.success) {
                    val updatedSummary = repository.getQuestSummary(forceRefresh = true)
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)
                    val updatedLogs = repository.getMyCreditLogs()
                    val updatedNotifs = repository.getMyNotifications()
                    _uiState.update { state ->
                        state.copy(
                            claimingMilestone = null,
                            questSummary = updatedSummary,
                            userProfile = updatedProfile,
                            // RPC cũ có thể không trả new_balance (decode = 0):
                            // fallback về profile mới thay vì hiển thị số dư 0.
                            userCredits = res.newBalance.takeIf { it > 0 }
                                ?: updatedProfile?.credits
                                ?: state.userCredits,
                            creditLogs = updatedLogs,
                            notifications = updatedNotifs,
                            unreadNotificationsCount = updatedNotifs.count { !it.isRead },
                            reward = if (res.reward > 0) {
                                CreditReward(
                                    type = CreditRewardType.QUEST_CLAIMED,
                                    amount = res.reward,
                                    newBalance = res.newBalance.takeIf { it > 0 },
                                )
                            } else {
                                state.reward
                            },
                        )
                    }
                    onResult(true, localizer.get(R.string.home_daily_reward_success_format, res.reward))
                } else {
                    _uiState.update { it.copy(claimingMilestone = null) }
                    onResult(false, localizer.get(R.string.home_daily_reward_failed))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(claimingMilestone = null) }
                onResult(false, friendlyError(e, localizer))
            }
        }
    }

    /** Hide the current reward card (auto-dismiss or navigation). */
    fun clearReward() {
        _uiState.update { it.copy(reward = null) }
    }

    /**
     * Tải danh sách nhiệm vụ App độc lập với refresh Trang chủ (M08, M09).
     * Có sequence token, generation và UID guard để không bị response cũ ghi đè.
     */
    fun refreshMissions(forceRefresh: Boolean = false) {
        if (!repository.isAuthenticated()) {
            _uiState.update { it.copy(missions = emptyList(), isMissionsLoading = false, missionsError = null) }
            return
        }
        val seq = missionRefreshSequence.incrementAndGet()
        val initialMutation = missionMutationVersion.get()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        val initialDay = repository.businessDay()
        viewModelScope.launch {
            _uiState.update { it.copy(isMissionsLoading = true, missionsError = null) }
            try {
                val missions = repository.getOpenMissions(forceRefresh = forceRefresh)
                if (seq != missionRefreshSequence.get() ||
                    initialMutation != missionMutationVersion.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    _uiState.update { it.copy(isMissionsLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(missions = missions, isMissionsLoading = false, missionsError = null) }
                scheduleNextMissionDeadlineCheck()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (seq != missionRefreshSequence.get() ||
                    initialMutation != missionMutationVersion.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    _uiState.update { it.copy(isMissionsLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(isMissionsLoading = false, missionsError = friendlyError(e, localizer)) }
            }
        }
    }

    /**
     * Người dùng nhận nhiệm vụ trong tab "Nhiệm vụ App". Thành công: cập nhật
     * trạng thái mission local (joined, count, status) để UI phản ánh ngay.
     * Có guard chống click đúp và reload trạng thái có thẩm quyền khi có xung đột (M09, R07).
     */
    fun joinMission(missionId: String, onResult: (Boolean, String) -> Unit) {
        if (_uiState.value.joiningMissionId != null) return
        val existingMission = _uiState.value.missions.firstOrNull { it.id == missionId }
        if (existingMission?.isBlockedPreviousParticipant == true) {
            onResult(false, localizer.get(R.string.mission_err_previous_participant_blocked))
            return
        }
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        val initialDay = repository.businessDay()
        viewModelScope.launch {
            _uiState.update { it.copy(joiningMissionId = missionId) }
            try {
                val result = repository.joinMission(missionId)
                if (initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }
                if (result.success) {
                    missionMutationVersion.incrementAndGet()
                    missionRefreshSequence.incrementAndGet()
                    _uiState.update { state ->
                        state.copy(
                            joiningMissionId = null,
                            isMissionsLoading = false,
                            missions = state.missions.map { m ->
                                if (m.id == missionId) {
                                    m.copy(
                                        hasJoined = true,
                                        participantCount = m.participantCount + 1,
                                        status = result.status,
                                    )
                                } else {
                                    m
                                }
                            },
                        )
                    }
                    onResult(true, "")
                } else {
                    _uiState.update { it.copy(joiningMissionId = null) }
                    onResult(false, localizer.get(R.string.repo_connection_failed))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }
                _uiState.update { it.copy(joiningMissionId = null) }
                val errStr = e.message.orEmpty()
                if (errStr.contains("ALREADY_JOINED")) {
                    missionMutationVersion.incrementAndGet()
                    missionRefreshSequence.incrementAndGet()
                    _uiState.update { state ->
                        state.copy(
                            isMissionsLoading = false,
                            missions = state.missions.map { m ->
                                if (m.id == missionId) m.copy(hasJoined = true) else m
                            }
                        )
                    }
                    refreshMissions(forceRefresh = true)
                } else if (errStr.contains("MISSION_PREVIOUS_PARTICIPANT_BLOCKED")) {
                    missionMutationVersion.incrementAndGet()
                    missionRefreshSequence.incrementAndGet()
                    _uiState.update { state ->
                        state.copy(
                            isMissionsLoading = false,
                            missions = state.missions.map { m ->
                                if (m.id == missionId) m.copy(isBlockedPreviousParticipant = true) else m
                            }
                        )
                    }
                    refreshMissions(forceRefresh = true)
                } else if (errStr.contains("MISSION_FULL") || errStr.contains("MISSION_EXPIRED") || errStr.contains("MISSION_NOT_OPEN")) {
                    refreshMissions(forceRefresh = true)
                }
                val msg = when {
                    errStr.contains("MISSION_PREVIOUS_PARTICIPANT_BLOCKED") ->
                        localizer.get(R.string.mission_err_previous_participant_blocked)
                    errStr.contains("MISSION_FULL") ->
                        localizer.get(R.string.mission_status_full)
                    errStr.contains("MISSION_EXPIRED") ->
                        localizer.get(R.string.mission_status_expired)
                    errStr.contains("ALREADY_JOINED") ->
                        localizer.get(R.string.mission_joined_cta)
                    errStr.contains("MISSION_NOT_OPEN") ->
                        localizer.get(R.string.mission_status_stopped)
                    errStr.contains("CANNOT_JOIN_OWN_MISSION") ->
                        localizer.get(R.string.mission_mine_badge)
                    else -> friendlyError(e, localizer)
                }
                onResult(false, msg)
            }
        }
    }

    private var missionDeadlineJob: kotlinx.coroutines.Job? = null

    /**
     * Lọc bỏ ngay các nhiệm vụ đã đến hoặc quá hạn (ends_at <= server_now)
     * khỏi danh sách đang hiển thị, không chờ kéo refresh (mục 5 kế hoạch).
     */
    fun pruneExpiredMissions() {
        val now = repository.estimatedServerEpochMs()
        _uiState.update { state ->
            val unexpired = state.missions.filter { repository.isMissionActiveAndUnexpired(it, now) }
            if (unexpired.size != state.missions.size) {
                state.copy(missions = unexpired)
            } else {
                state
            }
        }
        scheduleNextMissionDeadlineCheck()
    }

    private fun scheduleNextMissionDeadlineCheck() {
        missionDeadlineJob?.cancel()
        val currentMissions = _uiState.value.missions
        if (currentMissions.isEmpty()) return
        val now = repository.estimatedServerEpochMs()
        val nextDeadlineDelay = currentMissions.mapNotNull { m ->
            val endsAt = m.endsAt ?: return@mapNotNull null
            val epoch = runCatching { java.time.Instant.parse(endsAt).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val delayMs = epoch - now
            if (delayMs > 0) delayMs else null
        }.minOrNull() ?: return

        missionDeadlineJob = viewModelScope.launch {
            kotlinx.coroutines.delay((nextDeadlineDelay + 200).coerceAtLeast(100L))
            pruneExpiredMissions()
        }
    }

    fun stopMissionDeadlineScheduler() {
        missionDeadlineJob?.cancel()
        missionDeadlineJob = null
    }

    private data class DailyTaskRequestOwner(
        val uid: String,
        val generation: Long,
        val day: String,
        val mutation: Long,
    )

    private fun currentDailyTaskOwner(): DailyTaskRequestOwner? {
        val uid = repository.currentUserId() ?: return null
        return DailyTaskRequestOwner(uid, SessionCacheManager.currentGeneration(),
            repository.businessDay().toString(), repository.getMutationVersion("daily_task"))
    }

    private fun isCurrentDailyTaskOwner(owner: DailyTaskRequestOwner): Boolean =
        repository.currentUserId() == owner.uid &&
            SessionCacheManager.currentGeneration() == owner.generation &&
            repository.businessDay().toString() == owner.day &&
            repository.getMutationVersion("daily_task") == owner.mutation

    private fun verifiedDailyTask(status: DailyTaskStatus?, day: String): DailyTaskStatus? {
        if (status == null || status.statusDate != day || status.rawMet == null ||
            status.rawHistoryReady == null || status.rawCarryoverReady == null ||
            status.rawCarryoverRemainingTests == null || status.rawCarryoverRemainingTests < 0 ||
            status.mode !in setOf("owner", "member", "exempt")) return null
        return status.forCurrentUi(day)
    }

    /** Publish one verified result to the card and cache, fencing older dashboard reads. */
    private fun publishDailyTaskSnapshot(
        owner: DailyTaskRequestOwner,
        request: Long,
        raw: DailyTaskStatus,
    ): Boolean {
        if (!isCurrentDailyTaskOwner(owner) || request != dailyTaskRequestSequence.get()) return false
        val fresh = verifiedDailyTask(raw, owner.day) ?: return false
        val publishedVersion = SessionCacheManager.nextDailyTaskVersion()
        repository.invalidateDailyTaskDashboardCache()
        val publishedOwner = owner.copy(mutation = publishedVersion)
        if (!isCurrentDailyTaskOwner(publishedOwner)) return false
        SessionCacheManager.updateCachedData(owner.uid, owner.generation) { cached ->
            if (cached.dateVn != owner.day || !isCurrentDailyTaskOwner(publishedOwner)) cached
            else cached.copy(dailyTask = fresh, missedStreak = fresh.missedStreak,
                isDailyTaskPendingRefresh = false)
        }
        _uiState.update { state ->
            if (!isCurrentDailyTaskOwner(publishedOwner)) state
            else state.copy(dailyTask = fresh, missedStreak = fresh.missedStreak,
                isDailyTaskPendingRefresh = false, isDailyTaskError = false)
        }
        if (fresh.isCarryoverHistoryPending) checkAndSchedulePendingCarryoverRetry(fresh)
        else stopPendingCarryoverRetry()
        return true
    }

    private suspend fun loadDailyTask(owner: DailyTaskRequestOwner): DailyTaskStatus? {
        if (!isCurrentDailyTaskOwner(owner)) return null
        val request = dailyTaskRequestSequence.incrementAndGet()
        val result = repository.fetchMyDailyTaskStatus()
        val status = result.getOrNull() ?: return null
        return if (publishDailyTaskSnapshot(owner, request, status)) status else null
    }

    private fun markDailyTaskLoadFailed(owner: DailyTaskRequestOwner) {
        if (!isCurrentDailyTaskOwner(owner)) return
        _uiState.update { state ->
            if (!isCurrentDailyTaskOwner(owner)) state else state.copy(
                isDailyTaskPendingRefresh = false, isDailyTaskError = true)
        }
        SessionCacheManager.updateCachedData(owner.uid, owner.generation) { cached ->
            if (cached.dateVn == owner.day && isCurrentDailyTaskOwner(owner))
                cached.copy(isDailyTaskPendingRefresh = false) else cached
        }
    }

    private data class PendingCarryoverOwner(val uid: String, val generation: Long, val businessDay: LocalDate)
    private var pendingCarryoverOwner: PendingCarryoverOwner? = null
    internal var pendingCarryoverJob: Job? = null

    fun stopPendingCarryoverRetry() {
        val oldJob = pendingCarryoverJob
        pendingCarryoverJob = null
        pendingCarryoverOwner = null
        oldJob?.cancel()
    }

    internal fun checkAndSchedulePendingCarryoverRetry(dailyTask: DailyTaskStatus?) {
        if (dailyTask == null || !dailyTask.isCarryoverHistoryPending) {
            stopPendingCarryoverRetry()
            return
        }
        val owner = currentDailyTaskOwner() ?: run { stopPendingCarryoverRetry(); return }
        val historyOwner = PendingCarryoverOwner(owner.uid, owner.generation, LocalDate.parse(owner.day))
        if (pendingCarryoverJob?.isActive == true && pendingCarryoverOwner == historyOwner) return
        stopPendingCarryoverRetry()
        pendingCarryoverOwner = historyOwner
        pendingCarryoverJob = viewModelScope.launch {
            var exhausted = false
            try {
                for (waitMs in PENDING_CARRYOVER_BACKOFF_MS) {
                    delay(waitMs)
                    val attemptOwner = currentDailyTaskOwner() ?: return@launch
                    if (attemptOwner.uid != historyOwner.uid || attemptOwner.generation != historyOwner.generation ||
                        attemptOwner.day != historyOwner.businessDay.toString()) return@launch
                    val fresh = loadDailyTask(attemptOwner)
                    if (fresh != null && !fresh.isCarryoverHistoryPending) return@launch
                }
                exhausted = true
            } finally {
                if (pendingCarryoverJob === coroutineContext[Job]) {
                    pendingCarryoverJob = null
                    pendingCarryoverOwner = null
                    val latestOwner = currentDailyTaskOwner()
                    if (exhausted && latestOwner != null && latestOwner.uid == historyOwner.uid &&
                        latestOwner.generation == historyOwner.generation &&
                        latestOwner.day == historyOwner.businessDay.toString() &&
                        _uiState.value.dailyTask?.isCarryoverHistoryPending == true) {
                        markDailyTaskLoadFailed(latestOwner)
                    }
                }
            }
        }
    }

    internal var dailyTaskRetryJob: Job? = null
    private var dailyTaskRetryMutation: Long? = null

    fun stopDailyTaskRetry() {
        val oldJob = dailyTaskRetryJob
        dailyTaskRetryJob = null
        dailyTaskRetryMutation = null
        oldJob?.cancel()
    }

    fun resumePendingDailyTaskWork() {
        if (!repository.isAuthenticated()) return
        val state = _uiState.value
        if (state.isDailyTaskPendingRefresh && dailyTaskRetryJob?.isActive != true) {
            val owner = currentDailyTaskOwner()
            if (owner != null) {
                scheduleDailyTaskRetry(owner.uid, owner.generation, owner.day, owner.mutation, immediate = false)
            }
        }
        checkAndSchedulePendingCarryoverRetry(state.dailyTask)
    }

    private fun scheduleDailyTaskRetry(
        targetUid: String,
        targetGen: Long,
        targetDay: String,
        targetMutation: Long,
        immediate: Boolean = false,
    ) {
        if (dailyTaskRetryJob?.isActive == true && dailyTaskRetryMutation == targetMutation) return
        stopDailyTaskRetry()
        val owner = DailyTaskRequestOwner(targetUid, targetGen, targetDay, targetMutation)
        if (!isCurrentDailyTaskOwner(owner)) return
        _uiState.update { it.copy(isDailyTaskPendingRefresh = true, isDailyTaskError = false) }
        dailyTaskRetryMutation = targetMutation
        dailyTaskRetryJob = viewModelScope.launch {
            try {
                val waits = if (immediate) listOf(0L, 500L, 1000L, 2000L, 4000L)
                    else listOf(200L, 500L, 1000L, 2000L, 4000L)
                for (waitMs in waits) {
                    if (waitMs > 0) delay(waitMs)
                    if (coroutineContext[Job]?.isActive != true || !isCurrentDailyTaskOwner(owner)) return@launch
                    if (loadDailyTask(owner) != null) return@launch
                }
                if (coroutineContext[Job]?.isActive == true) {
                    markDailyTaskLoadFailed(owner)
                }
            } finally {
                if (dailyTaskRetryJob === coroutineContext[Job]) {
                    dailyTaskRetryJob = null
                    dailyTaskRetryMutation = null
                }
            }
        }
    }

    fun retryDailyTask() {
        if (repository.currentUserId() == null) return
        stopDailyTaskRetry()
        stopPendingCarryoverRetry()
        repository.invalidateDailyTaskDashboardCache()
        repository.bumpMutationVersion("daily_task")
        val owner = currentDailyTaskOwner() ?: return
        scheduleDailyTaskRetry(owner.uid, owner.generation, owner.day, owner.mutation, immediate = true)
    }

    override fun onCleared() {
        stopMidnightScheduler()
        stopMissionDeadlineScheduler()
        stopPendingCarryoverRetry()
        stopDailyTaskRetry()
        super.onCleared()
    }
}
