package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignDetailResult
import com.ntt.crosstest.data.CampaignMember
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CampaignPushStore
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.PendingSlotIntent
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.data.TesterItem
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.ui.components.CreditReward
import com.ntt.crosstest.ui.components.CreditRewardType
import com.ntt.crosstest.util.UiStateMessageResolver
import com.ntt.crosstest.util.WarningRenderer
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OwnerAppDetailUiState(
    val isLoading: Boolean = true,
    val isBackgroundSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val isActionLoading: Boolean = false,
    val campaign: Campaign? = null,
    val ownerProfile: Profile? = null,
    val slotInfo: SlotInfo? = null,
    val stats: CampaignOwnerStats = CampaignOwnerStats(),
    val testers: List<TesterItem> = emptyList(),
    val reports: List<TesterReport> = emptyList(),
    error: String? = null,
    message: String? = null,
    warningMessage: String? = null,
    val isOwnerLocked: Boolean = false,
    /** Admin đang mở màn quản lý app của chủ khác. */
    val isAdmin: Boolean = false,
    /** Người xem đúng là chủ app (Admin xem app người khác = false) — điều kiện thấy mục Quản lý nhiệm vụ. */
    val isActualOwner: Boolean = false,
    /** Nhiệm vụ hiện tại của app (mục "Quản lý nhiệm vụ", migration 040). */
    val mission: Mission? = null,
    val isMissionLoading: Boolean = false,
    missionError: String? = null,
    val isMissionActionLoading: Boolean = false,
    /** Đợt cập nhật phiên bản app hiện tại. */
    val appUpdate: com.ntt.crosstest.data.AppUpdateRelease? = null,
    /** Reward overlay shown inside [TesterReportsDialog]. */
    val reward: CreditReward? = null,
    /** Trạng thái đang gửi yêu cầu đẩy app (RPC push_campaign). */
    val isPushLoading: Boolean = false,
    /** Lỗi phát sinh riêng của thao tác đẩy app. */
    pushError: String? = null,
    /** Trạng thái có yêu cầu đẩy app đang chờ giải quyết (observable trong VM state). */
    val hasPendingPush: Boolean = false,
    /** Đang thực hiện đối soát tự động / chỉ đọc. */
    val isPushVerifying: Boolean = false,
    /** Kết quả đối soát: Không tìm thấy biên lai trên server, cho phép người dùng thử mua lại. */
    val isPushPendingNotFound: Boolean = false,
    val isPushQuoteLoading: Boolean = false,
    val pushQuoteError: String? = null,
    val pushCost: Int = 100,
    val pushRevision: String? = null,
    val dailyTask: DailyTaskStatus? = null,
) {
    private val rawError: String? = error
    private val rawMessage: String? = message
    private val rawWarningMessage: String? = warningMessage
    private val rawMissionError: String? = missionError
    private val rawPushError: String? = pushError

    val error: String?
        get() = UiStateMessageResolver.resolve(rawError)
    val message: String?
        get() = UiStateMessageResolver.resolve(rawMessage)
    val warningMessage: String?
        get() = UiStateMessageResolver.resolve(rawWarningMessage)
    val missionError: String?
        get() = UiStateMessageResolver.resolve(rawMissionError)
    val pushError: String?
        get() = UiStateMessageResolver.resolve(rawPushError)

    val hasValidPushQuote: Boolean
        get() = pushRevision != null && pushCost > 0 && pushQuoteError == null

    val isPushActiveToday: Boolean
        get() = campaign?.isPushActive() == true

    val isResumeBlockedByCarryover: Boolean
        get() = dailyTask != null && dailyTask.hasRemainingCarryover

    val isResumeBlockedByPending: Boolean
        get() = dailyTask != null && (!dailyTask.carryoverReady || !dailyTask.historyReady)

    val isResumeBlockedByUnmetDailyTask: Boolean
        get() = dailyTask != null && !isResumeBlockedByPending && !dailyTask.met

    fun copy(
        isLoading: Boolean = this.isLoading,
        isBackgroundSyncing: Boolean = this.isBackgroundSyncing,
        isOffline: Boolean = this.isOffline,
        isActionLoading: Boolean = this.isActionLoading,
        campaign: Campaign? = this.campaign,
        ownerProfile: Profile? = this.ownerProfile,
        slotInfo: SlotInfo? = this.slotInfo,
        stats: CampaignOwnerStats = this.stats,
        testers: List<TesterItem> = this.testers,
        reports: List<TesterReport> = this.reports,
        error: String? = this.rawError,
        message: String? = this.rawMessage,
        warningMessage: String? = this.rawWarningMessage,
        isOwnerLocked: Boolean = this.isOwnerLocked,
        isAdmin: Boolean = this.isAdmin,
        isActualOwner: Boolean = this.isActualOwner,
        mission: Mission? = this.mission,
        isMissionLoading: Boolean = this.isMissionLoading,
        missionError: String? = this.rawMissionError,
        isMissionActionLoading: Boolean = this.isMissionActionLoading,
        appUpdate: com.ntt.crosstest.data.AppUpdateRelease? = this.appUpdate,
        reward: CreditReward? = this.reward,
        isPushLoading: Boolean = this.isPushLoading,
        pushError: String? = this.rawPushError,
        hasPendingPush: Boolean = this.hasPendingPush,
        isPushVerifying: Boolean = this.isPushVerifying,
        isPushPendingNotFound: Boolean = this.isPushPendingNotFound,
        isPushQuoteLoading: Boolean = this.isPushQuoteLoading,
        pushQuoteError: String? = this.pushQuoteError,
        pushCost: Int = this.pushCost,
        pushRevision: String? = this.pushRevision,
        dailyTask: DailyTaskStatus? = this.dailyTask,
    ): OwnerAppDetailUiState = OwnerAppDetailUiState(
        isLoading = isLoading,
        isBackgroundSyncing = isBackgroundSyncing,
        isOffline = isOffline,
        isActionLoading = isActionLoading,
        campaign = campaign,
        ownerProfile = ownerProfile,
        slotInfo = slotInfo,
        stats = stats,
        testers = testers,
        reports = reports,
        error = error,
        message = message,
        warningMessage = warningMessage,
        isOwnerLocked = isOwnerLocked,
        isAdmin = isAdmin,
        isActualOwner = isActualOwner,
        mission = mission,
        isMissionLoading = isMissionLoading,
        missionError = missionError,
        isMissionActionLoading = isMissionActionLoading,
        appUpdate = appUpdate,
        reward = reward,
        isPushLoading = isPushLoading,
        pushError = pushError,
        hasPendingPush = hasPendingPush,
        isPushVerifying = isPushVerifying,
        isPushPendingNotFound = isPushPendingNotFound,
        isPushQuoteLoading = isPushQuoteLoading,
        pushQuoteError = pushQuoteError,
        pushCost = pushCost,
        pushRevision = pushRevision,
        dailyTask = dailyTask,
    )
}

class OwnerAppDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val refreshSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val missionLoadSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val latestAppliedTaskSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val isSlotActionInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastRenewSlotIntentKey: String? = null
    private var lastRenewSlotRequestId: String? = null
    private val _uiState = MutableStateFlow(OwnerAppDetailUiState())
    val uiState: StateFlow<OwnerAppDetailUiState> = _uiState.asStateFlow()

    init {
        val initialUid = repository.currentUserId()
        val initialPending = initialUid?.let { CampaignPushStore.peekPendingRequestId(localizer.context, it, campaignId) != null } ?: false
        if (initialPending) {
            _uiState.update { it.copy(hasPendingPush = true) }
        }

        viewModelScope.launch {
            // Tự động đối soát biên lai đang chờ mà không trừ tiền (migration 066).
            // Phải chạy trước bất kỳ luồng missing/paused/finalized nào.
            reconcilePendingPushIfAny(localizer.context)

            // Nạp cache trước (best-effort): lỗi mạng ở đây không được làm crash màn,
            // refresh() phía dưới sẽ tải lại và báo lỗi qua UI state nếu cần.
            runCatching {
                val cachedCamp = repository.peekCampaign(campaignId)
                val cachedStats = repository.peekCampaignOwnerStats(campaignId)
                val cachedReports = repository.peekCampaignReports(campaignId, canManage = true)
                val cachedTesters = repository.peekCampaignTesters(campaignId)
                if (cachedCamp != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = true,
                            campaign = cachedCamp,
                            stats = cachedStats ?: it.stats,
                            reports = cachedReports ?: it.reports,
                            testers = cachedTesters ?: it.testers,
                        )
                    }
                }
            }
            refresh(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        loadMission()
        val seq = refreshSequence.incrementAndGet()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        val initialDay = repository.businessDay()
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.campaign == null) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                // Fast path: 1 RPC gộp (campaign + testers + reports + stats) thay cho 5-8 round-trip.
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = forceRefresh)
                if (detail != null) {
                    if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                        return@launch
                    }
                    applyDetail(detail, forceRefresh, seq, initialGen, initialUid, initialDay)
                    return@launch
                }
                val camp = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                val stats = repository.getCampaignOwnerStats(campaignId, forceRefresh = forceRefresh)
                val testers = repository.getCampaignTesters(campaignId, forceRefresh = forceRefresh)
                val reports = repository.getCampaignReports(campaignId, canManage = true, forceRefresh = forceRefresh)
                val myProfile = repository.getMyProfile(forceRefresh = forceRefresh)
                val taskSeq = dailyTaskSequence.incrementAndGet()
                val dailyTask = try { repository.getMyDailyTaskStatus() } catch (_: Exception) { null }
                val shouldApplyDaily = taskSeq >= latestAppliedTaskSeq.get()
                if (shouldApplyDaily) {
                    latestAppliedTaskSeq.set(taskSeq)
                }
                val owner = resolveOwnerProfile(
                    ownerId = camp?.ownerId,
                    fetched = camp?.ownerId?.let { id ->
                        repository.getProfiles(listOf(id), forceRefresh = forceRefresh)[id]
                    },
                    myProfile = myProfile,
                )
                val slots = try { repository.getMySlotsInfo(forceRefresh = forceRefresh) } catch (_: Exception) { emptyList() }
                val slot = slots.find { it.slotNumber == camp?.slotNumber }
                val pushQuoteResult = runCatching { repository.getPushQuote() }
                if (pushQuoteResult.exceptionOrNull() is CancellationException) {
                    throw pushQuoteResult.exceptionOrNull() as CancellationException
                }
                val pushQuote = pushQuoteResult.getOrNull()
                val pushQErr = pushQuoteResult.exceptionOrNull()?.let { friendlyBusinessPolicyError(it, localizer) }

                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }

                val finalStats = if (repository.businessDay() == initialDay) stats else stats?.copy(testedTodayCount = 0)
                _uiState.update {
                    val preservedCamp = if (camp?.pushOrder == null && it.campaign?.pushOrder != null) {
                        camp?.copy(
                            pushBusinessDate = it.campaign.pushBusinessDate,
                            pushExpiresAt = it.campaign.pushExpiresAt,
                            pushOrder = it.campaign.pushOrder
                        )
                    } else camp
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = preservedCamp,
                        ownerProfile = owner,
                        slotInfo = slot,
                        isPushQuoteLoading = false,
                        pushQuoteError = pushQErr,
                        pushCost = pushQuote?.cost ?: it.pushCost,
                        pushRevision = pushQuote?.revision,
                        stats = finalStats ?: CampaignOwnerStats(),
                        testers = testers,
                        reports = reports,
                        isAdmin = myProfile?.isAdmin == true,
                        isActualOwner = camp?.ownerId != null &&
                            camp.ownerId == repository.currentUserId(),
                        dailyTask = if (shouldApplyDaily) dailyTask else it.dailyTask,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.campaign == null) friendlyError(e, localizer) else null,
                    )
                }
            }
        }
    }

    /** Áp kết quả RPC `get_campaign_detail` (1 round-trip) vào UI state của chủ app. */
    private suspend fun applyDetail(
        detail: CampaignDetailResult,
        forceRefresh: Boolean,
        seq: Long,
        initialGen: Long,
        initialUid: String?,
        initialDay: LocalDate,
    ) {
        val camp = detail.campaign
        val isSameDay = repository.businessDay() == initialDay
        val stats = CampaignOwnerStats(
            installedTestersCount = detail.testers.size,
            testedTodayCount = if (isSameDay) detail.todayTestedCount else 0,
            totalReportsCount = detail.testerReportsCount,
            pendingReportsCount = detail.pendingReportsCount,
            averageRating = detail.avgRating,
        )
        val testers = detail.testers.map { tester ->
            TesterItem(
                member = CampaignMember(campaignId = campaignId, userId = tester.userId, joinedAt = tester.joinedAt),
                profile = detail.profiles[tester.userId],
                checkinCount = tester.checkinCount,
                lastCheckinDate = tester.lastCheckinDate,
            )
        }
        val reports = detail.reports.map { report ->
            report.copy(testerProfile = detail.profiles[report.userId])
        }
        val owner = resolveOwnerProfile(
            ownerId = camp.ownerId,
            fetched = camp.ownerId?.let { detail.profiles[it] },
            myProfile = detail.myProfile,
        )
        val slots = try { repository.getMySlotsInfo(forceRefresh = forceRefresh) } catch (_: Exception) { emptyList() }
        val pushQuoteResult = runCatching { repository.getPushQuote() }
        if (pushQuoteResult.exceptionOrNull() is CancellationException) {
            throw pushQuoteResult.exceptionOrNull() as CancellationException
        }
        val pushQuote = pushQuoteResult.getOrNull()
        val pushQErr = pushQuoteResult.exceptionOrNull()?.let { friendlyBusinessPolicyError(it, localizer) }
        val taskSeq = dailyTaskSequence.incrementAndGet()
        val dailyTask = try { repository.getMyDailyTaskStatus() } catch (_: Exception) { null }
        val shouldApplyDaily = taskSeq >= latestAppliedTaskSeq.get()
        if (shouldApplyDaily) {
            latestAppliedTaskSeq.set(taskSeq)
        }

        if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != repository.currentUserId()) {
            return
        }
        val slot = slots.find { it.slotNumber == camp.slotNumber }
        val finalStats = if (repository.businessDay() == initialDay) stats else stats.copy(testedTodayCount = 0)

        _uiState.update {
            val preservedCamp = if (camp.pushOrder == null && it.campaign?.pushOrder != null) {
                camp.copy(
                    pushBusinessDate = it.campaign.pushBusinessDate,
                    pushExpiresAt = it.campaign.pushExpiresAt,
                    pushOrder = it.campaign.pushOrder
                )
            } else camp
            it.copy(
                isLoading = false,
                isBackgroundSyncing = false,
                campaign = preservedCamp,
                ownerProfile = owner,
                slotInfo = slot,
                isPushQuoteLoading = false,
                pushQuoteError = pushQErr,
                pushCost = pushQuote?.cost ?: it.pushCost,
                pushRevision = pushQuote?.revision,
                stats = finalStats,
                testers = testers,
                reports = reports,
                isAdmin = detail.myProfile?.isAdmin == true,
                isActualOwner = detail.isActualOwner,
                appUpdate = detail.appUpdate,
                dailyTask = if (shouldApplyDaily) dailyTask else it.dailyTask,
                error = null,
            )
        }
    }

    /**
     * Profile chủ app hiển thị trên summary card. Chỉ fallback về profile CỦA CHÍNH
     * MÌNH khi app này đúng là của mình: admin/moderator xem app người khác mà profile
     * chủ app thiếu dữ liệu thì để null, không thay tên/số dư của mình vào chủ app.
     */
    private fun resolveOwnerProfile(ownerId: String?, fetched: Profile?, myProfile: Profile?): Profile? =
        fetched ?: myProfile?.takeIf { ownerId != null && it.id == ownerId }

    fun togglePauseResume() {
        val camp = _uiState.value.campaign ?: return
        if (camp.isFinalized) return
        if (camp.isAdminPaused && !_uiState.value.isAdmin) return
        val isAdmin = _uiState.value.isAdmin
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                if (camp.isPaused) {
                    // Pre-check for owner resume: debt must be 0 and history ready
                    if (!isAdmin) {
                        val daily = _uiState.value.dailyTask
                        if (daily != null) {
                            if (!daily.carryoverReady || !daily.historyReady) {
                                _uiState.update { it.copy(isActionLoading = false, error = localizer.get(R.string.oad_resume_error_pending)) }
                                reloadDailyTaskOnly()
                                return@launch
                            }
                            if (!daily.met) {
                                _uiState.update {
                                    it.copy(
                                        isActionLoading = false,
                                        error = localizer.get(R.string.owner_app_inactive_3days)
                                    )
                                }
                                reloadDailyTaskOnly()
                                return@launch
                            }
                            if (daily.hasRemainingCarryover) {
                                _uiState.update {
                                    it.copy(
                                        isActionLoading = false,
                                        error = localizer.get(R.string.oad_resume_error_carryover_debt_format, daily.carryoverRemainingTests)
                                    )
                                }
                                reloadDailyTaskOnly()
                                return@launch
                            }
                        }
                    }

                    // Chủ app: RPC kiểm tra cửa sổ 30 ngày + chủ đã hoạt động lại + nợ cộng dồn == 0.
                    // Admin: RPC riêng, bỏ qua điều kiện chủ vắng nhưng vẫn giữ luật 30 ngày/Slot.
                    val resultStatus = if (isAdmin) {
                        repository.adminResumeCampaign(camp.id.orEmpty()).status
                    } else {
                        repository.resumeCampaign(camp.id.orEmpty()).status
                    }
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            message = if (resultStatus == "completed") {
                                localizer.get(R.string.owner_app_expired_completed)
                            } else {
                                localizer.get(R.string.owner_app_reactivated)
                            },
                        )
                    }
                    refresh()
                } else {
                    if (isAdmin) {
                        repository.adminPauseCampaign(camp.id.orEmpty(), reason = null)
                    } else {
                        repository.updateCampaignStatus(camp.id.orEmpty(), "paused", pausedReason = "owner_manual")
                    }
                    // Phải hạ cờ loading trước khi refresh: refresh() không tự reset cờ này,
                    // nếu bỏ sót thì hộp thoại Gia hạn slot bị khóa vĩnh viễn.
                    _uiState.update { it.copy(isActionLoading = false) }
                    refresh()
                }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("OWNER_CARRYOVER_INCOMPLETE") == true -> {
                        val debtCount = e.message?.substringAfter("OWNER_CARRYOVER_INCOMPLETE:")
                            ?.takeWhile { it.isDigit() }?.toIntOrNull()
                        if (debtCount != null && debtCount > 0) {
                            localizer.get(R.string.oad_resume_error_carryover_debt_format, debtCount)
                        } else {
                            localizer.get(R.string.oad_resume_error_carryover_debt_generic)
                        }
                    }
                    e.message?.contains("DAILY_TASK_PENDING") == true ->
                        localizer.get(R.string.oad_resume_error_pending)
                    e.message?.contains("OWNER_INACTIVE") == true ->
                        localizer.get(R.string.owner_app_inactive_3days)
                    e.message?.contains("PAUSED_BY_ADMIN") == true ->
                        localizer.get(R.string.oad_paused_by_admin_error)
                    e.message?.contains("CAMPAIGN_NOT_FOUND") == true ->
                        localizer.get(R.string.campaign_not_found)
                    e.message?.contains("CAMPAIGN_FINALIZED") == true ->
                        localizer.get(R.string.campaign_admin_error_finalized)
                    e.message?.contains("NOT_ADMIN") == true ->
                        localizer.get(R.string.error_permission_denied)
                    e.message?.contains("CAMPAIGN_WINDOW_EXPIRED") == true ->
                        localizer.get(R.string.owner_app_expired_completed)
                    e.message?.contains("CAMPAIGN_NOT_PAUSED") == true ->
                        localizer.get(R.string.owner_app_not_paused)
                    e.message?.contains("SLOT_EXPIRED") == true ||
                        e.message?.contains("SLOT_NOT_ACTIVE_OR_EXPIRED") == true ->
                        localizer.get(R.string.owner_slot_expired_format, camp.slotNumber)
                    e.message?.contains("SLOT_ALREADY_IN_USE") == true ->
                        localizer.get(R.string.create_slot_busy)
                    else -> friendlyError(e, localizer)
                }
                _uiState.update { it.copy(isActionLoading = false, error = msg) }
                if (e.message?.contains("OWNER_CARRYOVER_INCOMPLETE") == true ||
                    e.message?.contains("DAILY_TASK_PENDING") == true ||
                    e.message?.contains("OWNER_INACTIVE") == true) {
                    reloadDailyTaskOnly()
                }
                if (e.message?.contains("CAMPAIGN_WINDOW_EXPIRED") == true) refresh()
            }
        }
    }

    fun renewCurrentSlot() {
        val camp = _uiState.value.campaign ?: return
        if (camp.slotNumber <= 1) return
        if (!isSlotActionInProgress.compareAndSet(false, true)) return

        val userId = repository.currentUserId() ?: run {
            isSlotActionInProgress.set(false)
            return
        }
        val gen = SessionCacheManager.currentGeneration()
        val currentSlot = _uiState.value.slotInfo
        val cost = currentSlot?.cost ?: SlotInfo.costForSlot(camp.slotNumber)
        val revision = currentSlot?.policyRevision

        if (revision == null) {
            isSlotActionInProgress.set(false)
            _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_quote_unavailable)) }
            return
        }

        val pending = FinancialIntentStore.peekSlotIntent(localizer.context, userId, camp.slotNumber)
        val intentToUse = if (pending != null && pending.expectedCost == cost && pending.expectedRevision == revision) {
            pending
        } else {
            val newIntent = PendingSlotIntent(
                userId = userId,
                slotNumber = camp.slotNumber,
                expectedCost = cost,
                expectedRevision = revision,
                renewalDays = currentSlot?.renewalDays ?: 30,
                clientRequestId = java.util.UUID.randomUUID().toString(),
            )
            FinancialIntentStore.saveSlotIntent(localizer.context, newIntent)
            newIntent
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.unlockOrRenewSlotV2(
                    slotNumber = intentToUse.slotNumber,
                    clientRequestId = intentToUse.clientRequestId,
                    expectedCost = intentToUse.expectedCost,
                    expectedRevision = intentToUse.expectedRevision,
                )
                if (repository.currentUserId() != userId || SessionCacheManager.currentGeneration() != gen) return@launch

                FinancialIntentStore.clearSlotIntent(localizer.context, userId, camp.slotNumber)

                _uiState.update {
                    it.copy(
                        message = localizer.get(R.string.owner_slot_renewed_format, camp.slotNumber)
                    )
                }
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (repository.currentUserId() != userId || SessionCacheManager.currentGeneration() != gen) return@launch

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true
                if (isConflict) {
                    FinancialIntentStore.clearSlotIntent(localizer.context, userId, camp.slotNumber)
                    repository.invalidateBusinessQuote()
                    refresh(forceRefresh = true)
                }
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        localizer.get(R.string.owner_renew_insufficient_format, cost)
                    else -> friendlyBusinessPolicyError(e, localizer)
                }
                _uiState.update { it.copy(error = msg) }
            } finally {
                if (repository.currentUserId() == userId && SessionCacheManager.currentGeneration() == gen) {
                    isSlotActionInProgress.set(false)
                    _uiState.update { it.copy(isActionLoading = false) }
                }
            }
        }
    }

    fun updateTip(tipCredits: Int) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                val balance = _uiState.value.ownerProfile?.credits ?: 0
                if (tipCredits > 0 && balance < tipCredits) {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            error = localizer.get(R.string.oad_tip_insufficient_format, balance)
                        )
                    }
                    return@launch
                }
                val res = repository.setCampaignTip(camp.id.orEmpty(), tipCredits)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = if (res.tipCredits > 0) {
                            localizer.get(R.string.oad_tip_saved_on_format, res.tipCredits)
                        } else {
                            localizer.get(R.string.oad_tip_saved_off)
                        }
                    )
                }
                refresh(forceRefresh = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun markProduction() {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.updateCampaignStatus(camp.id.orEmpty(), "production")
                _uiState.update { it.copy(isActionLoading = false) }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    private var currentDeleteOpId = 0L

    fun deleteCampaign(onDeleted: () -> Unit) {
        deleteCampaign(onDeleted = onDeleted, onPendingRemoval = null)
    }

    fun deleteCampaign(
        onDeleted: () -> Unit,
        onPendingRemoval: (() -> Unit)?,
    ) {
        val camp = _uiState.value.campaign ?: return
        val opId = ++currentDeleteOpId
        val capturedUid = repository.currentUserId()
        val capturedGen = SessionCacheManager.currentGeneration()

        _uiState.update { it.copy(isActionLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val result = repository.deleteCampaign(camp.id.orEmpty())

                if (opId != currentDeleteOpId || capturedUid != repository.currentUserId() || capturedGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                    return@launch
                }

                if (result.isPendingRemoval) {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            message = localizer.get(R.string.campaign_pending_removal_msg),
                        )
                    }
                    onPendingRemoval?.invoke()
                } else if (result.isRemoved) {
                    _uiState.update { it.copy(isActionLoading = false) }
                    onDeleted()
                } else {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            error = localizer.get(R.string.error_generic),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (opId != currentDeleteOpId || capturedUid != repository.currentUserId() || capturedGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun updateDetails(
        appName: String,
        description: String,
        playUrl: String,
        testingUrl: String,
        testUsername: String,
        testPassword: String,
    ) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.updateCampaignDetails(
                    campaignId = camp.id.orEmpty(),
                    appName = appName,
                    description = description,
                    playUrl = playUrl,
                    testingUrl = testingUrl,
                    testUsername = testUsername,
                    testPassword = testPassword,
                )
                _uiState.update { it.copy(isActionLoading = false) }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun approveReport(report: TesterReport, bonusCredits: Int = 0) {
        val reportId = report.id ?: return
        // Optimistic: cập nhật danh sách + bộ đếm chờ duyệt ngay. KHÔNG giữ snapshot
        // để rollback: hai thao tác duyệt/từ chối liên tiếp sẽ phục hồi lẫn nhau thành
        // state sai. Server là nguồn sự thật — lỗi thì báo lỗi + refresh() đồng bộ lại.
        _uiState.update { state ->
            state.copy(
                reports = state.reports.map { if (it.id == reportId) it.copy(status = "approved") else it },
                stats = state.stats.copy(
                    pendingReportsCount = (state.stats.pendingReportsCount - 1).coerceAtLeast(0),
                ),
            )
        }
        viewModelScope.launch {
            try {
                val result = repository.approveReport(report, bonusCredits)
                val msg = when {
                    result.ownerCreditsAwarded > 0 && result.tipAwarded > 0 ->
                        localizer.get(
                            R.string.owner_approve_tip_format,
                            result.creditsAwarded + result.tipAwarded,
                            result.tipAwarded,
                            result.ownerCreditsAwarded,
                        )
                    result.ownerCreditsAwarded > 0 ->
                        localizer.get(
                            R.string.owner_approve_bonus_format,
                            result.creditsAwarded,
                            result.ownerCreditsAwarded,
                        )
                    result.tipAwarded > 0 ->
                        localizer.get(
                            R.string.reviewer_approve_tip_format,
                            result.creditsAwarded + result.tipAwarded,
                            result.tipAwarded,
                        )
                    result.creditsAwarded > 0 ->
                        localizer.get(R.string.reviewer_approve_bonus_format, result.creditsAwarded)
                    else -> localizer.get(R.string.owner_approve_success)
                }
                val hasReward = result.creditsAwarded > 0 ||
                    result.tipAwarded > 0 ||
                    result.ownerCreditsAwarded > 0
                _uiState.update {
                    it.copy(
                        // Overlay already shows the reward amounts; keep the
                        // Toast for the rare case where nothing was awarded.
                        message = if (hasReward) null else msg,
                        reward = if (hasReward) {
                            CreditReward(
                                type = CreditRewardType.OWNER_APPROVED,
                                amount = result.creditsAwarded,
                                ownerAmount = result.ownerCreditsAwarded,
                                tipAmount = result.tipAwarded,
                                appName = _uiState.value.campaign?.appName.orEmpty(),
                            )
                        } else {
                            it.reward
                        },
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = friendlyError(e, localizer))
                }
                // KHÔNG gọi refresh() ở đây: refresh xoá error ngay khi bắt đầu nên
                // người dùng không kịp thấy. Chỉ tải lại danh sách báo cáo để hoàn tác
                // optimistic update theo server.
                reloadReportsOnly()
            }
        }
    }

    fun rejectReport(report: TesterReport, reason: String? = null) {
        val reportId = report.id ?: return
        // Optimistic: cập nhật danh sách + bộ đếm chờ duyệt ngay. KHÔNG giữ snapshot
        // để rollback — xem chú thích trong [approveReport].
        _uiState.update { state ->
            state.copy(
                reports = state.reports.map { if (it.id == reportId) it.copy(status = "rejected") else it },
                stats = state.stats.copy(
                    pendingReportsCount = (state.stats.pendingReportsCount - 1).coerceAtLeast(0),
                ),
            )
        }
        viewModelScope.launch {
            try {
                val result = repository.rejectReport(report, reason)
                if (result.ownerWarned) {
                    _uiState.update {
                        it.copy(
                            warningMessage = WarningRenderer.renderOwnerWarning(result.ownerWarningMsg, localizer),
                            isOwnerLocked = result.isOwnerLocked,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            message = if (result.creditsDeducted > 0) {
                                localizer.get(R.string.owner_reject_success_format, result.creditsDeducted)
                            } else {
                                localizer.get(R.string.owner_reject_success)
                            },
                        )
                    }
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = friendlyError(e, localizer))
                }
                // Xem chú thích trong [approveReport]: giữ error, chỉ tải lại báo cáo.
                reloadReportsOnly()
            }
        }
    }

    /**
     * Tải lại riêng danh sách báo cáo + số chờ duyệt sau khi duyệt/từ chối lỗi
     * (đua thao tác, hết hạn 8h...). Không đụng error/message như refresh()
     * để thông báo lỗi vẫn hiện cho chủ app.
     */
    private fun reloadReportsOnly() {
        viewModelScope.launch {
            try {
                val reports = repository.getCampaignReports(campaignId, canManage = true, forceRefresh = true)
                _uiState.update {
                    it.copy(
                        reports = reports,
                        stats = it.stats.copy(pendingReportsCount = reports.count { r -> r.isPending }),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Tải lại riêng nhiệm vụ ngày và trạng thái chiến dịch sau khi bật lại app bị từ chối
     * (còn nợ cộng dồn, chưa hoàn thành chỉ tiêu, lịch sử pending...). Không xóa error
     * để thông báo lỗi tiếp tục hiển thị trên UI.
     * Có guard UID, session generation, sequence counter và ngày nghiệp vụ.
     */
    fun refreshDailyTaskState() {
        val uid = repository.currentUserId() ?: return
        val gen = SessionCacheManager.currentGeneration()
        val seq = dailyTaskSequence.incrementAndGet()
        val initialDay = repository.businessDay()
        viewModelScope.launch {
            try {
                val daily = repository.getMyDailyTaskStatus()
                val camp = repository.getCampaign(campaignId, forceRefresh = true)
                if (uid != repository.currentUserId() || gen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                if (repository.businessDay() != initialDay) {
                    refresh(forceRefresh = true)
                    return@launch
                }
                val shouldApply = seq >= latestAppliedTaskSeq.get()
                if (shouldApply) {
                    latestAppliedTaskSeq.set(seq)
                }
                _uiState.update {
                    it.copy(
                        dailyTask = if (shouldApply) daily else it.dailyTask,
                        campaign = camp ?: it.campaign,
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadDailyTaskOnly() = refreshDailyTaskState()

    fun clearWarning() {
        _uiState.update { it.copy(warningMessage = null) }
    }

    /**
     * Nhiệm vụ hiện tại của app cho mục "Quản lý nhiệm vụ". Server chưa chạy
     * migration 040 thì RPC lỗi — bỏ qua im lặng, giữ nguyên giá trị cũ.
     */
    fun loadMission() {
        val seq = missionLoadSequence.incrementAndGet()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        val targetCampaignId = campaignId
        viewModelScope.launch {
            _uiState.update { it.copy(isMissionLoading = true, missionError = null) }
            try {
                val mission = repository.getCampaignMission(targetCampaignId)
                if (seq != missionLoadSequence.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId() ||
                    targetCampaignId != campaignId
                ) {
                    _uiState.update { it.copy(isMissionLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(mission = mission, isMissionLoading = false, missionError = null) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (seq != missionLoadSequence.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId() ||
                    targetCampaignId != campaignId
                ) {
                    _uiState.update { it.copy(isMissionLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(isMissionLoading = false, missionError = friendlyError(e, localizer)) }
            }
        }
    }

    fun retryLoadMission() = loadMission()

    /** Dừng / tiếp tục nhiệm vụ của app này (chỉ chủ nhiệm vụ). */
    fun togglePauseResumeMission(onResult: (Boolean, String) -> Unit) {
        val mission = _uiState.value.mission ?: return
        if (_uiState.value.isMissionActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMissionActionLoading = true, error = null) }
            try {
                val result = if (mission.isPaused) {
                    repository.resumeMission(mission.id)
                } else {
                    repository.pauseMission(mission.id)
                }
                if (result.success) {
                    missionLoadSequence.incrementAndGet()
                    _uiState.update { state ->
                        state.copy(
                            isMissionActionLoading = false,
                            isMissionLoading = false,
                            mission = state.mission?.copy(status = result.status),
                        )
                    }
                    val msg = if (result.status == "paused_by_creator") {
                        localizer.get(R.string.mission_manage_paused_toast)
                    } else {
                        localizer.get(R.string.mission_manage_resumed_toast)
                    }
                    onResult(true, msg)
                } else if (result.isExpired) {
                    missionLoadSequence.incrementAndGet()
                    _uiState.update { state ->
                        state.copy(
                            isMissionActionLoading = false,
                            isMissionLoading = false,
                            mission = state.mission?.copy(status = "expired"),
                        )
                    }
                    onResult(false, localizer.get(R.string.mission_status_expired))
                } else {
                    _uiState.update { it.copy(isMissionActionLoading = false) }
                    val msg = result.error?.let { err -> missionActionError(Exception(err)) }
                        ?: result.message?.takeIf { m -> m.isNotBlank() }?.let { missionActionError(Exception(it)) }
                        ?: localizer.get(R.string.repo_connection_failed)
                    onResult(false, msg)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isMissionActionLoading = false) }
                onResult(false, missionActionError(e))
            }
        }
    }

    /** Xóa mềm nhiệm vụ (không hoàn phí); thành công gọi [onDeleted] để đóng UI. */
    fun deleteMission(onDeleted: () -> Unit) {
        val mission = _uiState.value.mission ?: return
        if (_uiState.value.isMissionActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMissionActionLoading = true, error = null) }
            try {
                repository.deleteMission(mission.id)
                missionLoadSequence.incrementAndGet()
                _uiState.update { it.copy(isMissionActionLoading = false, isMissionLoading = false, mission = null) }
                onDeleted()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isMissionActionLoading = false, error = missionActionError(e)) }
            }
        }
    }

    /** Xem trước chênh lệch chi phí khi sửa nhiệm vụ. */
    suspend fun previewEditMission(
        rewardMode: String,
        participants: Int,
        days: Int,
        rewardCredits: Int,
    ): com.ntt.crosstest.data.PreviewEditMissionResult? {
        val mission = _uiState.value.mission ?: return null
        return try {
            repository.previewEditMission(
                com.ntt.crosstest.data.PreviewEditMissionParams(
                    missionId = mission.id,
                    rewardMode = rewardMode,
                    maxParticipants = participants,
                    durationDays = days,
                    rewardCredits = rewardCredits,
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Chủ app cập nhật nhiệm vụ. */
    fun editMission(
        title: String,
        description: String,
        rewardMode: String? = null,
        participants: Int? = null,
        days: Int? = null,
        rewardCredits: Int? = null,
        announcement: String? = null,
        expectedVersion: Int? = null,
        expectedCostDiff: Int? = null,
        onSuccess: () -> Unit,
    ) {
        val mission = _uiState.value.mission ?: return
        if (_uiState.value.isMissionActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMissionActionLoading = true, error = null) }
            try {
                val params = com.ntt.crosstest.data.EditMissionParams(
                    missionId = mission.id,
                    title = title.trim(),
                    description = description.trim(),
                    rewardMode = rewardMode,
                    maxParticipants = participants,
                    durationDays = days,
                    rewardCredits = rewardCredits,
                    testerAnnouncement = announcement?.trim()?.ifBlank { null },
                    expectedVersion = expectedVersion ?: mission.version,
                    expectedCostDiff = expectedCostDiff,
                )
                val result = repository.editMission(params)
                if (result.success) {
                    _uiState.update { state ->
                        state.copy(
                            isMissionActionLoading = false,
                            message = localizer.get(R.string.mission_edit_success),
                        )
                    }
                    loadMission()
                    onSuccess()
                } else {
                    _uiState.update { it.copy(isMissionActionLoading = false) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = when {
                    e.message?.contains("CANNOT_MODIFY_TERMS_AFTER_JOIN") == true ->
                        localizer.get(R.string.mission_edit_terms_locked_notice)
                    e.message?.contains("CONCURRENT_MODIFICATION") == true ->
                        localizer.get(R.string.admin_owner_auto_pause_conflict)
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        localizer.get(R.string.mission_err_fee_format, expectedCostDiff ?: 0, _uiState.value.ownerProfile?.credits ?: 0)
                    else -> friendlyError(e, localizer)
                }
                _uiState.update { it.copy(isMissionActionLoading = false, error = msg) }
            }
        }
    }

    private fun missionActionError(e: Exception): String = when {
        e.message?.contains("MISSION_NOT_FOUND") == true ->
            localizer.get(R.string.mission_err_not_found)
        e.message?.contains("PERMISSION_DENIED") == true ->
            localizer.get(R.string.error_permission_denied)
        e.message?.contains("MISSION_EXPIRED") == true ->
            localizer.get(R.string.mission_status_expired)
        e.message?.contains("MISSION_NOT_PAUSED") == true ->
            localizer.get(R.string.mission_manage_not_paused)
        e.message?.contains("MISSION_NOT_OPEN") == true ->
            localizer.get(R.string.mission_status_stopped)
        e.message?.contains("CAMPAIGN_NOT_OPEN") == true ->
            localizer.get(R.string.mission_manage_campaign_closed)
        else -> friendlyError(e, localizer)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearReward() {
        _uiState.update { it.copy(reward = null) }
    }

    private val isPushActionInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Đối soát tự động (không tính phí) nếu đang có request ID pending lưu trên đĩa.
     */
    suspend fun reconcilePendingPushIfAny(context: Context) {
        val userId = repository.currentUserId() ?: return
        val pendingRequestId = CampaignPushStore.peekPendingRequestId(context, userId, campaignId) ?: return
        val expectedUid = userId
        val expectedGen = SessionCacheManager.currentGeneration()

        try {
            _uiState.update { it.copy(hasPendingPush = true, isPushVerifying = true) }
            val recon = repository.reconcilePushReceipt(campaignId, pendingRequestId)
            val currentUid = repository.currentUserId()
            val currentGen = SessionCacheManager.currentGeneration()
            if (currentUid != expectedUid || currentGen != expectedGen) return

            if (recon.found) {
                CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                _uiState.update { state ->
                    val updatedCamp = (state.campaign ?: Campaign(id = campaignId)).copy(
                        pushBusinessDate = recon.businessDate,
                        pushExpiresAt = recon.expiresAt,
                        pushOrder = recon.pushOrder
                    )
                    val updatedProfile = (state.ownerProfile ?: Profile(id = expectedUid, credits = recon.currentCredits)).copy(
                        credits = recon.currentCredits
                    )
                    state.copy(
                        campaign = updatedCamp,
                        ownerProfile = updatedProfile,
                        hasPendingPush = false,
                        isPushVerifying = false,
                        isPushPendingNotFound = false
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        hasPendingPush = true,
                        isPushVerifying = false,
                        isPushPendingNotFound = true
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val currentUid = repository.currentUserId()
            val currentGen = SessionCacheManager.currentGeneration()
            if (currentUid != expectedUid || currentGen != expectedGen) return
            _uiState.update { it.copy(isPushVerifying = false) }
        }
    }

    /**
     * Mua lượt Đẩy app (RPC push_campaign_v2) hoặc đối soát giao dịch đang chờ (migration 066).
     * Chỉ gửi yêu cầu sau khi người dùng đã xác nhận trong hộp thoại.
     */
    fun pushApp(
        context: Context,
        expectedCost: Int? = null,
        expectedRevision: String? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        // Khóa đồng bộ trong ViewModel ngay trước khi launch coroutine,
        // ngăn chặn việc double-tap tạo 2 UUID hoặc gửi song song 2 request.
        if (!isPushActionInProgress.compareAndSet(false, true)) {
            return
        }

        val userId = repository.currentUserId()
        if (userId == null) {
            isPushActionInProgress.set(false)
            _uiState.update {
                it.copy(
                    isPushLoading = false,
                    pushError = localizer.get(R.string.error_permission_denied)
                )
            }
            return
        }

        val expectedUid = userId
        val expectedGen = SessionCacheManager.currentGeneration()
        val cost = expectedCost ?: _uiState.value.pushCost
        val revision = expectedRevision ?: _uiState.value.pushRevision

        // R06: Tách mua mới khỏi xác minh/replay request cũ
        val pendingRequestId = CampaignPushStore.peekPendingRequestId(context, userId, campaignId)
        val isResolvingPending = pendingRequestId != null

        val currentCamp = _uiState.value.campaign
        if (currentCamp == null && !isResolvingPending) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.oad_not_found)) }
            return
        }

        // Chỉ kiểm tra điều kiện mua mới (không paused, credits >= cost) khi ĐÂY LÀ MUA MỚI
        if (!isResolvingPending) {
            if (currentCamp?.isPaused == true) {
                isPushActionInProgress.set(false)
                _uiState.update {
                    it.copy(
                        isPushLoading = false,
                        pushError = localizer.get(R.string.oad_push_paused_warning)
                    )
                }
                return
            }

            val currentCredits = _uiState.value.ownerProfile?.credits ?: 0
            if (currentCredits < cost) {
                isPushActionInProgress.set(false)
                _uiState.update {
                    it.copy(
                        isPushLoading = false,
                        pushError = localizer.get(R.string.oad_push_insufficient_credits)
                    )
                }
                return
            }
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isPushLoading = true, pushError = null) }

                // Sử dụng lại request ID cũ nếu đang giải quyết pending, hoặc tạo mới có commit bền vững
                val requestId = pendingRequestId ?: CampaignPushStore.getOrCreatePendingRequestId(context, expectedUid, campaignId)

                if (isResolvingPending) {
                    // Đối soát chỉ đọc: không trừ tiền, không ràng buộc số dư hay campaign state
                    val recon = repository.reconcilePushReceipt(campaignId, requestId)

                    // Kiểm tra phiên và generation ngay lập tức sau khi await reconcile
                    val currentUid = repository.currentUserId()
                    val currentGen = SessionCacheManager.currentGeneration()
                    if (currentUid != expectedUid || currentGen != expectedGen) {
                        return@launch
                    }

                    if (recon.found) {
                        CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)

                        val successMessage = localizer.get(R.string.oad_push_replayed_success)
                        _uiState.update { state ->
                            val updatedCamp = (state.campaign ?: Campaign(id = campaignId)).copy(
                                pushBusinessDate = recon.businessDate,
                                pushExpiresAt = recon.expiresAt,
                                pushOrder = recon.pushOrder
                            )
                            val updatedProfile = (state.ownerProfile ?: Profile(id = expectedUid, credits = recon.currentCredits)).copy(
                                credits = recon.currentCredits
                            )
                            state.copy(
                                isPushLoading = false,
                                hasPendingPush = false,
                                isPushVerifying = false,
                                isPushPendingNotFound = false,
                                campaign = updatedCamp,
                                ownerProfile = updatedProfile,
                                message = successMessage,
                                pushError = null
                            )
                        }

                        onSuccess?.invoke()
                        refresh(forceRefresh = true)
                        return@launch
                    } else {
                        // Không tìm thấy biên lai trên server: KHÔNG tự động mua mới, KHÔNG trừ tiền!
                        // Giữ lại pendingRequestId cho người dùng quyết định thử lại rõ ràng.
                        _uiState.update { state ->
                            state.copy(
                                isPushLoading = false,
                                hasPendingPush = true,
                                isPushVerifying = false,
                                isPushPendingNotFound = true,
                                pushError = localizer.get(R.string.oad_push_retry_later_unbilled)
                            )
                        }
                        return@launch
                    }
                }

                // Trường hợp mua mới: Gọi push_campaign_v2 với expectedCost và expectedRevision
                val result = repository.pushCampaignV2(
                    campaignId = campaignId,
                    clientRequestId = requestId,
                    expectedCost = cost,
                    expectedRevision = revision,
                )

                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != expectedUid || currentGen != expectedGen) {
                    return@launch
                }

                CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)

                val successMessage = if (result.replayed) {
                    localizer.get(R.string.oad_push_replayed_success)
                } else {
                    localizer.get(R.string.oad_push_success)
                }

                _uiState.update { state ->
                    val updatedCamp = state.campaign?.copy(
                        pushBusinessDate = result.businessDate,
                        pushExpiresAt = result.expiresAt,
                        pushOrder = result.pushOrder
                    )
                    val updatedProfile = state.ownerProfile?.copy(
                        credits = result.balanceAfter
                    )
                    state.copy(
                        isPushLoading = false,
                        hasPendingPush = false,
                        isPushVerifying = false,
                        isPushPendingNotFound = false,
                        campaign = updatedCamp,
                        ownerProfile = updatedProfile,
                        message = successMessage,
                        pushError = null
                    )
                }

                onSuccess?.invoke()
                refresh(forceRefresh = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                // R05: Kiểm tra phiên & generation trước khi can thiệp vào bất kỳ state hay store nào
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != expectedUid || currentGen != expectedGen) {
                    return@launch
                }

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true

                if (isConflict) {
                    CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                    refresh(forceRefresh = true)
                    _uiState.update {
                        it.copy(
                            isPushLoading = false,
                            hasPendingPush = false,
                            pushError = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    if (isServerRejection(e)) {
                        CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                    }
                    _uiState.update {
                        it.copy(
                            isPushLoading = false,
                            pushError = friendlyPushError(e)
                        )
                    }
                }
            } finally {
                isPushActionInProgress.set(false)
            }
        }
    }

    /**
     * Mua lại có tính phí rõ ràng sử dụng lại chính xác request ID đã xác nhận trước đó.
     */
    fun retryOriginalPurchase(
        context: Context,
        expectedCost: Int? = null,
        expectedRevision: String? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (!isPushActionInProgress.compareAndSet(false, true)) return

        val userId = repository.currentUserId()
        if (userId == null) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.error_permission_denied)) }
            return
        }

        val expectedUid = userId
        val expectedGen = SessionCacheManager.currentGeneration()
        val cost = expectedCost ?: _uiState.value.pushCost
        val revision = expectedRevision ?: _uiState.value.pushRevision

        val pendingRequestId = CampaignPushStore.peekPendingRequestId(context, userId, campaignId)
        if (pendingRequestId == null) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.oad_push_no_pending_request)) }
            return
        }

        val currentCamp = _uiState.value.campaign
        if (currentCamp == null) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.oad_not_found)) }
            return
        }
        if (currentCamp.isPaused) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.oad_push_paused_warning)) }
            return
        }
        val currentCredits = _uiState.value.ownerProfile?.credits ?: 0
        if (currentCredits < cost) {
            isPushActionInProgress.set(false)
            _uiState.update { it.copy(isPushLoading = false, pushError = localizer.get(R.string.oad_push_insufficient_credits)) }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isPushLoading = true, pushError = null) }
                val requestId = pendingRequestId
                val result = repository.pushCampaignV2(
                    campaignId = campaignId,
                    clientRequestId = requestId,
                    expectedCost = cost,
                    expectedRevision = revision,
                )

                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != expectedUid || currentGen != expectedGen) return@launch

                CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                val successMessage = if (result.replayed) {
                    localizer.get(R.string.oad_push_replayed_success)
                } else {
                    localizer.get(R.string.oad_push_success)
                }

                _uiState.update { state ->
                    val updatedCamp = state.campaign?.copy(
                        pushBusinessDate = result.businessDate,
                        pushExpiresAt = result.expiresAt,
                        pushOrder = result.pushOrder
                    )
                    val updatedProfile = state.ownerProfile?.copy(
                        credits = result.balanceAfter
                    )
                    state.copy(
                        isPushLoading = false,
                        hasPendingPush = false,
                        isPushPendingNotFound = false,
                        campaign = updatedCamp,
                        ownerProfile = updatedProfile,
                        message = successMessage,
                        pushError = null
                    )
                }

                onSuccess?.invoke()
                refresh(forceRefresh = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != expectedUid || currentGen != expectedGen) return@launch

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true

                if (isConflict) {
                    CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                    refresh(forceRefresh = true)
                    _uiState.update {
                        it.copy(
                            isPushLoading = false,
                            hasPendingPush = false,
                            pushError = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    if (isServerRejection(e)) {
                        CampaignPushStore.clearPendingRequestId(context, expectedUid, campaignId)
                        _uiState.update { it.copy(hasPendingPush = false, isPushPendingNotFound = false) }
                    }
                    _uiState.update {
                        it.copy(
                            isPushLoading = false,
                            pushError = friendlyPushError(e)
                        )
                    }
                }
            } finally {
                isPushActionInProgress.set(false)
            }
        }
    }

    private fun isServerRejection(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return msg.contains("push_metadata_read_only") ||
                msg.contains("insufficient_credits") ||
                msg.contains("campaign_not_open") ||
                msg.contains("campaign_window_expired") ||
                msg.contains("slot_expired") ||
                msg.contains("not_owner") ||
                msg.contains("account_locked") ||
                msg.contains("user_locked") ||
                msg.contains("not_authenticated") ||
                msg.contains("invalid_request_id") ||
                msg.contains("invalid_campaign_id") ||
                msg.contains("request_id_conflict") ||
                msg.contains("profile_not_found") ||
                msg.contains("campaign_not_found")
    }

    private fun friendlyPushError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("PUSH_METADATA_READ_ONLY") ->
                localizer.get(R.string.oad_push_retry_later_unbilled)
            msg.contains("INSUFFICIENT_CREDITS") ->
                localizer.get(R.string.oad_push_insufficient_credits)
            msg.contains("CAMPAIGN_NOT_OPEN") ->
                localizer.get(R.string.oad_push_paused_warning)
            msg.contains("CAMPAIGN_WINDOW_EXPIRED") ->
                localizer.get(R.string.campaign_admin_error_finalized)
            msg.contains("SLOT_EXPIRED") ->
                localizer.get(R.string.oad_paused_slot_desc, _uiState.value.campaign?.slotNumber ?: 1)
            msg.contains("NOT_OWNER") ->
                localizer.get(R.string.error_permission_denied)
            msg.contains("ACCOUNT_LOCKED") || msg.contains("USER_LOCKED") ->
                localizer.get(R.string.error_account_locked)
            else -> friendlyError(e as? Exception ?: Exception(sanitizeErrorMessage(msg)), localizer)
        }
    }

    fun clearPushError() {
        _uiState.update { it.copy(pushError = null) }
    }
}
