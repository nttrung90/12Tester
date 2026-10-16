package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignMember
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Feedback
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.ImageHashHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import java.time.LocalDate

import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.data.UserWarning

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
    val isOwner: Boolean = false,
    val checkedInToday: Boolean = false,
    val members: List<MemberProgress> = emptyList(),
    val feedbacks: List<FeedbackItem> = emptyList(),
    val todayTestedCount: Int = 0,
    val reportsCount: Int = 0,
    val avgRating: Float = 0f,
    val myCheckinDates: List<String> = emptyList(),
    val myRating: Int = 5,
    val myComment: String = "",
    val selectedScreenshotUri: Uri? = null,
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
)

class CampaignDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
    private val myUserId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CampaignDetailUiState())
    val uiState: StateFlow<CampaignDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = repository.getCampaign(campaignId, forceRefresh = false)
            if (cached != null) {
                val isOwner = myUserId != null && cached.ownerId == myUserId
                _uiState.update {
                    it.copy(
                        campaign = cached,
                        isOwner = isOwner,
                        isLoading = false,
                        isBackgroundSyncing = true,
                    )
                }
            }
            refresh(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.campaign == null) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                val campaign = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                if (campaign == null) {
                    _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false, error = "Không tìm thấy chiến dịch.") }
                    return@launch
                }
                val isMember = if (myUserId != null) repository.isMember(campaignId, myUserId) else false
                // Chỉ coi là "đã check-in" khi check-in đã chính thức (được duyệt), KHÔNG tính
                // dòng check-in tạm tính tạo lúc nộp báo cáo còn chờ chủ app duyệt.
                val checkedIn = if (isMember && myUserId != null) repository.hasConfirmedCheckinToday(campaignId, myUserId) else false
                val isOwner = myUserId != null && campaign.ownerId == myUserId

                val members = repository.getMembers(campaignId)
                val checkins = repository.getCheckins(campaignId)
                val today = LocalDate.now().toString()
                val reports = repository.getCampaignReports(campaignId, isOwner = isOwner, forceRefresh = forceRefresh)
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
                val myLatestReport = repository.getMyLatestReport(campaignId)
                val myTodayReport = repository.getMyTodayReport(campaignId)
                val myReports = if (myUserId != null) reports.filter { it.userId == myUserId } else emptyList()
                val unreadWarnings = if (myUserId != null) repository.getUnreadWarnings() else emptyList()
                val weeklyCount = if (myUserId != null) repository.getWeeklyWarningsCount() else 0
                val myProfile = if (myUserId != null) repository.getMyProfile(forceRefresh = forceRefresh) else null

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = campaign,
                        ownerProfile = ownerProfile,
                        isMember = isMember,
                        isOwner = isOwner,
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
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.campaign == null) friendlyError(e) else null
                    )
                }
            }
        }
    }

    fun adminDeleteCampaign(reason: String? = null, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.adminDeleteCampaign(campaignId, reason)
                _uiState.update { it.copy(isActionLoading = false, message = "Đã gỡ bỏ ứng dụng thành công.") }
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = "Gỡ ứng dụng thất bại: ${friendlyError(e)}") }
            }
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
            return Pair(false, "Bạn cần bấm 'Test app' để mở và trải nghiệm ứng dụng trước khi nộp kết quả.")
        }
        val elapsedSeconds = (System.currentTimeMillis() - startMs) / 1000
        if (elapsedSeconds < 10) {
            return Pair(false, "Thời gian bạn test quá ngắn, yêu cầu tiếp tục test ít nhất 10s")
        }
        return Pair(true, null)
    }

    fun join() {
        if (_uiState.value.isOwner) {
            _uiState.update { it.copy(error = "Chủ app không thể tự tham gia làm tester cho app của chính mình.") }
            return
        }
        runAction("Đã tham gia chiến dịch!") {
            repository.joinCampaign(campaignId)
        }
    }

    fun leave() = runAction("Đã rời chiến dịch.") {
        repository.leaveCampaign(campaignId)
    }

    fun checkin() {
        if (myUserId == null) {
            _uiState.update { it.copy(error = "Vui lòng đăng nhập bằng Google để thực hiện thao tác này.") }
            return
        }
        if (_uiState.value.isOwner) {
            _uiState.update { it.copy(error = "Chủ app không thể tự check-in app của mình để nhận Credit.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, message = null, error = null) }
            try {
                repository.checkinToday(campaignId)
                _uiState.update { it.copy(isActionLoading = false, message = "Check-in thành công! Cảm ơn bạn.") }
            } catch (e: IllegalStateException) {
                if (e.message == "ALREADY_CHECKED_IN") {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            message = "Hôm nay bạn đã check-in rồi. Quay lại ngày mai nhé!",
                        )
                    }
                } else {
                    _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e)) }
            } finally {
                refresh()
            }
        }
    }

    fun onRatingChange(rating: Int) {
        _uiState.update { it.copy(myRating = rating.coerceIn(1, 5)) }
    }

    fun onCommentChange(comment: String) {
        _uiState.update { it.copy(myComment = comment) }
    }

    fun onScreenshotSelected(uri: Uri?) {
        _uiState.update { it.copy(selectedScreenshotUri = uri) }
    }

    fun submitFeedback(context: Context) {
        if (myUserId == null) {
            _uiState.update { it.copy(error = "Vui lòng đăng nhập bằng Google để thực hiện thao tác này.") }
            return
        }
        val state = _uiState.value
        if (state.isOwner) {
            _uiState.update { it.copy(error = "Chủ app không thể tự nộp báo cáo nhận Credit cho app của chính mình.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, message = null, error = null) }
            try {
                if (!state.isMember) {
                    try {
                        repository.joinCampaign(campaignId)
                    } catch (_: Exception) {}
                }

                var screenshotUrl: String? = null
                var imageHash: String? = null
                val uri = state.selectedScreenshotUri
                if (uri != null) {
                    imageHash = ImageHashHelper.computeDHashFromUri(context, uri)
                    screenshotUrl = FirebaseStorageHelper.uploadReportScreenshot(
                        context = context,
                        imageUri = uri,
                        campaignId = campaignId,
                        userId = myUserId,
                    )
                } else {
                    screenshotUrl = state.myTodayReport?.screenshotUrl
                    imageHash = state.myTodayReport?.imageHash
                }

                val pendingToday = state.myTodayReport?.isPending == true
                val alreadyRewarded = (state.checkedInToday || state.myTodayReport?.isApproved == true) && state.myTodayReport?.isRejected != true
                repository.submitFeedback(
                    campaignId = campaignId,
                    rating = state.myRating,
                    comment = state.myComment,
                    screenshotUrl = screenshotUrl,
                    imageHash = imageHash,
                )
                val successMessage = when {
                    alreadyRewarded ->
                        "Đã gửi góp ý bổ sung! (Hôm nay bạn đã nhận thưởng kiểm thử cho app này rồi)."
                    pendingToday ->
                        "Đã cập nhật kết quả kiểm thử! Chủ app có 8 giờ để duyệt (+1 Credit thưởng nếu được duyệt)."
                    else ->
                        "Đã gửi kết quả kiểm thử! Bạn tạm nhận +2 Credit. Chủ app có 8 giờ để duyệt (+1 Credit thưởng nếu được duyệt)."
                }
                val nextCamp = try {
                    repository.getNextUntestedCampaign(campaignId)
                } catch (_: Exception) {
                    null
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        myComment = "",
                        selectedScreenshotUri = null,
                        isMember = true,
                        message = successMessage,
                        showNextAppPrompt = true,
                        nextCampaignToTest = nextCamp,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e)) }
            } finally {
                refresh()
            }
        }
    }

    fun dismissWarning(warningId: String) {
        viewModelScope.launch {
            repository.acknowledgeWarning(warningId)
            _uiState.update { it.copy(unreadWarning = null) }
        }
    }

    fun closeCampaign() = runAction("Đã đóng chiến dịch.") {
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
            _uiState.update { it.copy(error = "Vui lòng đăng nhập bằng Google để thực hiện thao tác này.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, message = null, error = null) }
            try {
                block()
                _uiState.update { it.copy(isActionLoading = false, message = successMessage) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = friendlyError(e)) }
            } finally {
                refresh()
            }
        }
    }
}
