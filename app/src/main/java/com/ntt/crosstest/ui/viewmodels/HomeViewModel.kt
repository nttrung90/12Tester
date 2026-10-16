package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.LeaderboardUser
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.TopupPackage
import kotlinx.coroutines.Job
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
    val query: String = "",
    val error: String? = null,
    val todayCheckinCount: Int = 0,
    val userCredits: Int = 100,
    val missedStreak: Int = 0,
    val userProfile: Profile? = null,
    val pendingReportsCount: Int = 0,
    val ownerStatsMap: Map<String, CampaignOwnerStats> = emptyMap(),
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

    init {
        refresh(context = context, forceRefresh = false)
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

            if (forceRefresh || _uiState.value.openCampaigns.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                val isAuthed = repository.isAuthenticated()

                // Đồng bộ vòng đời: app quá 15 ngày → hoàn thành; chủ app vắng 3 ngày → app tạm dừng.
                if (isAuthed) repository.syncCampaignLifecycle()

                val open = repository.listOpenCampaigns(forceRefresh = forceRefresh)
                val paused = if (isAuthed) repository.listPausedCampaigns(forceRefresh = forceRefresh) else emptyList()
                val completed = if (isAuthed) repository.listCompletedCampaigns(forceRefresh = forceRefresh) else emptyList()

                val allCampaigns = open + paused + completed
                val allIds = allCampaigns.mapNotNull { it.id }
                val statsMap = repository.getCampaignsStatsMap(allIds, forceRefresh = forceRefresh)
                val ownerIds = allCampaigns.mapNotNull { it.ownerId }.distinct()
                val ownerProfiles = repository.getProfiles(ownerIds, forceRefresh = forceRefresh)

                // Trạng thái cài đặt trên máy của người test (tính trên IO, tránh chặn main thread).
                val installFlags = if (appContext != null) withContext(Dispatchers.IO) {
                    InstalledAppCache.ensureFresh(appContext)
                    allCampaigns.distinctBy { it.packageName }
                        .associate { it.packageName to InstalledAppCache.isInstalled(appContext, it.packageName) }
                } else emptyMap()

                fun toDisplayItem(c: Campaign): CampaignDisplayItem {
                    val stats = statsMap[c.id] ?: CampaignStats()
                    val owner = ownerProfiles[c.ownerId]
                    val name = owner?.displayName?.ifBlank { null }
                        ?: owner?.email?.substringBefore("@")
                        ?: "Chủ app"
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

                val mine = repository.myCampaigns(forceRefresh = forceRefresh)
                val joined = repository.myJoinedCampaigns(forceRefresh = forceRefresh)
                val topUsers = repository.getLeaderboard(forceRefresh = forceRefresh)
                val todayCheckins = if (isAuthed) repository.getTodayCheckinCount(forceRefresh = forceRefresh) else 0
                val myProfile = if (isAuthed) repository.getMyProfile(forceRefresh = forceRefresh) else null
                val pendingReports = if (isAuthed) repository.getPendingReportsCountForOwner(forceRefresh = forceRefresh) else 0
                val ownerStats = if (isAuthed && mine.isNotEmpty()) {
                    mine.mapNotNull { it.id }.associateWith { id ->
                        repository.getCampaignOwnerStats(id, forceRefresh = forceRefresh)
                    }
                } else emptyMap()
                val unreadWarnings = if (isAuthed) repository.getUnreadWarnings() else emptyList()
                val weeklyCount = if (isAuthed) repository.getWeeklyWarningsCount() else 0
                val logs = if (isAuthed) repository.getMyCreditLogs() else emptyList()
                val packages = if (isAuthed) repository.getTopupPackages() else com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
                val notifs = if (isAuthed) repository.getMyNotifications() else emptyList()
                val unreadNotifsCount = notifs.count { !it.isRead }
                val quests = repository.getQuestSummary(forceRefresh = forceRefresh)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        isAuthenticated = isAuthed,
                        openCampaigns = open,
                        displayItems = displayList,
                        pausedDisplayItems = pausedList,
                        completedDisplayItems = completedList,
                        myCampaigns = mine,
                        joinedCampaigns = joined,
                        leaderboard = topUsers,
                        todayCheckinCount = todayCheckins,
                        userCredits = myProfile?.credits ?: 100,
                        missedStreak = myProfile?.missedStreak ?: 0,
                        userProfile = myProfile,
                        pendingReportsCount = pendingReports,
                        ownerStatsMap = ownerStats,
                        unreadWarning = unreadWarnings.firstOrNull(),
                        weeklyWarningsCount = weeklyCount,
                        creditLogs = logs,
                        topupPackages = packages,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = quests,
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
                        myCampaigns = mine,
                        joinedCampaigns = joined,
                        leaderboard = topUsers,
                        ownerStatsMap = ownerStats,
                        userCredits = myProfile?.credits ?: 100,
                        todayCheckinCount = todayCheckins,
                        missedStreak = myProfile?.missedStreak ?: 0,
                        pendingReportsCount = pendingReports,
                        notifications = notifs,
                        unreadNotificationsCount = unreadNotifsCount,
                        questSummary = quests,
                        timestamp = System.currentTimeMillis(),
                    )
                    SessionCacheManager.save(appContext, snapshot)
                }

                // Auto-sync missing icon for owner's campaigns if app is installed on this device
                if (appContext != null && isAuthed && mine.isNotEmpty()) {
                    for (c in mine) {
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
                        error = if (_uiState.value.openCampaigns.isEmpty()) friendlyError(e) else null
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
                val topUsers = repository.getLeaderboard(forceRefresh = forceRefresh)
                _uiState.update { it.copy(isLeaderboardLoading = false, leaderboard = topUsers) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLeaderboardLoading = false) }
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
        viewModelScope.launch {
            repository.acknowledgeWarning(warningId)
            _uiState.update { it.copy(unreadWarning = null) }
        }
    }

    fun markNotificationAsRead(id: String) {
        viewModelScope.launch {
            repository.markNotificationAsRead(id)
            _uiState.update { state ->
                val updated = state.notifications.map {
                    if (it.id == id) it.copy(isRead = true) else it
                }
                state.copy(
                    notifications = updated,
                    unreadNotificationsCount = updated.count { !it.isRead },
                )
            }
        }
    }

    fun markAllNotificationsAsRead() {
        viewModelScope.launch {
            repository.markAllNotificationsAsRead()
            _uiState.update { state ->
                val updated = state.notifications.map { it.copy(isRead = true) }
                state.copy(
                    notifications = updated,
                    unreadNotificationsCount = 0,
                )
            }
        }
    }

    fun deleteNotification(id: String) {
        viewModelScope.launch {
            repository.deleteNotification(id)
            _uiState.update { state ->
                val updated = state.notifications.filterNot { it.id == id }
                state.copy(
                    notifications = updated,
                    unreadNotificationsCount = updated.count { !it.isRead },
                )
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
                val msg = if (res.message.isNotBlank()) res.message else "Nạp ${pkg.totalCredits} Credit thành công!"
                onResult(true, msg)
            } catch (e: Exception) {
                onResult(false, friendlyError(e))
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
                    onResult(true, res.message.ifBlank { "Nhận thành công +${res.reward} Credit!" })
                } else {
                    _uiState.update { it.copy(claimingMilestone = null) }
                    onResult(false, res.message.ifBlank { "Không thể nhận thưởng lúc này." })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(claimingMilestone = null) }
                onResult(false, friendlyError(e))
            }
        }
    }
}
