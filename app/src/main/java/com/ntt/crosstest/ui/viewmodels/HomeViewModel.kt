package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.LeaderboardUser
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.TopupPackage
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
import com.ntt.crosstest.data.CampaignStats
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.data.UserWarning
import com.ntt.crosstest.data.QuestSummary
import com.ntt.crosstest.util.NetworkMonitor
import com.ntt.crosstest.util.InstalledAppCache
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class CampaignDisplayItem(
    val campaign: Campaign,
    val ownerName: String = "",
    val installedCount: Int = 0,
    val todayTestedCount: Int = 0,
    val hasTestedToday: Boolean = false,
    /** App đang có sẵn trên máy của người test (kiểm tra qua PackageManager). */
    val isInstalledLocally: Boolean = false,
) {
    val dailyTarget: Int get() = campaign.dailyTarget
    val isTargetReached: Boolean get() = todayTestedCount >= dailyTarget
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
    /** null = not searching; non-null = search results for [query] */
    val searchResults: List<CampaignDisplayItem>? = null,
    val myCampaigns: List<Campaign> = emptyList(),
    val joinedCampaigns: List<Campaign> = emptyList(),
    val leaderboard: List<LeaderboardUser> = emptyList(),
    val isLeaderboardLoading: Boolean = false,
    /** Bảng xếp hạng theo tổng lượt test (sub-tab "Top Test"). */
    val testLeaderboard: List<LeaderboardUser> = emptyList(),
    val isTestLeaderboardLoading: Boolean = false,
    val query: String = "",
    val error: String? = null,
    val todayCheckinCount: Int = 0,
    val userCredits: Int = 0,
    val missedStreak: Int = 0,
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
)

class HomeViewModel(
    private val repository: CampaignRepository,
    private val context: android.content.Context? = null,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        run {
            val cached = context?.let { SessionCacheManager.loadSync(it) }
            // Ignore a snapshot that provably belongs to a different account.
            // While the session is still loading (currentUid == null) the cache
            // is kept to preserve the offline-first instant paint.
            val currentUid = repository.currentUserId()
            val cacheMatchesSession = cached != null &&
                (cached.userId == null || currentUid == null || cached.userId == currentUid)
            if (cached != null && cacheMatchesSession) {
                HomeUiState(
                    isLoading = false,
                    isBackgroundSyncing = true,
                    isAuthenticated = cached.userId != null,
                    openCampaigns = cached.openCampaigns,
                    displayItems = cached.displayItems,
                    pausedDisplayItems = cached.pausedDisplayItems,
                    completedDisplayItems = cached.completedDisplayItems,
                    myCampaigns = cached.myCampaigns,
                    joinedCampaigns = cached.joinedCampaigns,
                    leaderboard = cached.leaderboard,
                    ownerStatsMap = cached.ownerStatsMap,
                    ownerNameById = (cached.displayItems + cached.pausedDisplayItems + cached.completedDisplayItems)
                        .filter { it.ownerName.isNotBlank() }
                        .mapNotNull { item -> item.campaign.id?.let { it to item.ownerName } }
                        .toMap(),
                    joinedStatsMap = (cached.displayItems + cached.pausedDisplayItems + cached.completedDisplayItems)
                        .mapNotNull { item ->
                            item.campaign.id?.let { id ->
                                id to CampaignStats(
                                    installedCount = item.installedCount,
                                    todayTestedCount = item.todayTestedCount,
                                    hasTestedToday = item.hasTestedToday,
                                )
                            }
                        }
                        .toMap(),
                    userCredits = cached.userCredits,
                    todayCheckinCount = cached.todayCheckinCount,
                    missedStreak = cached.missedStreak,
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

    /** Gom dữ liệu cá nhân của Trang chủ, dùng chung cho fast path (dashboard RPC) và legacy. */
    private data class PersonalData(
        val mine: List<Campaign> = emptyList(),
        val joined: List<Campaign> = emptyList(),
        val leaderboard: List<LeaderboardUser> = emptyList(),
        val todayCheckins: Int = 0,
        val myProfile: Profile? = null,
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
    private var leaderboardLoaded = false
    private var testLeaderboardLoaded = false

    /**
     * Đánh dấu refresh() đã bỏ dở vì phiên còn đang khôi phục (Initializing/RefreshFailure).
     * Khi phiên sẵn sàng trở lại (kể cả cùng uid — ẩn app rồi mở lại), observeSession
     * sẽ refresh đúng một lần để dữ liệu không bị cũ.
     */
    private var refreshWhenSessionReady = false

    init {
        refresh(context = context, forceRefresh = false)
        observeSession()
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
                            lastSyncedUid = uid
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

    /** Đồng bộ vòng đời campaign tối đa 1 lần/giờ (pg_cron, nếu được bật, đã lo phần còn lại). */
    private fun maybeSyncCampaignLifecycle(appContext: android.content.Context?) {
        val now = System.currentTimeMillis()
        val prefs = appContext?.getSharedPreferences("testcheo_sync", android.content.Context.MODE_PRIVATE)
        if (prefs != null && now - prefs.getLong("lifecycle_sync_ms", 0L) < LIFECYCLE_SYNC_THROTTLE_MS) return
        prefs?.edit()?.putLong("lifecycle_sync_ms", now)?.apply()
        viewModelScope.launch { repository.syncCampaignLifecycle() }
    }

    private companion object {
        const val LIFECYCLE_SYNC_THROTTLE_MS = 60 * 60 * 1000L
    }

    fun refresh(context: android.content.Context? = null, forceRefresh: Boolean = false) {
        val appContext = context ?: this.context
        viewModelScope.launch {
            val isOnline = appContext?.let { NetworkMonitor.isOnline(it) } ?: true
            _uiState.update { it.copy(isOffline = !isOnline) }

            if (!isOnline && _uiState.value.openCampaigns.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false) }
                return@launch
            }

            // Kéo làm mới giữ nguyên dữ liệu đang hiển thị; chỉ hiện vòng xoay toàn màn hình khi chưa có gì.
            if (_uiState.value.openCampaigns.isEmpty()) {
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

                // Fast path: 2 RPC gộp chạy SONG SONG (get_home_feed + get_my_dashboard) thay cho ~25 round-trip.
                val (feed, dashboard) = if (isAuthed) {
                    coroutineScope {
                        val feedDeferred = async { repository.getHomeFeed(forceRefresh = forceRefresh) }
                        val dashDeferred = async { repository.getMyDashboard(forceRefresh = forceRefresh) }
                        Pair(feedDeferred.await(), dashDeferred.await())
                    }
                } else {
                    Pair(null, null)
                }
                if (isAuthed) lastSyncedUid = repository.currentUserId()

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
                    )
                }

                val displayList = open.map { toDisplayItem(it) }.sortedWith(
                    compareBy<CampaignDisplayItem> { it.isTargetReached }
                        .thenByDescending { it.campaign.isBoosted }
                        .thenByDescending { it.campaign.tipCredits }
                )
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
                        val todayDeferred = async { repository.getTodayCheckinCount(forceRefresh = forceRefresh) }
                        val profileDeferred = async { repository.getMyProfile(forceRefresh = forceRefresh) }
                        val pendingDeferred = async { repository.getPendingReportsCountForOwner(forceRefresh = forceRefresh) }
                        val warningsDeferred = async { repository.getUnreadWarnings(forceRefresh = forceRefresh) }
                        val weeklyDeferred = async { repository.getWeeklyWarningsCount(forceRefresh = forceRefresh) }
                        val logsDeferred = async { repository.getMyCreditLogs(forceRefresh = forceRefresh) }
                        val packagesDeferred = async { repository.getTopupPackages(forceRefresh = forceRefresh) }
                        val notificationsDeferred = async { repository.getMyNotifications(forceRefresh = forceRefresh) }
                        val questsDeferred = async { repository.getQuestSummary(forceRefresh = forceRefresh) }
                        PersonalData(
                            mine = mineDeferred.await(),
                            joined = joinedDeferred.await(),
                            leaderboard = leaderboardDeferred.await(),
                            todayCheckins = todayDeferred.await(),
                            myProfile = profileDeferred.await(),
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

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        isAuthenticated = isAuthed,
                        openCampaigns = open,
                        displayItems = displayList,
                        pausedDisplayItems = pausedList,
                        completedDisplayItems = completedList,
                        myCampaigns = personal.mine,
                        joinedCampaigns = personal.joined,
                        // Dashboard không còn trả leaderboard (tải lazy theo tab) — giữ bảng đang hiển thị.
                        leaderboard = personal.leaderboard.ifEmpty { it.leaderboard },
                        todayCheckinCount = personal.todayCheckins,
                        userCredits = myProfile?.credits ?: it.userCredits,
                        missedStreak = myProfile?.missedStreak ?: 0,
                        userProfile = myProfile,
                        pendingReportsCount = personal.pendingReports,
                        ownerStatsMap = ownerStats,
                        joinedStatsMap = joinedStats,
                        ownerNameById = ownerNameById,
                        unreadWarning = personal.unreadWarnings.firstOrNull(),
                        weeklyWarningsCount = personal.weeklyWarnings,
                        creditLogs = personal.logs,
                        creditLogsLoadFailed = logsFailed,
                        topupPackages = personal.packages,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = personal.quests,
                        error = null,
                    )
                }

                // Persist fresh session snapshot to disk
                if (appContext != null) {
                    val snapshot = CachedSessionData(
                        userId = repository.currentUserId(),
                        userProfile = myProfile,
                        openCampaigns = open,
                        displayItems = displayList,
                        pausedDisplayItems = pausedList,
                        completedDisplayItems = completedList,
                        myCampaigns = personal.mine,
                        joinedCampaigns = personal.joined,
                        leaderboard = personal.leaderboard,
                        ownerStatsMap = ownerStats,
                        userCredits = myProfile?.credits ?: _uiState.value.userCredits,
                        todayCheckinCount = personal.todayCheckins,
                        missedStreak = myProfile?.missedStreak ?: 0,
                        pendingReportsCount = personal.pendingReports,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = personal.quests,
                        timestamp = System.currentTimeMillis(),
                    )
                    SessionCacheManager.save(appContext, snapshot)
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.openCampaigns.isEmpty()) friendlyError(e, localizer) else null
                    )
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
            com.ntt.crosstest.util.InstalledAppCache.refresh(appContext)
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
                val topUsers = repository.getLeaderboard(sort = "credit", forceRefresh = forceRefresh)
                _uiState.update { it.copy(isLeaderboardLoading = false, leaderboard = topUsers) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLeaderboardLoading = false) }
            }
        }
    }

    fun refreshTestLeaderboard(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestLeaderboardLoading = true) }
            try {
                val topUsers = repository.getLeaderboard(sort = "test", forceRefresh = forceRefresh)
                _uiState.update { it.copy(isTestLeaderboardLoading = false, testLeaderboard = topUsers) }
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
                refresh()
                val msg = if (res.message.isNotBlank()) res.message else localizer.get(R.string.home_topup_success_format, pkg.totalCredits)
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
                            userCredits = res.newBalance,
                            creditLogs = updatedLogs,
                            notifications = updatedNotifs,
                            unreadNotificationsCount = updatedNotifs.count { !it.isRead },
                        )
                    }
                    onResult(true, res.message.ifBlank { localizer.get(R.string.home_daily_reward_success_format, res.reward) })
                } else {
                    _uiState.update { it.copy(claimingMilestone = null) }
                    onResult(false, res.message.ifBlank { localizer.get(R.string.home_daily_reward_failed) })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(claimingMilestone = null) }
                onResult(false, friendlyError(e, localizer))
            }
        }
    }
}
