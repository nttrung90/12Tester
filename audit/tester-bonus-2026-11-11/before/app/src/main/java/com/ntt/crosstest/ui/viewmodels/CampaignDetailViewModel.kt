package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.CampaignDetailResult
import com.ntt.crosstest.data.CampaignMember
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.Feedback
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.EgressTriggerSource
import com.ntt.crosstest.data.NextCampaignCandidate
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.util.InstalledAppCache
import com.ntt.crosstest.util.NextCampaignSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.data.UserWarning
import com.ntt.crosstest.ui.components.CreditReward
import com.ntt.crosstest.ui.components.CreditRewardType
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.InstalledAppVersionReader
import com.ntt.crosstest.util.InstalledVersionResult
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.util.VnTime
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

data class MemberProgress(
    val member: CampaignMember,
    val profile: Profile?,
    /** Number of distinct days this tester has checked in. */
    val days: Int,
)

data class FeedbackItem(
    val feedback: Feedback,
    val profile: Profile?,
)

data class CampaignDetailUiState(
    val isLoading: Boolean = true,
    val isBackgroundSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val campaign: Campaign? = null,
    val ownerProfile: Profile? = null,
    val isMember: Boolean = false,
    /** Chủ app thật (is_actual_owner). */
    val isOwner: Boolean = false,
    /** Owner/admin/moderator: dùng cho dữ liệu báo cáo; không dùng cho banner quản lý. */
    val canManage: Boolean = false,
    val checkedInToday: Boolean = false,
    val members: List<MemberProgress> = emptyList(),
    val feedbacks: List<FeedbackItem> = emptyList(),
    val todayTestedCount: Int = 0,
    val reportsCount: Int = 0,
    val avgRating: Float = 0f,
    val myCheckinDates: List<String> = emptyList(),
    val myRating: Int = 5,
    val myComment: String = "",
    /** Người dùng đã tự chỉnh trong phiên này chưa — tránh refresh nền ghi đè. */
    val ratingTouched: Boolean = false,
    val commentTouched: Boolean = false,
    val selectedScreenshotUri: Uri? = null,
    /** Ảnh đã nén + hash, dùng chung cho preview, check trùng và upload. */
    val preparedScreenshot: PreparedScreenshot? = null,
    val isPreparingScreenshot: Boolean = false,
    val isActionLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val myLatestReport: TesterReport? = null,
    val myTodayReport: TesterReport? = null,
    val myReports: List<TesterReport> = emptyList(),
    val unreadWarning: UserWarning? = null,
    val weeklyWarningsCount: Int = 0,
    val isUserLocked: Boolean = false,
    val isAdmin: Boolean = false,
    val currentUserId: String? = null,
    val sessionGeneration: Long = SessionCacheManager.currentGeneration(),
    val testStartedAtMs: Long? = null,
    val showNextAppPrompt: Boolean = false,
    val nextCampaignToTest: Campaign? = null,
    val nextSuggestionLoading: Boolean = false,
    val nextSuggestionFailed: Boolean = false,
    val submitStage: ReportSubmitManager.Stage? = null,
    val submitAttempt: Int = 0,
    val submitError: String? = null,
    /** Remote approval banner (pending → approved for the same report in this session). */
    val reward: CreditReward? = null,
    /** Nhiệm vụ app đang áp dụng (nếu có và tester đã nhận/hợp lệ). */
    val activeMission: Mission? = null,
    /** Nhiệm vụ độc lập cho Admin quản trị/xóa (không ảnh hưởng context nộp báo cáo thường). */
    val adminManageableMission: Mission? = null,
    val isMissionReport: Boolean = false,
    val missionDeadlinePassed: Boolean = false,
    val intendedMissionId: String? = null,
    val missionContextError: String? = null,
    val appUpdate: com.ntt.crosstest.data.AppUpdateRelease? = null,
    val installedVersionCode: Long? = null,
    val installedVersionName: String? = null,
    val isCheckingInstalledVersion: Boolean = false,
    val isClaimingUpdateReward: Boolean = false,
    val updateClaimSuccessMessage: String? = null,
    val updateClaimError: String? = null,
) {
    val isAppUpdated: Boolean
        get() {
            val target = appUpdate?.targetVersionCode ?: return false
            val installed = installedVersionCode ?: return false
            return installed >= target
        }

    val showAppUpdateBlock: Boolean
        get() {
            if (isOwner) return false
            val rel = appUpdate ?: return false
            if (!rel.isEligible) return false
            if (campaign?.status != null && campaign.status != "open") return false
            if (rel.isExhausted) return false
            if (rel.status != "active") return false
            if (rel.paidCount >= rel.maxClaims) return false
            if (rel.remainingCredits < rel.rewardPerClaim) return false
            return true
        }
}

class CampaignDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
    private val reportSubmitManager: ReportSubmitManager,
    private val myUserId: String?,
    private val localizer: Localizer,
    private val appContext: Context? = null,
    private val initialMissionId: String? = null,
) : ViewModel() {

    private val adminActionLock = Any()
    private val adminActionSequence = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeAdminToken: Long = 0L
    @Volatile private var activeAdminUid: String? = null
    @Volatile private var activeAdminGen: Long = 0L

    private val refreshSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val _uiState = MutableStateFlow(
        CampaignDetailUiState(
            intendedMissionId = initialMissionId,
            isMissionReport = initialMissionId != null,
        )
    )
    val uiState: StateFlow<CampaignDetailUiState> = _uiState.asStateFlow()

    /**
     * Snapshot trạng thái "chưa từng test" gắn với userId và generation lúc khởi tạo / đăng nhập.
     * Chốt trạng thái trước khi bài nộp đầu tiên hoàn tất.
     */
    private data class FirstTimeTesterSnapshot(
        val userId: String,
        val generation: Long,
        val deferred: kotlinx.coroutines.Deferred<Boolean>,
    )

    private var activeFirstTimeTester: FirstTimeTesterSnapshot? = null

    private fun eagerFirstTimeTester(userId: String? = null): kotlinx.coroutines.Deferred<Boolean> {
        val uid = userId ?: effectiveUserId()
        val gen = SessionCacheManager.currentGeneration()
        val current = activeFirstTimeTester
        if (current != null && current.userId == uid && current.generation == gen) {
            return current.deferred
        }
        val deferred = viewModelScope.async {
            if (uid == null) return@async false
            val hasPendingUpload = appContext?.let { ctx ->
                try {
                    withContext(Dispatchers.IO) {
                        PendingReportStore.pendingCampaignIds(ctx).isNotEmpty()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } ?: false
            if (hasPendingUpload) return@async false
            try {
                !repository.hasTestedBefore()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
        if (uid != null) {
            activeFirstTimeTester = FirstTimeTesterSnapshot(uid, gen, deferred)
        }
        return deferred
    }

    /**
     * Đã nộp xong ít nhất một báo cáo trong ViewModel này.
     * Chỉ lần gợi ý của bài nộp ĐẦU TIÊN mới được hưởng ưu tiên lượt cài thấp;
     * các lần nộp cập nhật sau đó (dù vẫn ở cùng màn) dùng quy tắc thường.
     */
    internal var testedInThisViewModel = false

    internal var nextCampaignRandomPicker: (List<NextCampaignCandidate>) -> NextCampaignCandidate? = { it.randomOrNull() }

    /**
     * [myUserId] được chốt một lần lúc tạo ViewModel; nếu lúc đó phiên còn đang
     * khôi phục (Initializing) thì nó là null mãi. Đọc lại uid hiện tại từ
     * repository để không kẹt trạng thái "chưa đăng nhập" sau khi session sẵn sàng.
     */
    private fun effectiveUserId(): String? {
        if (repository.sessionStatus.value is SessionStatus.NotAuthenticated) {
            return null
        }
        return repository.currentUserId() ?: myUserId
    }

    /** Chỉ giữ 1 tác vụ nén ảnh tại một thời điểm (user đổi ảnh liên tục). */
    private var prepareScreenshotJob: Job? = null

    /**
     * Trạng thái báo cáo của tester đã thấy trong phiên này, theo report id.
     * Dùng để chỉ hiện banner "+2" khi đúng báo cáo đó chuyển pending → approved,
     * không hiện lại cho báo cáo đã duyệt từ trước.
     */
    private val observedReportStatuses = mutableMapOf<String, String>()
    private var reportBaselineCaptured = false

    /** Thông báo thành công tương ứng trạng thái nộp (extra/update/mới) — hiển thị khi ở lại màn. */
    private var pendingSuccessMessage: String? = null

    /**
     * refresh() đã bỏ dở vì phiên còn đang khôi phục (Initializing/RefreshFailure).
     * Khi phiên sẵn sàng trở lại, observer sessionStatus refresh đúng một lần —
     * tránh gửi request ẩn danh (không JWT) làm sai trạng thái thành viên/check-in.
     */
    private var refreshWhenSessionReady = false
    private var lastSubmittedRequestId: String? = null
    private var submittedCommentSnapshot: String? = null
    private var submittedRatingSnapshot: Int? = null
    private var submittedPreparedPath: String? = null
    private var activeLeasedPhotoPath: String? = null

    val adminPauseDialogController = AdminPauseDialogController { SessionCacheManager.currentGeneration() }
    val adminPauseDialogState: StateFlow<AdminPauseDialogState> = adminPauseDialogController.state

    fun openAdminPauseDialog() {
        val uid = effectiveUserId()
        val gen = SessionCacheManager.currentGeneration()
        _uiState.update { it.copy(currentUserId = uid, sessionGeneration = gen) }
        adminPauseDialogController.open(uid, gen, _uiState.value.isAdmin)
    }

    fun dismissAdminPauseDialog() {
        adminPauseDialogController.dismiss()
    }

    fun setAdminPauseReasonCode(code: String) {
        adminPauseDialogController.setReasonCode(code)
    }

    fun setAdminPauseCustomReason(reason: String) {
        adminPauseDialogController.setCustomReason(reason)
    }

    private fun updatePhotoLease(newPath: String?) {
        if (activeLeasedPhotoPath != newPath) {
            PreparedScreenshotCache.releaseLease(activeLeasedPhotoPath)
            activeLeasedPhotoPath = newPath
            PreparedScreenshotCache.acquireLease(newPath)
        }
    }

    init {
        eagerFirstTimeTester(effectiveUserId())
        adminPauseDialogController.syncIdentity(
            userId = effectiveUserId(),
            generation = SessionCacheManager.currentGeneration(),
            isAdmin = _uiState.value.isAdmin,
        )
        viewModelScope.launch {
            _uiState.map { it.preparedScreenshot?.file?.absolutePath }
                .distinctUntilChanged()
                .collect { path ->
                    updatePhotoLease(path)
                }
        }
        viewModelScope.launch {
            repository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val newUid = repository.currentUserId()
                        val newGen = SessionCacheManager.currentGeneration()
                        eagerFirstTimeTester(newUid)
                        _uiState.update { it.copy(currentUserId = newUid, sessionGeneration = newGen) }
                        resetOldAdminActionIfSessionChanged()
                        adminPauseDialogController.syncIdentity(newUid, newGen, _uiState.value.isAdmin)
                        if (refreshWhenSessionReady) {
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val newGen = SessionCacheManager.currentGeneration()
                        _uiState.update {
                            it.copy(
                                currentUserId = null,
                                sessionGeneration = newGen,
                                isAdmin = false,
                                isActionLoading = false,
                            )
                        }
                        synchronized(adminActionLock) {
                            if (activeAdminToken != 0L) {
                                activeAdminToken = 0L
                                activeAdminUid = null
                                activeAdminGen = 0L
                            }
                        }
                        adminPauseDialogController.syncIdentity(null, newGen, false)
                        refreshWhenSessionReady = false
                    }
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            reportSubmitManager.stateForCampaign(campaignId).collect { submitState ->
                if (submitState == null || submitState.campaignId != campaignId) return@collect
                if (initialMissionId == null && submitState.isMissionReport) return@collect
                if (initialMissionId != null && submitState.missionId != initialMissionId) return@collect
                if (initialMissionId == null && submitState.scopedKey != null && !submitState.scopedKey.endsWith(":${VnTime.todayString()}")) return@collect
                val previousStage = _uiState.value.submitStage
                _uiState.update {
                    it.copy(
                        submitStage = submitState.stage,
                        submitAttempt = submitState.attempt,
                        submitError = if (submitState.stage == ReportSubmitManager.Stage.FAILED) {
                            friendlyError(submitState.error ?: Exception(), localizer)
                        } else {
                            null
                        },
                    )
                }
                if (submitState.stage == ReportSubmitManager.Stage.DONE &&
                    (previousStage == ReportSubmitManager.Stage.SENDING ||
                        previousStage == ReportSubmitManager.Stage.RETRYING)
                ) {
                    testedInThisViewModel = true
                    // Thông báo theo kết quả THẬT từ server: 10 Credit cho báo cáo mới,
                    // 2 Credit cho báo cáo cũ, hoặc 0 nếu đã có checkin xác nhận hôm nay.
                    val submitResult = submitState.result
                    val successMessage = when {
                        submitResult == null -> pendingSuccessMessage
                        submitResult.status == "approved" ->
                            localizer.get(R.string.campaign_report_extra_success)
                        submitResult.provisionalGranted && submitResult.provisionalAmount > 0 ->
                            localizer.get(R.string.campaign_report_submit_success_format, submitResult.provisionalAmount)
                        else -> localizer.get(R.string.campaign_report_update_success)
                    }
                    // The global overlay already communicates the provisional
                    // Credits; don't repeat the same copy in a snackbar.
                    val hasCreditOverlay = submitResult != null &&
                        submitResult.provisionalGranted &&
                        submitResult.provisionalAmount > 0
                    val isMyRequest = lastSubmittedRequestId != null && submitState.requestId == lastSubmittedRequestId
                    if (isMyRequest) {
                        testedInThisViewModel = true
                        val snapshotComment = submittedCommentSnapshot
                        val snapshotRating = submittedRatingSnapshot
                        val snapshotPhotoPath = submittedPreparedPath

                        _uiState.update { state ->
                            val commentMatches = state.myComment == snapshotComment
                            val ratingMatches = state.myRating == snapshotRating
                            val photoMatches = state.preparedScreenshot?.file?.absolutePath == snapshotPhotoPath
                            val draftUnmodified = commentMatches && ratingMatches && photoMatches

                            if (draftUnmodified) {
                                // Toàn bộ bản nháp đã nộp thành công và không bị sửa đổi -> xóa file prepared và reset form
                                state.preparedScreenshot?.file?.delete()
                                state.copy(
                                    myComment = "",
                                    commentTouched = false,
                                    ratingTouched = false,
                                    selectedScreenshotUri = null,
                                    preparedScreenshot = null,
                                    isPreparingScreenshot = false,
                                    // Dialog đang mở thì đã có dòng trạng thái "đã gửi" — khỏi snackbar trùng.
                                    message = if (state.showNextAppPrompt) {
                                        state.message
                                    } else if (hasCreditOverlay) {
                                        null
                                    } else {
                                        successMessage
                                    },
                                )
                            } else {
                                // Người dùng đã chỉnh sửa bản nháp (comment, rating, hoặc ảnh) trong lúc gửi ->
                                // GIỮ NGUYÊN TOÀN BỘ BẢN NHÁP MỚI (bao gồm cả ảnh, file ảnh và URI đang chọn)!
                                state.copy(
                                    message = if (state.showNextAppPrompt) {
                                        state.message
                                    } else if (hasCreditOverlay) {
                                        null
                                    } else {
                                        successMessage
                                    },
                                )
                            }
                        }
                        lastSubmittedRequestId = null
                        submittedCommentSnapshot = null
                        submittedRatingSnapshot = null
                        submittedPreparedPath = null
                    }
                    refresh(triggerSource = EgressTriggerSource.MUTATION)
                }
            }
        }
        viewModelScope.launch {
            if (isSessionRestoring()) {
                refreshWhenSessionReady = true
                return@launch
            }
            val cached = repository.peekCampaign(campaignId)
            if (cached != null) {
                val isOwner = effectiveUserId() != null && cached.ownerId == effectiveUserId()
                _uiState.update {
                    it.copy(
                        campaign = cached,
                        isOwner = isOwner,
                        canManage = isOwner,
                        isLoading = false,
                        isBackgroundSyncing = true,
                    )
                }
            }
            refresh(forceRefresh = false, triggerSource = EgressTriggerSource.COLD_START)
        }
    }

    /** True khi phiên chưa sẵn sàng (đang khôi phục hoặc refresh tạm thời thất bại). */
    private fun isSessionRestoring(): Boolean {
        val status = repository.sessionStatus.value
        return status is SessionStatus.Initializing || status is SessionStatus.RefreshFailure
    }

    fun refresh(
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ) {
        val seq = refreshSequence.incrementAndGet()
        val initialUid = effectiveUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        resetOldAdminActionIfSessionChanged()
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.campaign == null) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                // Phiên có thể đang khôi phục (Initializing) hoặc refresh tạm lỗi
                // (RefreshFailure) → currentUserId()==null. Bỏ qua lần này để không
                // gửi request ẩn danh; observer sessionStatus sẽ refresh lại khi sẵn sàng.
                if (isSessionRestoring()) {
                    refreshWhenSessionReady = true
                    if (_uiState.value.campaign != null) {
                        _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false) }
                    }
                    return@launch
                }
                // Fast path: 1 RPC gộp toàn bộ dữ liệu màn chi tiết (migration 020).
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = forceRefresh, triggerSource = triggerSource)
                if (detail != null) {
                    if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != effectiveUserId()) {
                        return@launch
                    }
                    val todayReport = if (effectiveUserId() != null) {
                        val userTodayReports = detail.reports.filter { it.isCreatedToday() && it.userId == effectiveUserId() }
                        if (initialMissionId == null) {
                            userTodayReports.firstOrNull { !it.isMissionReport }
                        } else {
                            userTodayReports.firstOrNull { it.isMissionReport && it.missionId == initialMissionId }
                                ?: userTodayReports.firstOrNull()
                        }
                    } else null
                    val res = resolveMissionContext(todayReport)
                    if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != effectiveUserId()) {
                        return@launch
                    }
                    val adminMission = if (detail.myProfile?.isAdmin == true) {
                        try { repository.getCampaignMission(campaignId) } catch (_: Exception) { null }
                    } else null
                    applyDetail(detail, res.mission, res.isMission, res.deadlinePassed, res.intendedMissionId, res.contextError, adminMission)
                    return@launch
                }
                val campaign = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                if (campaign == null) {
                    _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false, error = localizer.get(R.string.campaign_detail_not_found)) }
                    return@launch
                }
                val uid = effectiveUserId()
                val isMember = if (uid != null) repository.isMember(campaignId, uid) else false
                // Chỉ coi là "đã check-in" khi check-in đã chính thức (được duyệt), KHÔNG tính
                // dòng check-in tạm tính tạo lúc nộp báo cáo còn chờ chủ app duyệt.
                val checkedIn = if (isMember && uid != null) repository.hasConfirmedCheckinToday(campaignId, uid) else false
                val isOwner = uid != null && campaign.ownerId == uid
                val myProfile = if (uid != null) repository.getMyProfile(forceRefresh = forceRefresh) else null
                val canManage = isOwner || myProfile?.canModerate == true

                val members = repository.getMembers(campaignId)
                val checkins = repository.getCheckins(campaignId)
                val today = VnTime.today().toString()
                val reports = repository.getCampaignReports(campaignId, canManage = canManage, forceRefresh = forceRefresh)
                val todayTested = (
                    checkins.filter { it.checkinDate == today }.map { it.userId } +
                    reports.filter { it.isCreatedToday() && !it.isRejected && !it.isMissionReport }.map { it.userId }
                ).distinct().size
                val myDates = if (uid != null) {
                    checkins.filter { it.userId == uid }.mapNotNull { it.checkinDate }.distinct()
                } else emptyList()

                val daysByUser = checkins.groupBy { it.userId }
                    .mapValues { (_, list) -> list.mapNotNull { it.checkinDate }.distinct().size }

                val profiles = repository.getProfiles(
                    (members.map { it.userId } + checkins.map { it.userId } + listOfNotNull(campaign.ownerId)).distinct(),
                    forceRefresh = forceRefresh
                )
                val ownerProfile = campaign.ownerId?.let { profiles[it] }

                val memberProgress = members.map { member ->
                    MemberProgress(
                        member = member,
                        profile = profiles[member.userId],
                        days = daysByUser[member.userId] ?: 0,
                    )
                }.sortedByDescending { it.days }

                val feedbacks = repository.getFeedbacks(campaignId)
                val feedbackProfiles = repository.getProfiles(feedbacks.map { it.userId }.distinct(), forceRefresh = forceRefresh)
                val feedbackItems = feedbacks.map { fb ->
                    FeedbackItem(fb, feedbackProfiles[fb.userId])
                }
                val avgRating = if (feedbacks.isNotEmpty()) feedbacks.map { it.rating }.average().toFloat() else 0f
                val reportsCount = reports.size.coerceAtLeast(feedbacks.size)
                // Ưu tiên suy ra từ danh sách reports đã tải; chỉ gọi thêm khi RLS không trả về báo cáo của mình.
                val myReports = if (uid != null) reports.filter { it.userId == uid } else emptyList()
                val remoteReward = detectRemoteApprovalReward(myReports)
                val myLatestReport = myReports.firstOrNull() ?: repository.getMyLatestReport(campaignId)
                val myTodayReport = if (initialMissionId == null) {
                    myReports.firstOrNull { it.isCreatedToday() && !it.isMissionReport }
                        ?: repository.getMyTodayReport(campaignId)?.takeIf { !it.isMissionReport }
                } else {
                    myReports.firstOrNull { it.isCreatedToday() && it.isMissionReport && it.missionId == initialMissionId }
                        ?: myReports.firstOrNull { it.isCreatedToday() }
                        ?: repository.getMyTodayReport(campaignId)
                }
                val unreadWarnings = if (uid != null) repository.getUnreadWarnings() else emptyList()
                val weeklyCount = if (uid != null) repository.getWeeklyWarningsCount() else 0

                if (myTodayReport?.isApproved == true) {
                    _uiState.value.preparedScreenshot?.file?.delete()
                }
                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != effectiveUserId()) {
                    return@launch
                }

                val res = resolveMissionContext(myTodayReport)
                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != effectiveUserId()) {
                    return@launch
                }
                val adminMission = if (myProfile?.isAdmin == true) {
                    try { repository.getCampaignMission(campaignId) } catch (_: Exception) { null }
                } else null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = campaign,
                        ownerProfile = ownerProfile,
                        isMember = isMember,
                        isOwner = isOwner,
                        canManage = canManage,
                        checkedInToday = checkedIn || (myTodayReport?.isApproved == true && !myTodayReport.isMissionReport),
                        members = memberProgress,
                        feedbacks = feedbackItems,
                        todayTestedCount = todayTested,
                        reportsCount = reportsCount,
                        avgRating = avgRating,
                        myCheckinDates = myDates,
                        myLatestReport = myLatestReport,
                        myTodayReport = myTodayReport,
                        myReports = myReports,
                        unreadWarning = unreadWarnings.firstOrNull(),
                        weeklyWarningsCount = weeklyCount,
                        isUserLocked = myProfile?.isLocked == true,
                        isAdmin = myProfile?.isAdmin == true,
                        currentUserId = effectiveUserId(),
                        reward = remoteReward ?: it.reward,
                        activeMission = res.mission,
                        adminManageableMission = adminMission ?: res.mission,
                        isMissionReport = res.isMission,
                        missionDeadlinePassed = res.deadlinePassed,
                        intendedMissionId = res.intendedMissionId ?: it.intendedMissionId,
                        missionContextError = res.contextError,
                        error = null,
                    ).withReportPrefill(myTodayReport)
                }
                adminPauseDialogController.syncIdentity(
                    userId = effectiveUserId(),
                    generation = SessionCacheManager.currentGeneration(),
                    isAdmin = myProfile?.isAdmin == true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != refreshSequence.get() || initialGen != SessionCacheManager.currentGeneration() || initialUid != effectiveUserId()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.campaign == null) friendlyError(e, localizer) else null
                    )
                }
            }
        }
    }

    private data class MissionResolution(
        val mission: Mission?,
        val isMission: Boolean,
        val deadlinePassed: Boolean,
        val intendedMissionId: String?,
        val contextError: String? = null,
    )

    private suspend fun resolveMissionContext(todayReport: TesterReport?): MissionResolution {
        if (initialMissionId == null) return MissionResolution(null, false, false, null)
        // 1. Nếu đã có báo cáo hôm nay: Báo cáo đã lưu là nguồn xác định loại và mission identity!
        if (todayReport != null) {
            if (todayReport.isMissionReport) {
                val reportMissionId = todayReport.missionId
                val targetMissionId = initialMissionId ?: reportMissionId
                val mission = try {
                    repository.getCampaignMission(campaignId, targetMissionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val matchedMission = if (mission != null && (targetMissionId == null || mission.id == targetMissionId || mission.id == reportMissionId)) {
                    mission
                } else null

                val isExpired = matchedMission != null && !repository.isMissionActiveAndUnexpired(matchedMission)
                val deadlinePassed = !todayReport.isApproved && isExpired
                return MissionResolution(
                    mission = matchedMission,
                    isMission = true,
                    deadlinePassed = deadlinePassed,
                    intendedMissionId = targetMissionId ?: reportMissionId,
                    contextError = null,
                )
            } else {
                // Báo cáo thường: giữ nguyên là thường! Tuyệt đối không tự convert sang mission dù đã join mission
                return MissionResolution(
                    mission = null,
                    isMission = false,
                    deadlinePassed = false,
                    intendedMissionId = null,
                    contextError = null,
                )
            }
        }

        // 2. Bài nộp mới (todayReport == null)
        val targetMissionId = initialMissionId ?: _uiState.value.intendedMissionId
        var contextError: String? = null
        val mission = try {
            repository.getCampaignMission(campaignId, targetMissionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            contextError = friendlyError(e, localizer)
            null
        }

        if (contextError != null) {
            return MissionResolution(
                mission = null,
                isMission = targetMissionId != null,
                deadlinePassed = false,
                intendedMissionId = targetMissionId,
                contextError = contextError,
            )
        }

        if (mission == null) {
            return MissionResolution(
                mission = null,
                isMission = targetMissionId != null,
                deadlinePassed = false,
                intendedMissionId = targetMissionId,
                contextError = null,
            )
        }

        if (targetMissionId != null && mission.id != targetMissionId) {
            return MissionResolution(
                mission = null,
                isMission = true,
                deadlinePassed = false,
                intendedMissionId = targetMissionId,
                contextError = null,
            )
        }

        val isExpired = !repository.isMissionActiveAndUnexpired(mission)
        val hasJoined = mission.hasJoined || (targetMissionId != null && mission.id == targetMissionId)
        val deadlinePassed = hasJoined && isExpired
        val isMission = (targetMissionId != null) || (hasJoined && !isExpired)

        return MissionResolution(
            mission = mission,
            isMission = isMission,
            deadlinePassed = deadlinePassed,
            intendedMissionId = targetMissionId ?: (if (isMission) mission.id else null),
            contextError = null,
        )
    }

    /** Áp kết quả RPC `get_campaign_detail` (1 round-trip) vào UI state. */
    private fun applyDetail(
        detail: CampaignDetailResult,
        mission: Mission? = null,
        isMission: Boolean = false,
        deadlinePassed: Boolean = false,
        intendedMissionId: String? = null,
        contextError: String? = null,
        adminMission: Mission? = null,
    ) {
        val campaign = detail.campaign
        val ownerProfile = campaign.ownerId?.let { detail.profiles[it] }
        val memberProgress = detail.testers.map { tester ->
            MemberProgress(
                member = CampaignMember(campaignId = campaignId, userId = tester.userId, joinedAt = tester.joinedAt),
                profile = detail.profiles[tester.userId],
                days = tester.days,
            )
        }.sortedByDescending { it.days }
        val feedbackItems = detail.feedbacks.map { feedback ->
            FeedbackItem(feedback, detail.profiles[feedback.userId])
        }
        val myReports = if (effectiveUserId() != null) {
            detail.reports.filter { it.userId == effectiveUserId() }
        } else emptyList()
        val remoteReward = detectRemoteApprovalReward(myReports)
        val myTodayReport = if (initialMissionId == null && !isMission) {
            myReports.firstOrNull { it.isCreatedToday() && !it.isMissionReport }
        } else {
            val targetMid = intendedMissionId ?: initialMissionId ?: mission?.id
            myReports.firstOrNull { it.isCreatedToday() && it.isMissionReport && (targetMid == null || it.missionId == targetMid) }
                ?: myReports.firstOrNull { it.isCreatedToday() }
        }
        if (myTodayReport?.isApproved == true) {
            _uiState.value.preparedScreenshot?.file?.delete()
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                isBackgroundSyncing = false,
                campaign = campaign,
                ownerProfile = ownerProfile,
                isMember = detail.isMember,
                isOwner = detail.isActualOwner,
                canManage = detail.canManage,
                checkedInToday = detail.checkedInConfirmedToday || (myTodayReport?.isApproved == true && !myTodayReport.isMissionReport),
                members = memberProgress,
                feedbacks = feedbackItems,
                todayTestedCount = detail.todayTestedCount,
                reportsCount = detail.reportsCount.coerceAtLeast(detail.feedbacks.size),
                avgRating = detail.avgRating ?: 0f,
                myCheckinDates = detail.myCheckinDates,
                myLatestReport = myReports.firstOrNull(),
                myTodayReport = myTodayReport,
                myReports = myReports,
                unreadWarning = detail.unreadWarnings.firstOrNull(),
                weeklyWarningsCount = detail.weeklyWarningsCount,
                isUserLocked = detail.myProfile?.isLocked == true,
                isAdmin = detail.myProfile?.isAdmin == true,
                currentUserId = effectiveUserId(),
                reward = remoteReward ?: it.reward,
                activeMission = mission,
                adminManageableMission = adminMission ?: mission,
                isMissionReport = isMission,
                missionDeadlinePassed = deadlinePassed,
                intendedMissionId = intendedMissionId ?: it.intendedMissionId,
                missionContextError = contextError,
                appUpdate = detail.appUpdate,
                error = null,
            ).withReportPrefill(myTodayReport)
        }
        adminPauseDialogController.syncIdentity(
            userId = effectiveUserId(),
            generation = SessionCacheManager.currentGeneration(),
            isAdmin = detail.myProfile?.isAdmin == true,
        )
    }

    /**
     * Điền sẵn đánh giá sao + nhận xét từ báo cáo hôm nay để khi tester chỉ
     * đổi ảnh (không nhập lại) thì không vô tình ghi đè rating/comment cũ.
     */
    private fun CampaignDetailUiState.withReportPrefill(report: TesterReport?): CampaignDetailUiState {
        if (report == null) return this
        return copy(
            myRating = if (!ratingTouched) report.rating ?: myRating else myRating,
            myComment = if (!commentTouched && myComment.isBlank()) report.content else myComment,
            // Báo cáo đã duyệt thì ảnh bị khóa: bỏ mọi lựa chọn ảnh mới chưa gửi.
            selectedScreenshotUri = if (report.isApproved) null else selectedScreenshotUri,
            preparedScreenshot = if (report.isApproved) null else preparedScreenshot,
            isPreparingScreenshot = if (report.isApproved) false else isPreparingScreenshot,
        )
    }

    private fun resetOldAdminActionIfSessionChanged(
        currentUid: String? = repository.currentUserId(),
        currentGen: Long = SessionCacheManager.currentGeneration(),
    ) {
        synchronized(adminActionLock) {
            if (activeAdminToken != 0L && (activeAdminUid != currentUid || activeAdminGen != currentGen)) {
                activeAdminToken = 0L
                activeAdminUid = null
                activeAdminGen = 0L
                _uiState.update { it.copy(isActionLoading = false) }
            }
        }
    }

    private fun admitAdminAction(): Pair<Long, String>? {
        val currentUid = repository.currentUserId()
        val currentGen = SessionCacheManager.currentGeneration()
        if (currentUid == null) {
            _uiState.update {
                it.copy(
                    isActionLoading = false,
                    error = localizer.get(R.string.error_permission_denied),
                )
            }
            return null
        }

        val token = synchronized(adminActionLock) {
            if (activeAdminToken != 0L && activeAdminUid == currentUid && activeAdminGen == currentGen) {
                return null
            }
            val nextToken = adminActionSequence.incrementAndGet()
            activeAdminToken = nextToken
            activeAdminUid = currentUid
            activeAdminGen = currentGen
            nextToken
        }

        _uiState.update { it.copy(isActionLoading = true, error = null) }
        refreshSequence.incrementAndGet()
        return Pair(token, currentUid)
    }

    private fun isActionOwner(token: Long, initiatingUid: String, initiatingGen: Long): Boolean {
        return synchronized(adminActionLock) {
            activeAdminToken == token &&
                activeAdminUid == initiatingUid &&
                activeAdminGen == initiatingGen &&
                repository.currentUserId() == initiatingUid &&
                SessionCacheManager.currentGeneration() == initiatingGen
        }
    }

    private fun releaseAdminAction(token: Long) {
        synchronized(adminActionLock) {
            if (activeAdminToken == token) {
                activeAdminToken = 0L
                activeAdminUid = null
                activeAdminGen = 0L
                if (_uiState.value.isActionLoading) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
            }
        }
    }

    fun adminDeleteCampaign(reason: String? = null, onDeleted: () -> Unit) {
        val admission = admitAdminAction() ?: return
        val token = admission.first
        val initiatingUid = admission.second
        val initiatingGen = SessionCacheManager.currentGeneration()

        viewModelScope.launch {
            try {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                repository.adminDeleteCampaign(campaignId, reason)

                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                refreshSequence.incrementAndGet()
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = localizer.get(R.string.campaign_remove_success_msg),
                    )
                }
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.campaign_remove_failed_format, adminCampaignActionError(e)),
                    )
                }
            } finally {
                releaseAdminAction(token)
            }
        }
    }

    /** Admin xóa nhiệm vụ với lý do bắt buộc (ghim missionId cụ thể). */
    fun adminDeleteMission(missionId: String, reason: String, onDeleted: () -> Unit) {
        if (reason.isBlank()) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_admin_delete_err_reason)) }
            return
        }
        val admission = admitAdminAction() ?: return
        val token = admission.first
        val initiatingUid = admission.second
        val initiatingGen = SessionCacheManager.currentGeneration()

        viewModelScope.launch {
            try {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) return@launch
                repository.adminDeleteMission(missionId, reason.trim())
                if (!isActionOwner(token, initiatingUid, initiatingGen)) return@launch

                refreshSequence.incrementAndGet()
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        activeMission = null,
                        adminManageableMission = null,
                        message = localizer.get(R.string.mission_admin_delete_success),
                    )
                }
                refresh(forceRefresh = true)
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) return@launch
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = adminCampaignActionError(e),
                    )
                }
            } finally {
                releaseAdminAction(token)
            }
        }
    }

    /** Admin tạm dừng app của chủ app (không đổi quyền sở hữu). */
    fun adminPauseCampaign(reason: String?, onSuccess: (() -> Unit)? = null) {
        val admission = admitAdminAction() ?: return
        val token = admission.first
        val initiatingUid = admission.second
        val initiatingGen = SessionCacheManager.currentGeneration()

        viewModelScope.launch {
            try {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                repository.adminPauseCampaign(campaignId, reason)

                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                refreshSequence.incrementAndGet()
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = localizer.get(R.string.campaign_admin_pause_success),
                    )
                }
                onSuccess?.invoke()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = adminCampaignActionError(e),
                    )
                }
            } finally {
                releaseAdminAction(token)
            }
        }
    }

    /**
     * Admin tạm dừng app với mã nguyên nhân có cấu trúc (missing_google_group / policy_violation)
     * hoặc mã custom kèm ghi chú tự nhập.
     */
    fun adminPauseCampaignWithReason(
        reasonCode: String,
        customReason: String? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        val admission = admitAdminAction() ?: return
        val token = admission.first
        val initiatingUid = admission.second
        val initiatingGen = SessionCacheManager.currentGeneration()
        val dialogInstanceId = adminPauseDialogController.currentInstanceId()

        viewModelScope.launch {
            try {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                if (reasonCode == com.ntt.crosstest.data.AdminPauseReason.MISSING_GOOGLE_GROUP.code ||
                    reasonCode == com.ntt.crosstest.data.AdminPauseReason.POLICY_VIOLATION.code
                ) {
                    repository.adminPauseCampaignWithReason(campaignId, reasonCode)
                } else {
                    repository.adminPauseCampaign(campaignId, customReason)
                }

                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                refreshSequence.incrementAndGet()
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = localizer.get(R.string.campaign_admin_pause_success),
                    )
                }
                adminPauseDialogController.onActionSuccess(dialogInstanceId, initiatingUid, initiatingGen)
                onSuccess?.invoke()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }
                val errorMsg = adminCampaignActionError(e)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = errorMsg,
                    )
                }
                adminPauseDialogController.onActionError(dialogInstanceId, initiatingUid, initiatingGen, errorMsg)
            } finally {
                releaseAdminAction(token)
            }
        }
    }

    /** Admin mở lại app đang tạm dừng; server vẫn áp luật 30 ngày và Slot. */
    fun adminResumeCampaign() {
        val admission = admitAdminAction() ?: return
        val token = admission.first
        val initiatingUid = admission.second
        val initiatingGen = SessionCacheManager.currentGeneration()

        viewModelScope.launch {
            try {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                val result = repository.adminResumeCampaign(campaignId)

                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }

                refreshSequence.incrementAndGet()
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = if (result.status == "completed") {
                            localizer.get(R.string.campaign_admin_resume_completed)
                        } else {
                            localizer.get(R.string.campaign_admin_resume_success)
                        },
                    )
                }
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActionOwner(token, initiatingUid, initiatingGen)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = adminCampaignActionError(e),
                    )
                }
            } finally {
                releaseAdminAction(token)
            }
        }
    }

    private fun adminCampaignActionError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("permission denied", ignoreCase = true) ||
                msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                msg.contains("NOT_ADMIN", ignoreCase = true) ||
                msg.contains("INSUFFICIENT_PRIVILEGE", ignoreCase = true) ||
                msg.contains("42501", ignoreCase = true) ->
                localizer.get(R.string.error_permission_denied)
            msg.contains("PGRST202", ignoreCase = true) ||
                msg.contains("schema cache", ignoreCase = true) ||
                msg.contains("Could not find the function", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_missing_rpc)
            msg.contains("CAMPAIGN_NOT_FOUND", ignoreCase = true) ->
                localizer.get(R.string.campaign_not_found)
            msg.contains("CAMPAIGN_FINALIZED", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_finalized)
            msg.contains("SLOT_EXPIRED", ignoreCase = true) ||
                msg.contains("SLOT_NOT_ACTIVE_OR_EXPIRED", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_slot_expired)
            msg.contains("CAMPAIGN_WINDOW_EXPIRED", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_window_expired)
            msg.contains("CAMPAIGN_NOT_OPEN", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_not_open)
            msg.contains("CAMPAIGN_NOT_PAUSED", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_not_paused)
            msg.contains("CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_MISSION_ESCROW", ignoreCase = true) ||
                msg.contains("CANNOT_DELETE_CAMPAIGN_WITH_PENDING_MISSION_REPORTS", ignoreCase = true) ->
                localizer.get(R.string.campaign_admin_error_unsettled_mission_escrow)
            else -> friendlyError(e, localizer)
        }
    }

    fun ensureJoined() {
        val uid = effectiveUserId() ?: return
        if (_uiState.value.isMember || _uiState.value.isOwner) return
        viewModelScope.launch {
            try {
                repository.joinCampaign(campaignId)
                _uiState.update { it.copy(isMember = true) }
            } catch (_: Exception) {}
        }
    }

    fun recordTestStart() {
        _uiState.update { it.copy(testStartedAtMs = System.currentTimeMillis()) }
    }

    fun validateCanSubmit(): Pair<Boolean, String?> {
        val state = _uiState.value
        if (state.checkedInToday || state.myTodayReport?.isApproved == true || state.myTodayReport?.isPending == true) {
            return Pair(true, null)
        }
        val startMs = state.testStartedAtMs
        if (startMs == null) {
            return Pair(false, localizer.get(R.string.campaign_need_open_app))
        }
        val elapsedSeconds = (System.currentTimeMillis() - startMs) / 1000
        if (elapsedSeconds < 10) {
            return Pair(false, localizer.get(R.string.campaign_test_too_short))
        }
        return Pair(true, null)
    }

    fun join() {
        if (_uiState.value.isOwner) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_owner_cant_join)) }
            return
        }
        runAction(localizer.get(R.string.campaign_join_success)) {
            repository.joinCampaign(campaignId)
        }
    }

    fun leave() = runAction(localizer.get(R.string.campaign_leave_success)) {
        repository.leaveCampaign(campaignId)
    }

    fun onRatingChange(rating: Int) {
        _uiState.update { it.copy(myRating = rating.coerceIn(1, 5), ratingTouched = true) }
    }

    fun onCommentChange(comment: String) {
        _uiState.update { it.copy(myComment = comment, commentTouched = true) }
    }

    /**
     * Chọn ảnh -> nén ngay một lần. Kết quả [PreparedScreenshot] được dùng lại
     * cho preview, check trùng và upload (không nén lại ở 3 nơi như trước).
     */
    fun onScreenshotSelected(uri: Uri?, context: Context? = null) {
        prepareScreenshotJob?.cancel()
        val previous = _uiState.value.preparedScreenshot

        if (uri == null) {
            previous?.file?.delete()
            _uiState.update {
                it.copy(
                    selectedScreenshotUri = null,
                    preparedScreenshot = null,
                    isPreparingScreenshot = false,
                )
            }
            return
        }

        val appContext = context?.applicationContext
        if (appContext == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.error_image_read_failed)) }
            return
        }

        _uiState.update {
            it.copy(
                selectedScreenshotUri = uri,
                preparedScreenshot = null,
                isPreparingScreenshot = true,
                error = null,
            )
        }
        prepareScreenshotJob = viewModelScope.launch {
            try {
                val prepared = FirebaseStorageHelper.prepareScreenshot(appContext, uri)
                previous?.file?.delete()
                _uiState.update {
                    it.copy(
                        preparedScreenshot = prepared,
                        selectedScreenshotUri = Uri.fromFile(prepared.file),
                        isPreparingScreenshot = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previous?.file?.delete()
                _uiState.update {
                    it.copy(
                        preparedScreenshot = null,
                        selectedScreenshotUri = null,
                        isPreparingScreenshot = false,
                        error = friendlyError(e, localizer),
                    )
                }
            }
        }
    }

    private data class PrefetchedCandidates(
        val candidates: List<NextCampaignCandidate>,
        val userId: String?,
        val sessionGen: Long,
        val businessDate: java.time.LocalDate,
        val mutationVersion: Long,
        val createdAtMs: Long,
        val isFirstTimeTesterSnapshot: Boolean,
    )
    private var prefetchedCandidates: PrefetchedCandidates? = null
    private val nextSuggestionSequence = java.util.concurrent.atomic.AtomicLong(0)
    private var prefetchJob: Job? = null
    private var consumeJob: Job? = null

    private fun isPrefetchFresh(entry: PrefetchedCandidates?): Boolean {
        if (entry == null) return false
        val currentUid = effectiveUserId()
        val currentGen = SessionCacheManager.currentGeneration()
        val currentDay = repository.businessDay()
        val currentMutVer = repository.getMutationVersion("campaigns")
        val now = System.currentTimeMillis()

        return entry.userId == currentUid &&
                entry.sessionGen == currentGen &&
                entry.businessDate == currentDay &&
                entry.mutationVersion == currentMutVer &&
                (now - entry.createdAtMs) <= CampaignRepository.CACHE_TTL_MS
    }

    /**
     * Tìm trước app kế tiếp khi người dùng mở form nộp để hộp thoại gợi ý hiện tức thì.
     *
     * Thứ tự 4 nhóm ưu tiên nghiêm ngặt (theo kế hoạch 10/11/2026):
     * 1. Chưa đủ chỉ tiêu hôm nay + Đã cài trên máy
     * 2. Chưa đủ chỉ tiêu hôm nay + Chưa cài trên máy
     * 3. Đã đủ hoặc vượt chỉ tiêu hôm nay + Đã cài trên máy
     * 4. Đã đủ hoặc vượt chỉ tiêu hôm nay + Chưa cài trên máy
     *
     * App chưa đủ chỉ tiêu luôn thắng app đã đủ chỉ tiêu (dù app đủ chỉ tiêu đã cài).
     * Với tài khoản test lần đầu: tiêu chí lượt cài thấp chỉ áp dụng trong nhóm đã thắng cuộc.
     */
    fun prefetchNextCampaign(forceRefresh: Boolean = false) {
        val currentUid = effectiveUserId()
        val currentGen = SessionCacheManager.currentGeneration()
        val currentDay = repository.businessDay()
        val currentMutVer = repository.getMutationVersion("campaigns")

        val existing = prefetchedCandidates
        val isFresh = !forceRefresh && isPrefetchFresh(existing)

        if (isFresh) {
            if (_uiState.value.showNextAppPrompt) {
                val seq = nextSuggestionSequence.incrementAndGet()
                consumePrefetchedCandidatesInternal(existing!!, seq)
            }
            return
        }

        val seq = nextSuggestionSequence.incrementAndGet()
        prefetchJob?.cancel()
        consumeJob?.cancel()

        // Xóa lựa chọn cũ và bật loading để nút "Test ngay" cũ không thắng nhầm trạng thái đang tải/lỗi
        _uiState.update {
            it.copy(
                nextSuggestionLoading = true,
                nextSuggestionFailed = false,
                nextCampaignToTest = null,
            )
        }

        val alreadyTestedSnapshot = testedInThisViewModel

        prefetchJob = viewModelScope.launch {
            try {
                val isFirstTestSnapshot = if (currentUid != null) {
                    (!alreadyTestedSnapshot) && eagerFirstTimeTester(currentUid).await()
                } else false

                val preFetchMutVer = repository.getMutationVersion("campaigns")
                val candidates = repository.getNextCampaignCandidates(campaignId)
                val postFetchMutVer = repository.getMutationVersion("campaigns")

                // CỰC KỲ QUAN TRỌNG: Nếu sequence không còn là seq hiện tại (đã bị hủy/thay thế),
                // TUYỆT ĐỐI KHÔNG ghi bất kỳ UI state nào!
                if (seq != nextSuggestionSequence.get()) return@launch

                val curUid = effectiveUserId()
                val curGen = SessionCacheManager.currentGeneration()
                val curDay = repository.businessDay()

                if (curGen != currentGen || curUid != currentUid || curDay != currentDay) {
                    if (curUid != currentUid) {
                        _uiState.update {
                            it.copy(
                                showNextAppPrompt = false,
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = false,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = true,
                            )
                        }
                    }
                    return@launch
                }

                if (preFetchMutVer != postFetchMutVer) {
                    // Mutation đã thay đổi trong lúc fetch: repository trả về emptyList do hủy kết quả cũ.
                    // Không lưu danh sách rác này thành danh sách rỗng hợp lệ ở mutation mới!
                    _uiState.update {
                        it.copy(
                            nextSuggestionLoading = false,
                            nextCampaignToTest = null,
                            nextSuggestionFailed = true,
                        )
                    }
                    return@launch
                }

                val newEntry = PrefetchedCandidates(
                    candidates = candidates,
                    userId = curUid,
                    sessionGen = curGen,
                    businessDate = curDay,
                    mutationVersion = postFetchMutVer,
                    createdAtMs = System.currentTimeMillis(),
                    isFirstTimeTesterSnapshot = isFirstTestSnapshot,
                )
                prefetchedCandidates = newEntry

                consumePrefetchedCandidatesInternal(newEntry, seq)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (seq != nextSuggestionSequence.get()) return@launch
                val curUid = effectiveUserId()
                if (curUid != currentUid) {
                    _uiState.update {
                        it.copy(
                            showNextAppPrompt = false,
                            nextCampaignToTest = null,
                            nextSuggestionLoading = false,
                            nextSuggestionFailed = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            nextCampaignToTest = null,
                            nextSuggestionLoading = false,
                            nextSuggestionFailed = true,
                        )
                    }
                }
            }
        }
    }

    fun retryPrefetchNextCampaign() {
        prefetchNextCampaign(forceRefresh = true)
    }

    /**
     * Tiêu thụ danh sách ứng viên đã tìm trước:
     * - Kiểm tra tính hợp lệ của phiên đăng nhập và ngày nghiệp vụ.
     * - Luôn kiểm tra lại danh sách báo cáo đang chờ gửi cục bộ tại thời điểm tiêu thụ
     *   để không gợi ý lại app vừa nộp.
     * - Làm mới danh sách package đã cài để nhận diện app tester vừa cài đặt.
     * - Chọn theo 4 nhóm ưu tiên nghiêm ngặt qua [NextCampaignSelector].
     */
    fun consumePrefetchedCandidates() {
        val existing = prefetchedCandidates
        if (existing != null && isPrefetchFresh(existing)) {
            val seq = nextSuggestionSequence.incrementAndGet()
            prefetchJob?.cancel()
            _uiState.update {
                it.copy(
                    nextSuggestionLoading = true,
                    nextSuggestionFailed = false,
                    nextCampaignToTest = null,
                )
            }
            consumePrefetchedCandidatesInternal(existing, seq)
        } else if (prefetchJob?.isActive == true) {
            // Đang có prefetch hợp lệ chạy nền: không cancel/restart để tránh gián đoạn
            _uiState.update {
                it.copy(
                    nextSuggestionLoading = true,
                    nextSuggestionFailed = false,
                    nextCampaignToTest = null,
                )
            }
        } else {
            prefetchNextCampaign(forceRefresh = true)
        }
    }

    private fun consumePrefetchedCandidatesInternal(
        entry: PrefetchedCandidates,
        seq: Long,
    ) {
        val targetUid = entry.userId
        val targetGen = entry.sessionGen
        val targetDay = entry.businessDate
        val isFirstTest = entry.isFirstTimeTesterSnapshot

        consumeJob?.cancel()
        consumeJob = viewModelScope.launch {
            try {
                val ctx = appContext
                val pendingCampaignIds = if (ctx == null) {
                    emptySet()
                } else withContext(Dispatchers.IO) {
                    PendingReportStore.pendingCampaignIds(ctx, "app_test")
                }

                if (seq != nextSuggestionSequence.get()) return@launch
                if (SessionCacheManager.currentGeneration() != targetGen ||
                    effectiveUserId() != targetUid ||
                    repository.businessDay() != targetDay ||
                    repository.getMutationVersion("campaigns") != entry.mutationVersion) {
                    if (effectiveUserId() != targetUid) {
                        _uiState.update {
                            it.copy(
                                showNextAppPrompt = false,
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = false,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = true,
                            )
                        }
                    }
                    return@launch
                }

                val eligible = entry.candidates.filterNot {
                    it.campaign.id == campaignId || it.campaign.id in pendingCampaignIds
                }

                val installedPackages = if (eligible.isEmpty() || ctx == null) {
                    emptySet()
                } else withContext(Dispatchers.IO) {
                    InstalledAppCache.refreshPackages(ctx)
                    eligible.mapNotNull { candidate ->
                        val pkg = candidate.campaign.packageName
                        pkg.takeIf { it.isNotBlank() && InstalledAppCache.isInstalled(ctx, it) }
                    }.toSet()
                }

                if (seq != nextSuggestionSequence.get()) return@launch
                if (SessionCacheManager.currentGeneration() != targetGen ||
                    effectiveUserId() != targetUid ||
                    repository.businessDay() != targetDay ||
                    repository.getMutationVersion("campaigns") != entry.mutationVersion) {
                    if (effectiveUserId() != targetUid) {
                        _uiState.update {
                            it.copy(
                                showNextAppPrompt = false,
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = false,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                nextCampaignToTest = null,
                                nextSuggestionLoading = false,
                                nextSuggestionFailed = true,
                            )
                        }
                    }
                    return@launch
                }

                val next = NextCampaignSelector.selectNextCampaign(eligible, installedPackages, isFirstTest, nextCampaignRandomPicker)

                _uiState.update {
                    it.copy(
                        nextSuggestionLoading = false,
                        nextCampaignToTest = next,
                        nextSuggestionFailed = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (seq != nextSuggestionSequence.get()) return@launch
                if (effectiveUserId() != targetUid) {
                    _uiState.update {
                        it.copy(
                            showNextAppPrompt = false,
                            nextCampaignToTest = null,
                            nextSuggestionLoading = false,
                            nextSuggestionFailed = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            nextCampaignToTest = null,
                            nextSuggestionLoading = false,
                            nextSuggestionFailed = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * Chọn app kế tiếp từ danh sách đủ điều kiện.
     * Ủy quyền cho [NextCampaignSelector.selectNextCampaign] theo 4 nhóm ưu tiên nghiêm ngặt.
     */
    private fun chooseNextCampaign(
        candidates: List<NextCampaignCandidate>,
        installedPackages: Set<String>,
        isFirstTest: Boolean,
    ): Campaign? = NextCampaignSelector.selectNextCampaign(candidates, installedPackages, isFirstTest)

    /**
     * Nộp báo cáo: trả về true nếu yêu cầu được tiếp nhận (enqueue thành công),
     * trả về false nếu bị chặn do điều kiện không thỏa mãn hoặc đang có bài gửi.
     */
    suspend fun startSubmit(): Boolean {
        val uid = effectiveUserId()
        if (uid == null) {
            _uiState.update { it.copy(submitError = localizer.get(R.string.campaign_login_required), error = localizer.get(R.string.campaign_login_required)) }
            return false
        }
        val state = _uiState.value
        if (state.isOwner) {
            _uiState.update { it.copy(submitError = localizer.get(R.string.campaign_owner_cant_report), error = localizer.get(R.string.campaign_owner_cant_report)) }
            return false
        }
        if (state.submitStage == ReportSubmitManager.Stage.SENDING ||
            state.submitStage == ReportSubmitManager.Stage.RETRYING
        ) {
            _uiState.update { it.copy(submitError = localizer.get(R.string.campaign_report_already_in_flight)) }
            return false
        }

        val todayReport = state.myTodayReport
        if (todayReport != null && todayReport.isMissionReport != (initialMissionId != null)) {
            _uiState.update { it.copy(submitError = localizer.get(R.string.error_report_context_conflict)) }
            return false
        }
        val pendingToday = todayReport?.isPending == true
        val rejectedToday = todayReport?.isRejected == true
        val approvedToday = todayReport?.isApproved == true
        val alreadyRewarded = (state.checkedInToday || approvedToday) && !rejectedToday

        // N03: Người dùng mở từ một mission cụ thể (initialMissionId), nhưng hôm nay app đã có báo cáo:
        if (todayReport != null && initialMissionId != null) {
            if (!todayReport.isMissionReport) {
                // Đã có báo cáo thường (pending/approved/rejected) -> không thể nộp nhiệm vụ hôm nay
                _uiState.update {
                    it.copy(submitError = localizer.get(R.string.campaign_mission_conflict_with_approved_app_test))
                }
                return false
            } else if (todayReport.missionId != null && todayReport.missionId != initialMissionId) {
                // Đã có báo cáo cho mission khác trong ngày -> không thể nộp cho mission mới này
                _uiState.update {
                    it.copy(submitError = localizer.get(R.string.campaign_mission_conflict_with_approved_app_test))
                }
                return false
            }
        }

        // R02: Hôm nay đã có báo cáo approved là app_test thường, nhưng mở với ý định mission -> Chặn, báo lỗi rõ ràng!
        if (approvedToday && todayReport?.isMissionReport != true && initialMissionId != null) {
            _uiState.update {
                it.copy(submitError = localizer.get(R.string.campaign_mission_conflict_with_approved_app_test))
            }
            return false
        }

        // R01: Báo cáo thường pending mà mở với ý định mission -> Chặn chuyển đổi
        if (pendingToday && todayReport?.isMissionReport != true && initialMissionId != null) {
            _uiState.update {
                it.copy(submitError = localizer.get(R.string.campaign_cannot_convert_pending_report))
            }
            return false
        }

        val hasMissionIntent = if (todayReport != null) {
            todayReport.isMissionReport
        } else {
            initialMissionId != null || state.isMissionReport || state.intendedMissionId != null
        }
        val mission = state.activeMission

        if (hasMissionIntent) {
            // R04: Nếu là approved mission report bổ sung nhận xét (alreadyRewarded), KHÔNG phụ thuộc vào mission context error hoặc hạn!
            if (!alreadyRewarded) {
                if (state.missionContextError != null && mission == null) {
                    _uiState.update {
                        it.copy(submitError = localizer.get(R.string.repo_connection_failed))
                    }
                    return false
                }
                val isExpired = state.missionDeadlinePassed || (mission != null && !repository.isMissionActiveAndUnexpired(mission))
                if (isExpired) {
                    _uiState.update {
                        it.copy(
                            submitError = localizer.get(R.string.mission_expired_error),
                            missionDeadlinePassed = true,
                        )
                    }
                    return false
                }
                if (mission == null && todayReport?.isMissionReport != true) {
                    _uiState.update {
                        it.copy(submitError = localizer.get(R.string.mission_expired_error))
                    }
                    return false
                }
                if (mission != null && !mission.hasJoined && todayReport?.isMissionReport != true) {
                    _uiState.update {
                        it.copy(submitError = localizer.get(R.string.mission_joined_cta))
                    }
                    return false
                }
            }
        }

        // Báo cáo bị từ chối: bắt buộc chọn ảnh mới trước khi nộp lại.
        if (rejectedToday && state.preparedScreenshot == null) {
            _uiState.update {
                it.copy(submitError = localizer.get(R.string.campaign_resubmit_new_photo_required))
            }
            return false
        }

        pendingSuccessMessage = when {
            alreadyRewarded -> localizer.get(R.string.campaign_report_extra_success)
            pendingToday -> localizer.get(R.string.campaign_report_update_success)
            rejectedToday -> localizer.get(R.string.campaign_report_resubmit_success)
            else -> localizer.get(
                R.string.campaign_report_submit_success_format,
                ReportSubmitManager.PROVISIONAL_CREDITS,
            )
        }

        val submitMissionId = if (alreadyRewarded) {
            if (todayReport?.isMissionReport == true) todayReport.missionId else null
        } else if (hasMissionIntent) {
            if (todayReport != null) {
                if (todayReport.isMissionReport) todayReport.missionId else null
            } else {
                initialMissionId ?: state.intendedMissionId ?: mission?.id
            }
        } else {
            null
        }

        val submitMissionTitle = if (alreadyRewarded) {
            if (todayReport?.isMissionReport == true) todayReport.missionTitle else null
        } else if (hasMissionIntent) {
            if (todayReport != null) {
                if (todayReport.isMissionReport) (todayReport.missionTitle ?: mission?.title) else null
            } else {
                mission?.title ?: state.myTodayReport?.missionTitle
            }
        } else {
            null
        }

        val clientSubmitId = java.util.UUID.randomUUID().toString()
        val commentToSubmit = state.myComment
        val ratingToSubmit = state.myRating
        val preparedPathToSubmit = state.preparedScreenshot?.file?.absolutePath

        // Đăng ký snapshot/ownership trước khi gọi submit để loại trừ race condition nếu worker hoàn thành siêu nhanh
        val isFirstInFlight = lastSubmittedRequestId == null
        if (isFirstInFlight) {
            lastSubmittedRequestId = clientSubmitId
            submittedCommentSnapshot = commentToSubmit
            submittedRatingSnapshot = ratingToSubmit
            submittedPreparedPath = preparedPathToSubmit
        }

        val admission = reportSubmitManager.submit(
            ReportSubmitManager.SubmitRequest(
                campaignId = campaignId,
                appName = state.campaign?.appName.orEmpty(),
                userId = uid,
                rating = state.myRating,
                comment = state.myComment,
                preparedScreenshot = if (alreadyRewarded) null else state.preparedScreenshot,
                fallbackScreenshotUrl = state.myTodayReport?.screenshotUrl,
                fallbackImageHash = state.myTodayReport?.imageHash,
                needsJoin = !state.isMember,
                clientSubmitId = clientSubmitId,
                missionId = submitMissionId,
                missionTitle = submitMissionTitle,
            )
        )

        return when (admission) {
            is ReportSubmitManager.SubmitAdmissionResult.Accepted -> {
                _uiState.update {
                    it.copy(
                        showNextAppPrompt = true,
                        submitError = null,
                    )
                }
                consumePrefetchedCandidates()
                true
            }
            is ReportSubmitManager.SubmitAdmissionResult.AlreadyInFlight -> {
                // Yêu cầu bị AlreadyInFlight không được xóa ownership của bài đầu
                _uiState.update {
                    it.copy(submitError = localizer.get(R.string.campaign_report_already_in_flight))
                }
                false
            }
            is ReportSubmitManager.SubmitAdmissionResult.Blocked -> {
                if (lastSubmittedRequestId == clientSubmitId) {
                    lastSubmittedRequestId = null
                    submittedCommentSnapshot = null
                    submittedRatingSnapshot = null
                    submittedPreparedPath = null
                }
                _uiState.update {
                    it.copy(submitError = friendlyError(Exception(admission.code), localizer))
                }
                false
            }
        }
    }

    fun retrySubmit() {
        reportSubmitManager.retry(campaignId)
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

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    /**
     * Xác thực lại app gợi ý đã chọn khi người dùng nhấn "Test ngay".
     * Trả về campaign ID nếu snapshot còn tươi mới cho identity, ngày và mutation hiện tại;
     * ngược lại xóa lựa chọn cũ, bắt đầu tải lại (refresh) và trả về null.
     */
    fun validateAndConsumeNextCampaign(): String? {
        val selected = _uiState.value.nextCampaignToTest
        val snapshot = prefetchedCandidates
        val targetId = selected?.id?.takeIf { it.isNotBlank() }
        if (targetId == null || snapshot == null || !isPrefetchFresh(snapshot)) {
            prefetchedCandidates = null
            val currentUid = effectiveUserId()
            if (snapshot != null && snapshot.userId != currentUid) {
                _uiState.update {
                    it.copy(
                        showNextAppPrompt = false,
                        nextCampaignToTest = null,
                        nextSuggestionLoading = false,
                        nextSuggestionFailed = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        nextCampaignToTest = null,
                        nextSuggestionLoading = true,
                        nextSuggestionFailed = false,
                    )
                }
                prefetchNextCampaign(forceRefresh = true)
            }
            return null
        }
        return targetId
    }

    fun dismissNextAppPrompt() {
        nextSuggestionSequence.incrementAndGet()
        prefetchJob?.cancel()
        consumeJob?.cancel()
        prefetchedCandidates = null
        _uiState.update { it.copy(showNextAppPrompt = false, nextCampaignToTest = null, nextSuggestionLoading = false, nextSuggestionFailed = false) }
    }

    fun clearReward() {
        _uiState.update { it.copy(reward = null) }
    }

    /**
     * So sánh trạng thái báo cáo giữa các lần refresh để phát hiện báo cáo
     * vừa được duyệt từ xa (cùng report id: pending → approved).
     *
     * Phần thưởng thực tế là chênh lệch giữa tổng Credit của báo cáo đã duyệt
     * và Credit tạm nhận. Auto-approve sau 8h không cộng thêm nên chênh lệch
     * bằng 0 và sẽ không hiện banner.
     */
    private fun detectRemoteApprovalReward(reports: List<TesterReport>): CreditReward? {
        if (!reportBaselineCaptured) {
            reports.forEach { report -> report.id?.let { observedReportStatuses[it] = report.status } }
            reportBaselineCaptured = true
            return null
        }
        var bonus = 0
        reports.forEach { report ->
            val id = report.id ?: return@forEach
            val previous = observedReportStatuses[id]
            if (previous == "pending" && report.status == "approved") {
                val delta = (report.approvedReward - report.provisionalGrantedCredits).coerceAtLeast(0)
                bonus += delta
            }
            observedReportStatuses[id] = report.status
        }
        if (bonus <= 0) return null
        return CreditReward(
            type = CreditRewardType.REPORT_APPROVED_REMOTE,
            amount = bonus,
            appName = _uiState.value.campaign?.appName.orEmpty(),
        )
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        if (effectiveUserId() == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_login_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, message = null, error = null) }
            try {
                block()
                _uiState.update { it.copy(isActionLoading = false, message = successMessage) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e, localizer)) }
            } finally {
                refresh()
            }
        }
    }

    fun clearUpdateClaimMessages() {
        _uiState.update { it.copy(updateClaimSuccessMessage = null, updateClaimError = null) }
    }

    fun checkInstalledAppVersion(context: Context) {
        val packageName = _uiState.value.campaign?.packageName ?: return
        val currentRel = _uiState.value.appUpdate ?: return
        if (currentRel.isExhausted || currentRel.status != "active" || _uiState.value.isOwner) return
        if (!currentRel.isEligible || _uiState.value.campaign?.status != "open") return

        val targetReleaseId = currentRel.id
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingInstalledVersion = true) }
            val res = InstalledAppVersionReader.readInstalledVersion(context, packageName)
            when (res) {
                is InstalledVersionResult.Installed -> {
                    _uiState.update {
                        it.copy(
                            installedVersionCode = res.versionCode,
                            installedVersionName = res.versionName,
                            isCheckingInstalledVersion = false,
                        )
                    }
                    val liveRel = _uiState.value.appUpdate
                    if (liveRel != null && liveRel.id == targetReleaseId) {
                        val target = liveRel.targetVersionCode
                        if (res.versionCode >= target && !liveRel.hasClaimed && liveRel.isEligible && !_uiState.value.isClaimingUpdateReward) {
                            claimAppUpdateReward(liveRel.id, res.versionCode)
                        }
                    }
                }
                is InstalledVersionResult.NotInstalled -> {
                    _uiState.update {
                        it.copy(
                            installedVersionCode = null,
                            installedVersionName = null,
                            isCheckingInstalledVersion = false,
                        )
                    }
                }
                is InstalledVersionResult.Unverifiable -> {
                    _uiState.update {
                        it.copy(
                            installedVersionCode = null,
                            installedVersionName = null,
                            isCheckingInstalledVersion = false,
                            updateClaimError = res.reason,
                        )
                    }
                }
            }
        }
    }

    fun claimAppUpdateReward(releaseId: String, observedVersionCode: Long) {
        if (_uiState.value.isClaimingUpdateReward) return

        viewModelScope.launch {
            _uiState.update { it.copy(isClaimingUpdateReward = true, updateClaimError = null) }
            try {
                val result = repository.claimAppUpdateReward(releaseId, observedVersionCode, campaignId = campaignId).getOrThrow()
                if (result.success) {
                    _uiState.update { current ->
                        val currentRel = current.appUpdate
                        val updatedAppUpdate = if (currentRel?.id == releaseId) {
                            currentRel.copy(
                                hasClaimed = true,
                                paidCount = result.paidCount,
                                status = result.releaseStatus,
                                claimReceipt = result.receipt,
                            )
                        } else {
                            currentRel
                        }
                        current.copy(
                            isClaimingUpdateReward = false,
                            appUpdate = updatedAppUpdate,
                            updateClaimSuccessMessage = if (result.newlyAwarded && currentRel?.id == releaseId) {
                                localizer.get(R.string.campaign_update_claim_success_format, result.reward)
                            } else null,
                        )
                    }
                    refresh(forceRefresh = true)
                } else {
                    _uiState.update {
                        it.copy(
                            isClaimingUpdateReward = false,
                            updateClaimError = friendlyError(result.message, localizer).ifBlank {
                                localizer.get(R.string.campaign_update_claim_failed)
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isClaimingUpdateReward = false,
                        updateClaimError = friendlyError(e, localizer).ifBlank {
                            localizer.get(R.string.campaign_update_claim_network_error)
                        },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        synchronized(adminActionLock) {
            activeAdminToken = 0L
            activeAdminUid = null
            activeAdminGen = 0L
        }
        prepareScreenshotJob?.cancel()
        updatePhotoLease(null)
        // Nếu đang nộp, manager đã copy ảnh sang pending và sẽ tự dọn.
        // Nếu chỉ chọn ảnh rồi rời màn, xoá file prepared để không tích rác.
        val submitState = reportSubmitManager.currentStateForCampaign(campaignId)
        val isSubmitting = submitState?.campaignId == campaignId &&
            (submitState.stage == ReportSubmitManager.Stage.SENDING ||
                submitState.stage == ReportSubmitManager.Stage.RETRYING)
        if (!isSubmitting) {
            _uiState.value.preparedScreenshot?.file?.delete()
        }
        super.onCleared()
    }
}
