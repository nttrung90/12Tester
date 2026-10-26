package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignDetailResult
import com.ntt.crosstest.data.CampaignMember
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.data.TesterItem
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.ui.components.CreditReward
import com.ntt.crosstest.ui.components.CreditRewardType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OwnerAppDetailUiState(
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
    val error: String? = null,
    val message: String? = null,
    val warningMessage: String? = null,
    val isOwnerLocked: Boolean = false,
    /** Admin đang mở màn quản lý app của chủ khác. */
    val isAdmin: Boolean = false,
    /** Người xem đúng là chủ app (Admin xem app người khác = false) — điều kiện thấy mục Quản lý nhiệm vụ. */
    val isActualOwner: Boolean = false,
    /** Nhiệm vụ hiện tại của app (mục "Quản lý nhiệm vụ", migration 040). */
    val mission: Mission? = null,
    val isMissionActionLoading: Boolean = false,
    /** Reward overlay shown inside [TesterReportsDialog]. */
    val reward: CreditReward? = null,
)

class OwnerAppDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OwnerAppDetailUiState())
    val uiState: StateFlow<OwnerAppDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Nạp cache trước (best-effort): lỗi mạng ở đây không được làm crash màn,
            // refresh() phía dưới sẽ tải lại và báo lỗi qua UI state nếu cần.
            runCatching {
                val cachedCamp = repository.getCampaign(campaignId, forceRefresh = false)
                val cachedStats = repository.getCampaignOwnerStats(campaignId, forceRefresh = false)
                val cachedReports = repository.getCampaignReports(campaignId, canManage = true, forceRefresh = false)
                val cachedTesters = repository.getCampaignTesters(campaignId, forceRefresh = false)
                if (cachedCamp != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = true,
                            campaign = cachedCamp,
                            stats = cachedStats,
                            reports = cachedReports,
                            testers = cachedTesters,
                        )
                    }
                }
            }
            refresh(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        loadMission()
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
                    applyDetail(detail, forceRefresh)
                    return@launch
                }
                val camp = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                val stats = repository.getCampaignOwnerStats(campaignId, forceRefresh = forceRefresh)
                val testers = repository.getCampaignTesters(campaignId, forceRefresh = forceRefresh)
                val reports = repository.getCampaignReports(campaignId, canManage = true, forceRefresh = forceRefresh)
                val myProfile = repository.getMyProfile(forceRefresh = forceRefresh)
                val owner = resolveOwnerProfile(
                    ownerId = camp?.ownerId,
                    fetched = camp?.ownerId?.let { id ->
                        repository.getProfiles(listOf(id), forceRefresh = forceRefresh)[id]
                    },
                    myProfile = myProfile,
                )
                val slots = try { repository.getMySlotsInfo(forceRefresh = forceRefresh) } catch (_: Exception) { emptyList() }
                val slot = slots.find { it.slotNumber == camp?.slotNumber }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = camp,
                        ownerProfile = owner,
                        slotInfo = slot,
                        stats = stats,
                        testers = testers,
                        reports = reports,
                        isAdmin = myProfile?.isAdmin == true,
                        isActualOwner = camp?.ownerId != null &&
                            camp.ownerId == repository.currentUserId(),
                        error = null,
                    )
                }
            } catch (e: Exception) {
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
    private suspend fun applyDetail(detail: CampaignDetailResult, forceRefresh: Boolean) {
        val camp = detail.campaign
        val stats = CampaignOwnerStats(
            installedTestersCount = detail.testers.size,
            testedTodayCount = detail.todayTestedCount,
            totalReportsCount = detail.reportsCount,
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
        val slot = slots.find { it.slotNumber == camp.slotNumber }

        _uiState.update {
            it.copy(
                isLoading = false,
                isBackgroundSyncing = false,
                campaign = camp,
                ownerProfile = owner,
                slotInfo = slot,
                stats = stats,
                testers = testers,
                reports = reports,
                isAdmin = detail.myProfile?.isAdmin == true,
                isActualOwner = detail.isActualOwner,
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
                    // Chủ app: RPC kiểm tra cửa sổ 30 ngày + chủ đã hoạt động lại.
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
                if (e.message?.contains("CAMPAIGN_WINDOW_EXPIRED") == true) refresh()
            }
        }
    }

    fun renewCurrentSlot() {
        val camp = _uiState.value.campaign ?: return
        if (camp.slotNumber <= 1) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.unlockOrRenewSlot(camp.slotNumber)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = localizer.get(R.string.owner_slot_renewed_format, camp.slotNumber)
                    )
                }
                refresh()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        localizer.get(R.string.owner_renew_insufficient_format, SlotInfo.costForSlot(camp.slotNumber))
                    else -> friendlyError(e, localizer)
                }
                _uiState.update { it.copy(isActionLoading = false, error = msg) }
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
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
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
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun deleteCampaign(onDeleted: () -> Unit) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.deleteCampaign(camp.id.orEmpty())
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
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
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
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
                            warningMessage = result.ownerWarningMsg ?: localizer.get(R.string.owner_approve_warning_fallback),
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

    fun clearWarning() {
        _uiState.update { it.copy(warningMessage = null) }
    }

    /**
     * Nhiệm vụ hiện tại của app cho mục "Quản lý nhiệm vụ". Server chưa chạy
     * migration 040 thì RPC lỗi — bỏ qua im lặng, giữ nguyên giá trị cũ.
     */
    private fun loadMission() {
        viewModelScope.launch {
            try {
                val mission = repository.getCampaignMission(campaignId)
                _uiState.update { it.copy(mission = mission) }
            } catch (_: Exception) {
            }
        }
    }

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
                    _uiState.update { state ->
                        state.copy(
                            isMissionActionLoading = false,
                            mission = state.mission?.copy(status = result.status),
                        )
                    }
                    val msg = if (result.status == "paused_by_creator") {
                        localizer.get(R.string.mission_manage_paused_toast)
                    } else {
                        localizer.get(R.string.mission_manage_resumed_toast)
                    }
                    onResult(true, msg)
                } else {
                    _uiState.update { it.copy(isMissionActionLoading = false) }
                    onResult(false, localizer.get(R.string.repo_connection_failed))
                }
            } catch (e: Exception) {
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
                _uiState.update { it.copy(isMissionActionLoading = false, mission = null) }
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(isMissionActionLoading = false, error = missionActionError(e)) }
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
}
