package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignDetailResult
import com.ntt.crosstest.data.CampaignMember
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Feedback
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.ReportSubmitManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import java.time.LocalDate

import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.data.UserWarning
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PreparedScreenshot
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

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
    val testStartedAtMs: Long? = null,
    val showNextAppPrompt: Boolean = false,
    val nextCampaignToTest: Campaign? = null,
    val nextSuggestionLoading: Boolean = false,
    val submitStage: ReportSubmitManager.Stage? = null,
    val submitAttempt: Int = 0,
    val submitError: String? = null,
)

class CampaignDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
    private val reportSubmitManager: ReportSubmitManager,
    private val myUserId: String?,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CampaignDetailUiState())
    val uiState: StateFlow<CampaignDetailUiState> = _uiState.asStateFlow()

    /** Chỉ giữ 1 tác vụ nén ảnh tại một thời điểm (user đổi ảnh liên tục). */
    private var prepareScreenshotJob: Job? = null

    /** Thông báo thành công tương ứng trạng thái nộp (extra/update/mới) — hiển thị khi ở lại màn. */
    private var pendingSuccessMessage: String? = null

    /**
     * refresh() đã bỏ dở vì phiên còn đang khôi phục (Initializing/RefreshFailure).
     * Khi phiên sẵn sàng trở lại, observer sessionStatus refresh đúng một lần —
     * tránh gửi request ẩn danh (không JWT) làm sai trạng thái thành viên/check-in.
     */
    private var refreshWhenSessionReady = false

    init {
        viewModelScope.launch {
            repository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        if (refreshWhenSessionReady) {
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> refreshWhenSessionReady = false
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            reportSubmitManager.state.collect { submitState ->
                if (submitState == null || submitState.campaignId != campaignId) return@collect
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
                    _uiState.value.preparedScreenshot?.file?.delete()
                    _uiState.update { state ->
                        state.copy(
                            myComment = "",
                            ratingTouched = false,
                            commentTouched = false,
                            selectedScreenshotUri = null,
                            preparedScreenshot = null,
                            isPreparingScreenshot = false,
                            // Dialog đang mở thì đã có dòng trạng thái "đã gửi" — khỏi snackbar trùng.
                            message = if (state.showNextAppPrompt) state.message else successMessage,
                        )
                    }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            if (isSessionRestoring()) {
                refreshWhenSessionReady = true
                return@launch
            }
            val cached = repository.getCampaign(campaignId, forceRefresh = false)
            if (cached != null) {
                val isOwner = myUserId != null && cached.ownerId == myUserId
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
            refresh(forceRefresh = false)
        }
    }

    /** True khi phiên chưa sẵn sàng (đang khôi phục hoặc refresh tạm thời thất bại). */
    private fun isSessionRestoring(): Boolean {
        val status = repository.sessionStatus.value
        return status is SessionStatus.Initializing || status is SessionStatus.RefreshFailure
    }

    fun refresh(forceRefresh: Boolean = false) {
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
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = forceRefresh)
                if (detail != null) {
                    applyDetail(detail)
                    return@launch
                }
                val campaign = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                if (campaign == null) {
                    _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false, error = localizer.get(R.string.campaign_detail_not_found)) }
                    return@launch
                }
                val isMember = if (myUserId != null) repository.isMember(campaignId, myUserId) else false
                // Chỉ coi là "đã check-in" khi check-in đã chính thức (được duyệt), KHÔNG tính
                // dòng check-in tạm tính tạo lúc nộp báo cáo còn chờ chủ app duyệt.
                val checkedIn = if (isMember && myUserId != null) repository.hasConfirmedCheckinToday(campaignId, myUserId) else false
                val isOwner = myUserId != null && campaign.ownerId == myUserId
                val myProfile = if (myUserId != null) repository.getMyProfile(forceRefresh = forceRefresh) else null
                val canManage = isOwner || myProfile?.canModerate == true

                val members = repository.getMembers(campaignId)
                val checkins = repository.getCheckins(campaignId)
                val today = LocalDate.now().toString()
                val reports = repository.getCampaignReports(campaignId, canManage = canManage, forceRefresh = forceRefresh)
                val todayTested = (
                    checkins.filter { it.checkinDate == today }.map { it.userId } +
                    reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.userId }
                ).distinct().size
                val myDates = if (myUserId != null) {
                    checkins.filter { it.userId == myUserId }.mapNotNull { it.checkinDate }.distinct()
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
                val myReports = if (myUserId != null) reports.filter { it.userId == myUserId } else emptyList()
                val myLatestReport = myReports.firstOrNull() ?: repository.getMyLatestReport(campaignId)
                val myTodayReport = myReports.firstOrNull { it.isCreatedToday() } ?: repository.getMyTodayReport(campaignId)
                val unreadWarnings = if (myUserId != null) repository.getUnreadWarnings() else emptyList()
                val weeklyCount = if (myUserId != null) repository.getWeeklyWarningsCount() else 0

                if (myTodayReport?.isApproved == true) {
                    _uiState.value.preparedScreenshot?.file?.delete()
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = campaign,
                        ownerProfile = ownerProfile,
                        isMember = isMember,
                        isOwner = isOwner,
                        canManage = canManage,
                        checkedInToday = checkedIn || (myTodayReport?.isApproved == true),
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
                        error = null,
                    ).withReportPrefill(myTodayReport)
                }
            } catch (e: Exception) {
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

    /** Áp kết quả RPC `get_campaign_detail` (1 round-trip) vào UI state. */
    private fun applyDetail(detail: CampaignDetailResult) {
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
        val myReports = if (myUserId != null) detail.reports.filter { it.userId == myUserId } else emptyList()
        val myTodayReport = myReports.firstOrNull { it.isCreatedToday() }
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
                checkedInToday = detail.checkedInConfirmedToday || (myTodayReport?.isApproved == true),
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
                error = null,
            ).withReportPrefill(myTodayReport)
        }
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

    fun adminDeleteCampaign(reason: String? = null, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.adminDeleteCampaign(campaignId, reason)
                _uiState.update { it.copy(isActionLoading = false, message = localizer.get(R.string.campaign_remove_success_msg)) }
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = localizer.get(R.string.campaign_remove_failed_format, friendlyError(e, localizer))) }
            }
        }
    }

    /** Admin tạm dừng app của chủ app (không đổi quyền sở hữu). */
    fun adminPauseCampaign(reason: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.adminPauseCampaign(campaignId, reason)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = localizer.get(R.string.campaign_admin_pause_success),
                    )
                }
                refresh(forceRefresh = true)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionLoading = false, error = adminCampaignActionError(e))
                }
            }
        }
    }

    /** Admin mở lại app đang tạm dừng; server vẫn áp luật 30 ngày và Slot. */
    fun adminResumeCampaign() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                val result = repository.adminResumeCampaign(campaignId)
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionLoading = false, error = adminCampaignActionError(e))
                }
            }
        }
    }

    private fun adminCampaignActionError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
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
            msg.contains("NOT_ADMIN", ignoreCase = true) ->
                localizer.get(R.string.error_permission_denied)
            else -> friendlyError(e, localizer)
        }
    }

    fun ensureJoined() {
        if (myUserId == null || _uiState.value.isMember || _uiState.value.isOwner) return
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

    /** Tìm trước app kế tiếp khi người dùng mở form nộp để hộp thoại gợi ý hiện tức thì. */
    fun prefetchNextCampaign() {
        val state = _uiState.value
        if (state.nextSuggestionLoading || state.nextCampaignToTest != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(nextSuggestionLoading = true) }
            val next = try {
                repository.getNextUntestedCampaign(campaignId)
            } catch (_: Exception) {
                null
            }
            _uiState.update { it.copy(nextSuggestionLoading = false, nextCampaignToTest = next) }
        }
    }

    /**
     * Nộp báo cáo: hộp thoại gợi ý app kế tiếp hiện NGAY, còn upload ảnh + RPC
     * chạy nền ở [ReportSubmitManager] (không bị hủy khi chuyển màn).
     */
    fun startSubmit() {
        if (myUserId == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_login_required)) }
            return
        }
        val state = _uiState.value
        if (state.isOwner) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_owner_cant_report)) }
            return
        }
        if (state.submitStage == ReportSubmitManager.Stage.SENDING ||
            state.submitStage == ReportSubmitManager.Stage.RETRYING
        ) {
            return
        }

        val pendingToday = state.myTodayReport?.isPending == true
        val rejectedToday = state.myTodayReport?.isRejected == true
        val alreadyRewarded = (state.checkedInToday || state.myTodayReport?.isApproved == true) &&
            state.myTodayReport?.isRejected != true

        // Báo cáo bị từ chối: bắt buộc chọn ảnh mới trước khi nộp lại.
        if (rejectedToday && state.preparedScreenshot == null) {
            _uiState.update {
                it.copy(submitError = localizer.get(R.string.campaign_resubmit_new_photo_required))
            }
            return
        }

        pendingSuccessMessage = when {
            alreadyRewarded -> localizer.get(R.string.campaign_report_extra_success)
            pendingToday -> localizer.get(R.string.campaign_report_update_success)
            rejectedToday -> localizer.get(R.string.campaign_report_resubmit_success)
            else -> localizer.get(R.string.campaign_report_submit_success_format, 10)
        }

        _uiState.update {
            it.copy(
                showNextAppPrompt = true,
                submitError = null,
            )
        }
        prefetchNextCampaign()
        reportSubmitManager.submit(
            ReportSubmitManager.SubmitRequest(
                campaignId = campaignId,
                appName = state.campaign?.appName.orEmpty(),
                userId = myUserId,
                rating = state.myRating,
                comment = state.myComment,
                preparedScreenshot = if (alreadyRewarded) null else state.preparedScreenshot,
                fallbackScreenshotUrl = state.myTodayReport?.screenshotUrl,
                fallbackImageHash = state.myTodayReport?.imageHash,
                needsJoin = !state.isMember,
            )
        )
    }

    fun retrySubmit() {
        reportSubmitManager.retry()
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

    fun closeCampaign() = runAction(localizer.get(R.string.campaign_close_success)) {
        repository.closeCampaign(campaignId)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    fun dismissNextAppPrompt() {
        _uiState.update { it.copy(showNextAppPrompt = false, nextCampaignToTest = null) }
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        if (myUserId == null) {
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

    override fun onCleared() {
        prepareScreenshotJob?.cancel()
        // Nếu đang nộp, manager đã copy ảnh sang pending và sẽ tự dọn.
        // Nếu chỉ chọn ảnh rồi rời màn, xoá file prepared để không tích rác.
        val submitState = reportSubmitManager.state.value
        val isSubmitting = submitState?.campaignId == campaignId &&
            (submitState.stage == ReportSubmitManager.Stage.SENDING ||
                submitState.stage == ReportSubmitManager.Stage.RETRYING)
        if (!isSubmitting) {
            _uiState.value.preparedScreenshot?.file?.delete()
        }
        super.onCleared()
    }
}
