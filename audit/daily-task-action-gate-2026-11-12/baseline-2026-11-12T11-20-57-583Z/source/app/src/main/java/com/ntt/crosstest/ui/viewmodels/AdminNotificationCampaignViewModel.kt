package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminCampaignRecipientItem
import com.ntt.crosstest.data.AdminMemberItem
import com.ntt.crosstest.data.AdminNotificationCampaign
import com.ntt.crosstest.data.AdminNotificationCampaignDetailResponse
import com.ntt.crosstest.data.AdminNotificationCampaignRepository
import com.ntt.crosstest.data.AdminNotificationSchedulerHealth
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class NotificationAdminTab {
    HISTORY, COMPOSER, MONITOR
}

sealed interface NotificationActionDialog {
    val ownerUid: String?
    val isRetry: Boolean

    data class Schedule(
        val campaign: AdminNotificationCampaign,
        val scheduledDate: String = "",
        val scheduledTime: String = "",
        val reason: String = "",
        val requestId: String = UUID.randomUUID().toString(),
        override val isRetry: Boolean = false,
        override val ownerUid: String? = null,
    ) : NotificationActionDialog

    data class SendNow(
        val campaign: AdminNotificationCampaign,
        val reason: String = "",
        val requestId: String = UUID.randomUUID().toString(),
        override val isRetry: Boolean = false,
        override val ownerUid: String? = null,
    ) : NotificationActionDialog

    data class Cancel(
        val campaign: AdminNotificationCampaign,
        val reason: String = "",
        val requestId: String = UUID.randomUUID().toString(),
        override val isRetry: Boolean = false,
        override val ownerUid: String? = null,
    ) : NotificationActionDialog

    data object ProcessDue : NotificationActionDialog {
        override val ownerUid: String? = null
        override val isRetry: Boolean = false
    }
}

data class AdminNotificationCampaignUiState(
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
    val activeTab: NotificationAdminTab = NotificationAdminTab.HISTORY,
    val error: String? = null,
    val infoMessage: String? = null,
    val isActionInProgress: Boolean = false,

    // Tab 1: History List
    val campaigns: List<AdminNotificationCampaign> = emptyList(),
    val isHistoryLoading: Boolean = false,
    val isHistoryLoadingMore: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyNextCursor: String? = null,
    val filterStatus: String? = null,
    val filterAudience: String? = null,

    // Tab 2: Composer / Draft Editor
    val editingCampaignId: String? = null,
    val editingRevision: Int = 1,
    val draftTitleVi: String = "",
    val draftTitleEn: String = "",
    val draftBodyVi: String = "",
    val draftBodyEn: String = "",
    val draftLocale: String = "vi",
    val draftAudienceType: String = "all",
    val draftTargetRole: String = "member",
    val selectedUsers: List<AdminMemberItem> = emptyList(),
    val draftReason: String = "",
    val isDraftSaving: Boolean = false,
    val draftMutationRequestId: String = "",

    // User Search for Selected Users
    val userSearchQuery: String = "",
    val userSearchResults: List<AdminMemberItem> = emptyList(),
    val isUserSearching: Boolean = false,
    val isUserSearchLoadingMore: Boolean = false,
    val userSearchHasMore: Boolean = false,
    val userSearchNextCreatedAt: String? = null,
    val userSearchNextId: String? = null,

    // Live Preview
    val previewRecipientCount: Int = 0,
    val previewViTitle: String = "",
    val previewViBody: String = "",
    val previewEnTitle: String = "",
    val previewEnBody: String = "",
    val previewEffectiveTitle: String = "",
    val previewEffectiveBody: String = "",
    val isPreviewLoading: Boolean = false,

    // Campaign Details
    val selectedDetailCampaign: AdminNotificationCampaignDetailResponse? = null,
    val isDetailLoading: Boolean = false,
    val detailRecipients: List<AdminCampaignRecipientItem> = emptyList(),
    val isDetailRecipientsLoadingMore: Boolean = false,
    val detailRecipientsHasMore: Boolean = false,
    val detailRecipientsNextCursor: String? = null,
    val detailRecipientsFilterStatus: String? = null,

    // Tab 3: Scheduler & Monitor
    val schedulerHealth: AdminNotificationSchedulerHealth? = null,
    val isSchedulerHealthLoading: Boolean = false,
    val isProcessingDue: Boolean = false,

    // Active Dialogs
    val activeDialog: NotificationActionDialog? = null,
)

open class AdminNotificationCampaignViewModel(
    private val repository: AdminNotificationCampaignRepository,
    private val authRepository: AuthRepository? = null,
    private val localizer: Localizer? = null,
    private val savedStateHandle: SavedStateHandle? = null,
    private val currentUserIdProvider: (() -> String?)? = null,
    private val sessionGenProvider: (() -> Long)? = null,
    private val clock: (() -> Instant)? = null,
) : ViewModel() {

    private var activeUserId: String? = null
    private var activeSessionGen: Long = 0L

    private val sessionEpoch = AtomicLong(1L)
    private val historyEpoch = AtomicLong(0L)
    private val userSearchEpoch = AtomicLong(0L)
    private val previewEpoch = AtomicLong(0L)
    private val dialogPreviewEpoch = AtomicLong(0L)
    private val detailEpoch = AtomicLong(0L)
    private val recipientEpoch = AtomicLong(0L)
    private val healthEpoch = AtomicLong(0L)
    private val actionEpoch = AtomicLong(0L)

    private val currentActionToken = AtomicLong(0L)

    private val _uiState = MutableStateFlow(AdminNotificationCampaignUiState())
    val uiState: StateFlow<AdminNotificationCampaignUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var searchJob: Job? = null

    init {
        val initialUid = currentUserIdProvider?.invoke() ?: authRepository?.currentUserId()
        val initialGen = sessionGenProvider?.invoke() ?: SessionCacheManager.currentGeneration()
        activeUserId = initialUid
        activeSessionGen = initialGen

        _uiState.update {
            it.copy(
                currentAdminId = initialUid,
                sessionGeneration = initialGen,
                draftMutationRequestId = UUID.randomUUID().toString(),
            )
        }

        initialUid?.let {
            restoreDraftFromHandle(it)
            restoreUnresolvedActionFromHandle(it)
        }

        observeSession()

        if (initialUid != null) {
            loadHistory()
            loadSchedulerHealth()
        }
    }

    private fun now(): Instant = clock?.invoke() ?: Instant.now()

    private fun observeSession() {
        val sessionFlow = authRepository?.sessionStatus ?: return
        viewModelScope.launch {
            sessionFlow.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val newUid = currentUserIdProvider?.invoke() ?: authRepository.currentUserId()
                        val newGen = sessionGenProvider?.invoke() ?: SessionCacheManager.currentGeneration()
                        if (newUid != activeUserId || newGen != activeSessionGen) {
                            handleSessionReset(newUid, newGen)
                            if (newUid != null) {
                                loadHistory()
                                loadSchedulerHealth()
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val newGen = sessionGenProvider?.invoke() ?: SessionCacheManager.currentGeneration()
                        handleSessionReset(null, newGen)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Synchronously checks if current user or generation has changed before any operation.
     * Returns true if session reset occurred.
     */
    fun reconcileSession(): Boolean {
        val curUid = currentUserIdProvider?.invoke() ?: authRepository?.currentUserId()
        val curGen = sessionGenProvider?.invoke() ?: SessionCacheManager.currentGeneration()
        if (curUid != activeUserId || curGen != activeSessionGen) {
            handleSessionReset(curUid, curGen)
            return true
        }
        return false
    }

    private fun handleSessionReset(newUid: String?, newGen: Long) {
        val oldUid = activeUserId
        if (oldUid != null) {
            clearOwnerFromHandle(oldUid)
        }

        activeUserId = newUid
        activeSessionGen = newGen

        sessionEpoch.incrementAndGet()
        historyEpoch.incrementAndGet()
        userSearchEpoch.incrementAndGet()
        previewEpoch.incrementAndGet()
        dialogPreviewEpoch.incrementAndGet()
        detailEpoch.incrementAndGet()
        recipientEpoch.incrementAndGet()
        healthEpoch.incrementAndGet()
        actionEpoch.incrementAndGet()

        currentActionToken.set(0L)
        searchJob?.cancel()
        previewJob?.cancel()

        val newDraftReqId = UUID.randomUUID().toString()

        _uiState.update {
            AdminNotificationCampaignUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                draftMutationRequestId = newDraftReqId,
                activeTab = NotificationAdminTab.HISTORY,
            )
        }

        newUid?.let {
            restoreDraftFromHandle(it)
            restoreUnresolvedActionFromHandle(it)
        }
    }

    /**
     * Rigorous validation of live auth/provider UID and SessionCacheManager generation on EVERY completion.
     */
    private fun isRequestValid(
        reqOwner: String?,
        reqGen: Long,
        reqSessionEpoch: Long,
        reqStreamEpoch: Long,
        streamEpoch: AtomicLong,
    ): Boolean {
        val liveOwner = currentUserIdProvider?.invoke() ?: authRepository?.currentUserId()
        val liveGen = sessionGenProvider?.invoke() ?: SessionCacheManager.currentGeneration()

        if (liveOwner != reqOwner || liveGen != reqGen) return false
        if (activeUserId != reqOwner || activeSessionGen != reqGen) return false
        if (sessionEpoch.get() != reqSessionEpoch) return false
        if (streamEpoch.get() != reqStreamEpoch) return false
        return true
    }

    // ---------------------------------------------------------------- SavedStateHandle Persistence
    private fun saveDraftToHandle(owner: String) {
        val handle = savedStateHandle ?: return
        val s = _uiState.value
        handle["${owner}_draft_id"] = s.editingCampaignId
        handle["${owner}_draft_revision"] = s.editingRevision
        handle["${owner}_draft_title_vi"] = s.draftTitleVi
        handle["${owner}_draft_title_en"] = s.draftTitleEn
        handle["${owner}_draft_body_vi"] = s.draftBodyVi
        handle["${owner}_draft_body_en"] = s.draftBodyEn
        handle["${owner}_draft_locale"] = s.draftLocale
        handle["${owner}_draft_audience_type"] = s.draftAudienceType
        handle["${owner}_draft_target_role"] = s.draftTargetRole
        handle["${owner}_draft_reason"] = s.draftReason
        handle["${owner}_draft_request_id"] = s.draftMutationRequestId
        handle["${owner}_selected_user_ids"] = ArrayList(s.selectedUsers.map { it.id })
    }

    private fun restoreDraftFromHandle(owner: String) {
        val handle = savedStateHandle ?: return
        val draftReqId = handle.get<String>("${owner}_draft_request_id")
        if (!draftReqId.isNullOrBlank()) {
            val draftId = handle.get<String>("${owner}_draft_id")
            val draftRev = handle.get<Int>("${owner}_draft_revision") ?: 1
            val tVi = handle.get<String>("${owner}_draft_title_vi").orEmpty()
            val tEn = handle.get<String>("${owner}_draft_title_en").orEmpty()
            val bVi = handle.get<String>("${owner}_draft_body_vi").orEmpty()
            val bEn = handle.get<String>("${owner}_draft_body_en").orEmpty()
            val loc = handle.get<String>("${owner}_draft_locale") ?: "vi"
            val aud = handle.get<String>("${owner}_draft_audience_type") ?: "all"
            val role = handle.get<String>("${owner}_draft_target_role") ?: "member"
            val reason = handle.get<String>("${owner}_draft_reason").orEmpty()

            val savedUserIds = handle.get<ArrayList<String>>("${owner}_selected_user_ids") ?: arrayListOf()
            val restoredUsers = savedUserIds.map { uid ->
                AdminMemberItem(
                    id = uid,
                    email = uid,
                    displayName = uid.take(8),
                    role = "member"
                )
            }

            _uiState.update {
                it.copy(
                    editingCampaignId = draftId,
                    editingRevision = draftRev,
                    draftTitleVi = tVi,
                    draftTitleEn = tEn,
                    draftBodyVi = bVi,
                    draftBodyEn = bEn,
                    draftLocale = loc,
                    draftAudienceType = aud,
                    draftTargetRole = role,
                    draftReason = reason,
                    draftMutationRequestId = draftReqId,
                    selectedUsers = restoredUsers,
                )
            }
            triggerPreview()
        }
    }

    private fun saveUnresolvedActionToHandle(owner: String, dialog: NotificationActionDialog) {
        val handle = savedStateHandle ?: return
        when (dialog) {
            is NotificationActionDialog.Schedule -> {
                handle["${owner}_unresolved_type"] = "schedule"
                handle["${owner}_unresolved_camp_id"] = dialog.campaign.id
                handle["${owner}_unresolved_revision"] = dialog.campaign.revision
                handle["${owner}_unresolved_title_vi"] = dialog.campaign.titleVi
                handle["${owner}_unresolved_title_en"] = dialog.campaign.titleEn
                handle["${owner}_unresolved_recipients"] = dialog.campaign.totalRecipients
                handle["${owner}_unresolved_date"] = dialog.scheduledDate
                handle["${owner}_unresolved_time"] = dialog.scheduledTime
                handle["${owner}_unresolved_reason"] = dialog.reason
                handle["${owner}_unresolved_req_id"] = dialog.requestId
                handle["${owner}_unresolved_owner"] = owner
            }
            is NotificationActionDialog.SendNow -> {
                handle["${owner}_unresolved_type"] = "send_now"
                handle["${owner}_unresolved_camp_id"] = dialog.campaign.id
                handle["${owner}_unresolved_revision"] = dialog.campaign.revision
                handle["${owner}_unresolved_title_vi"] = dialog.campaign.titleVi
                handle["${owner}_unresolved_title_en"] = dialog.campaign.titleEn
                handle["${owner}_unresolved_recipients"] = dialog.campaign.totalRecipients
                handle["${owner}_unresolved_reason"] = dialog.reason
                handle["${owner}_unresolved_req_id"] = dialog.requestId
                handle["${owner}_unresolved_owner"] = owner
            }
            is NotificationActionDialog.Cancel -> {
                handle["${owner}_unresolved_type"] = "cancel"
                handle["${owner}_unresolved_camp_id"] = dialog.campaign.id
                handle["${owner}_unresolved_revision"] = dialog.campaign.revision
                handle["${owner}_unresolved_title_vi"] = dialog.campaign.titleVi
                handle["${owner}_unresolved_title_en"] = dialog.campaign.titleEn
                handle["${owner}_unresolved_reason"] = dialog.reason
                handle["${owner}_unresolved_req_id"] = dialog.requestId
                handle["${owner}_unresolved_owner"] = owner
            }
            else -> clearUnresolvedActionFromHandle(owner)
        }
    }

    private fun clearUnresolvedActionFromHandle(owner: String) {
        val handle = savedStateHandle ?: return
        handle.remove<String>("${owner}_unresolved_type")
        handle.remove<String>("${owner}_unresolved_camp_id")
        handle.remove<Int>("${owner}_unresolved_revision")
        handle.remove<String>("${owner}_unresolved_title_vi")
        handle.remove<String>("${owner}_unresolved_title_en")
        handle.remove<Int>("${owner}_unresolved_recipients")
        handle.remove<String>("${owner}_unresolved_date")
        handle.remove<String>("${owner}_unresolved_time")
        handle.remove<String>("${owner}_unresolved_reason")
        handle.remove<String>("${owner}_unresolved_req_id")
        handle.remove<String>("${owner}_unresolved_owner")
    }

    private fun restoreUnresolvedActionFromHandle(owner: String) {
        val handle = savedStateHandle ?: return
        val type = handle.get<String>("${owner}_unresolved_type") ?: return
        val campId = handle.get<String>("${owner}_unresolved_camp_id") ?: return
        val revision = handle.get<Int>("${owner}_unresolved_revision") ?: 1
        val titleVi = handle.get<String>("${owner}_unresolved_title_vi").orEmpty()
        val titleEn = handle.get<String>("${owner}_unresolved_title_en").orEmpty()
        val totalRecipients = handle.get<Int>("${owner}_unresolved_recipients") ?: 0
        val reason = handle.get<String>("${owner}_unresolved_reason").orEmpty()
        val reqId = handle.get<String>("${owner}_unresolved_req_id") ?: return

        val dummyCamp = AdminNotificationCampaign(
            id = campId,
            titleVi = titleVi,
            titleEn = titleEn,
            bodyVi = "",
            bodyEn = "",
            defaultLocale = "vi",
            audienceType = "all",
            status = if (type == "schedule") "draft" else "queued",
            revision = revision,
            totalRecipients = totalRecipients,
            createdBy = owner,
            createdAt = "",
            updatedAt = "",
        )

        val restoredDialog = when (type) {
            "schedule" -> {
                val dateStr = handle.get<String>("${owner}_unresolved_date").orEmpty()
                val timeStr = handle.get<String>("${owner}_unresolved_time").orEmpty()
                NotificationActionDialog.Schedule(
                    campaign = dummyCamp,
                    scheduledDate = dateStr,
                    scheduledTime = timeStr,
                    reason = reason,
                    requestId = reqId,
                    isRetry = true,
                    ownerUid = owner,
                )
            }
            "send_now" -> {
                NotificationActionDialog.SendNow(
                    campaign = dummyCamp,
                    reason = reason,
                    requestId = reqId,
                    isRetry = true,
                    ownerUid = owner,
                )
            }
            "cancel" -> {
                NotificationActionDialog.Cancel(
                    campaign = dummyCamp,
                    reason = reason,
                    requestId = reqId,
                    isRetry = true,
                    ownerUid = owner,
                )
            }
            else -> null
        }

        if (restoredDialog != null) {
            _uiState.update { it.copy(activeDialog = restoredDialog) }
        }
    }

    private fun clearOwnerFromHandle(owner: String) {
        val handle = savedStateHandle ?: return
        handle.remove<String>("${owner}_draft_id")
        handle.remove<Int>("${owner}_draft_revision")
        handle.remove<String>("${owner}_draft_title_vi")
        handle.remove<String>("${owner}_draft_title_en")
        handle.remove<String>("${owner}_draft_body_vi")
        handle.remove<String>("${owner}_draft_body_en")
        handle.remove<String>("${owner}_draft_locale")
        handle.remove<String>("${owner}_draft_audience_type")
        handle.remove<String>("${owner}_draft_target_role")
        handle.remove<String>("${owner}_draft_reason")
        handle.remove<String>("${owner}_draft_request_id")
        handle.remove<ArrayList<String>>("${owner}_selected_user_ids")
        clearUnresolvedActionFromHandle(owner)
    }

    // ---------------------------------------------------------------- Error Localization Helper
    private fun friendlyNotificationError(e: Throwable): String {
        val raw = e.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            "revision_conflict" in lower -> getString(R.string.admin_notif_error_revision_conflict)
            "request_id_conflict" in lower -> getString(R.string.admin_notif_error_request_conflict)
            "invalid_title_vi" in lower || "invalid_title_en" in lower -> getString(R.string.admin_notif_error_title_required)
            "invalid_body_vi" in lower || "invalid_body_en" in lower -> getString(R.string.admin_notif_error_body_required)
            "empty_selected_users" in lower -> getString(R.string.admin_notif_error_selected_users_empty)
            "schedule_time_must_be_in_future" in lower -> getString(R.string.admin_notif_schedule_must_be_future)
            "cannot_cancel_in_flight_or_past_campaign" in lower -> getString(R.string.admin_notif_error_cancel_in_flight)
            "not_authenticated" in lower || "unauthorized" in lower -> getString(R.string.admin_notif_error_not_authenticated)
            else -> getString(R.string.admin_notif_error_generic, raw)
        }
    }

    private fun getString(resId: Int, vararg args: Any): String {
        return localizer?.get(resId, *args) ?: when (resId) {
            R.string.admin_notif_error_revision_conflict -> "This campaign has been updated by another admin. Your current draft was preserved; please re-verify and save."
            R.string.admin_notif_error_request_conflict -> "Request conflict: request ID was reused with a different action or payload."
            R.string.admin_notif_error_title_required -> "Both Vietnamese and English titles are required (1–200 characters)"
            R.string.admin_notif_error_body_required -> "Both Vietnamese and English message bodies are required (1–2000 characters)"
            R.string.admin_notif_error_selected_users_empty -> "Please select at least one recipient user"
            R.string.admin_notif_schedule_must_be_future -> "Scheduled time must be strictly in the future"
            R.string.admin_notif_error_cancel_in_flight -> "Cannot cancel campaign that is currently sending, completed, or already due."
            R.string.admin_notif_error_not_authenticated -> "Session expired. Please sign in again."
            R.string.admin_notif_error_date_invalid -> "Invalid date or time format. Please use YYYY-MM-DD and HH:mm."
            R.string.admin_notif_success_draft_saved -> "Campaign draft saved successfully"
            R.string.admin_notif_success_scheduled -> "Campaign scheduled successfully"
            R.string.admin_notif_success_sent -> "Campaign queued for immediate sending"
            R.string.admin_notif_success_cancelled -> "Campaign cancelled successfully"
            R.string.admin_notif_replayed_note -> "(Replayed from previous request)"
            R.string.admin_notif_cancel_reason_label -> "Cancellation reason (required)"
            else -> if (args.isNotEmpty()) args[0].toString() else ""
        }
    }

    // ---------------------------------------------------------------- Navigation & Tabs
    fun selectTab(tab: NotificationAdminTab) {
        if (reconcileSession()) return
        _uiState.update { it.copy(activeTab = tab, error = null, infoMessage = null) }
        when (tab) {
            NotificationAdminTab.HISTORY -> loadHistory()
            NotificationAdminTab.COMPOSER -> triggerPreview()
            NotificationAdminTab.MONITOR -> loadSchedulerHealth()
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(error = null, infoMessage = null) }
    }

    // ---------------------------------------------------------------- History Stream
    fun setHistoryFilters(status: String?, audience: String?) {
        if (reconcileSession()) return
        _uiState.update {
            it.copy(
                filterStatus = status,
                filterAudience = audience,
                historyNextCursor = null,
                historyHasMore = false,
                isHistoryLoadingMore = false,
            )
        }
        loadHistory()
    }

    fun loadHistory(clearError: Boolean = true) {
        if (reconcileSession()) return
        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = historyEpoch.incrementAndGet()

        val status = _uiState.value.filterStatus
        val audience = _uiState.value.filterAudience

        _uiState.update {
            it.copy(
                isHistoryLoading = true,
                isHistoryLoadingMore = false,
                historyHasMore = false,
                historyNextCursor = null,
                error = if (clearError) null else it.error,
            )
        }

        viewModelScope.launch {
            try {
                val envelope = repository.listCampaigns(
                    status = status,
                    audienceType = audience,
                    limit = 20,
                    cursor = null,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, historyEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        campaigns = envelope.items,
                        historyHasMore = envelope.hasMore,
                        historyNextCursor = envelope.nextCursor,
                        isHistoryLoading = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, historyEpoch)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHistoryLoading = false,
                        error = friendlyNotificationError(e),
                    )
                }
            }
        }
    }

    fun loadMoreHistory() {
        if (reconcileSession()) return
        val state = _uiState.value
        if (state.isHistoryLoading || state.isHistoryLoadingMore || !state.historyHasMore) return
        val cursor = state.historyNextCursor ?: return

        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = historyEpoch.get()
        val status = state.filterStatus
        val audience = state.filterAudience

        _uiState.update { it.copy(isHistoryLoadingMore = true) }
        viewModelScope.launch {
            try {
                val envelope = repository.listCampaigns(
                    status = status,
                    audienceType = audience,
                    limit = 20,
                    cursor = cursor,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, historyEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        campaigns = it.campaigns + envelope.items,
                        historyHasMore = envelope.hasMore,
                        historyNextCursor = envelope.nextCursor,
                        isHistoryLoadingMore = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, historyEpoch)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHistoryLoadingMore = false,
                        error = friendlyNotificationError(e),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- Composer & Form Editing
    fun onTitleViChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftTitleVi = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreviewDebounced()
    }

    fun onTitleEnChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftTitleEn = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreviewDebounced()
    }

    fun onBodyViChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftBodyVi = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreviewDebounced()
    }

    fun onBodyEnChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftBodyEn = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreviewDebounced()
    }

    fun onLocaleChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftLocale = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    fun onAudienceTypeChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftAudienceType = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    fun onTargetRoleChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftTargetRole = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    fun onReasonChange(v: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(draftReason = v) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
    }

    private fun invalidateDraftIdempotency() {
        _uiState.update { it.copy(draftMutationRequestId = UUID.randomUUID().toString()) }
    }

    fun resetComposer() {
        if (reconcileSession()) return
        val newReqId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                editingCampaignId = null,
                editingRevision = 1,
                draftTitleVi = "",
                draftTitleEn = "",
                draftBodyVi = "",
                draftBodyEn = "",
                draftLocale = "vi",
                draftAudienceType = "all",
                draftTargetRole = "member",
                selectedUsers = emptyList(),
                draftReason = "",
                draftMutationRequestId = newReqId,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                error = null,
                infoMessage = null,
            )
        }
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    fun editDraft(campaign: AdminNotificationCampaign) {
        if (reconcileSession()) return
        val newReqId = UUID.randomUUID().toString()
        _uiState.update { state ->
            val knownUsersById = (state.selectedUsers + state.userSearchResults).associateBy { it.id }
            val restoredSelectedUsers = if (campaign.audienceType == "selected_users") {
                campaign.selectedUserIds.map { uid ->
                    knownUsersById[uid] ?: AdminMemberItem(
                        id = uid,
                        email = uid,
                        displayName = uid.take(8),
                        role = "member",
                    )
                }
            } else {
                emptyList()
            }
            state.copy(
                activeTab = NotificationAdminTab.COMPOSER,
                editingCampaignId = campaign.id,
                editingRevision = campaign.revision,
                draftTitleVi = campaign.titleVi,
                draftTitleEn = campaign.titleEn,
                draftBodyVi = campaign.bodyVi,
                draftBodyEn = campaign.bodyEn,
                draftLocale = campaign.defaultLocale,
                draftAudienceType = campaign.audienceType,
                draftTargetRole = campaign.targetRole ?: "member",
                selectedUsers = restoredSelectedUsers,
                draftReason = "",
                draftMutationRequestId = newReqId,
                error = null,
            )
        }
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    // ---------------------------------------------------------------- Member Search for Audience
    fun onUserSearchQueryChange(query: String) {
        if (reconcileSession()) return
        _uiState.update { it.copy(userSearchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            searchUsers(query)
        }
    }

    private fun searchUsers(query: String) {
        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = userSearchEpoch.incrementAndGet()

        _uiState.update {
            it.copy(
                isUserSearching = true,
                isUserSearchLoadingMore = false,
                userSearchNextCreatedAt = null,
                userSearchNextId = null,
                userSearchHasMore = false,
            )
        }
        viewModelScope.launch {
            try {
                val envelope = repository.searchMembers(
                    query = query.trim().ifBlank { null },
                    limit = 20,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, userSearchEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        userSearchResults = envelope.items,
                        userSearchHasMore = envelope.hasMore,
                        userSearchNextCreatedAt = envelope.nextCursor?.createdAt,
                        userSearchNextId = envelope.nextCursor?.id,
                        isUserSearching = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, userSearchEpoch)) {
                    return@launch
                }
                _uiState.update { it.copy(isUserSearching = false) }
            }
        }
    }

    fun loadMoreUserSearch() {
        if (reconcileSession()) return
        val state = _uiState.value
        if (state.isUserSearching || state.isUserSearchLoadingMore || !state.userSearchHasMore) return

        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = userSearchEpoch.get()

        _uiState.update { it.copy(isUserSearchLoadingMore = true) }
        viewModelScope.launch {
            try {
                val envelope = repository.searchMembers(
                    query = state.userSearchQuery.trim().ifBlank { null },
                    limit = 20,
                    cursorCreatedAt = state.userSearchNextCreatedAt,
                    cursorId = state.userSearchNextId,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, userSearchEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        userSearchResults = it.userSearchResults + envelope.items,
                        userSearchHasMore = envelope.hasMore,
                        userSearchNextCreatedAt = envelope.nextCursor?.createdAt,
                        userSearchNextId = envelope.nextCursor?.id,
                        isUserSearchLoadingMore = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, userSearchEpoch)) {
                    return@launch
                }
                _uiState.update { it.copy(isUserSearchLoadingMore = false) }
            }
        }
    }

    fun addSelectedUser(user: AdminMemberItem) {
        if (reconcileSession()) return
        val current = _uiState.value.selectedUsers
        if (current.any { it.id == user.id }) return
        _uiState.update { it.copy(selectedUsers = current + user) }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    fun removeSelectedUser(userId: String) {
        if (reconcileSession()) return
        _uiState.update {
            it.copy(selectedUsers = it.selectedUsers.filterNot { u -> u.id == userId })
        }
        invalidateDraftIdempotency()
        activeUserId?.let { saveDraftToHandle(it) }
        triggerPreview()
    }

    // ---------------------------------------------------------------- Live Preview
    private fun triggerPreviewDebounced() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(400)
            triggerPreview()
        }
    }

    fun triggerPreview() {
        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = previewEpoch.incrementAndGet()

        val state = _uiState.value
        val audience = state.draftAudienceType
        val role = if (audience == "role") state.draftTargetRole else null
        val selectedIds = if (audience == "selected_users") state.selectedUsers.map { it.id } else null

        _uiState.update { it.copy(isPreviewLoading = true) }
        viewModelScope.launch {
            try {
                val preview = repository.previewCampaign(
                    campaignId = state.editingCampaignId,
                    audienceType = audience,
                    targetRole = role,
                    selectedUserIds = selectedIds,
                    defaultLocale = state.draftLocale,
                    titleVi = state.draftTitleVi,
                    titleEn = state.draftTitleEn,
                    bodyVi = state.draftBodyVi,
                    bodyEn = state.draftBodyEn,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, previewEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        previewRecipientCount = preview.recipientCount,
                        previewViTitle = preview.previewVi?.title.orEmpty(),
                        previewViBody = preview.previewVi?.body.orEmpty(),
                        previewEnTitle = preview.previewEn?.title.orEmpty(),
                        previewEnBody = preview.previewEn?.body.orEmpty(),
                        previewEffectiveTitle = preview.previewEffective?.title.orEmpty(),
                        previewEffectiveBody = preview.previewEffective?.body.orEmpty(),
                        isPreviewLoading = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, previewEpoch)) {
                    return@launch
                }
                _uiState.update { it.copy(isPreviewLoading = false) }
            }
        }
    }

    // ---------------------------------------------------------------- Draft Mutations
    fun saveDraft() {
        if (reconcileSession()) return
        if (activeUserId == null) return

        val state = _uiState.value
        if (state.isActionInProgress || state.isDraftSaving) return

        if (state.draftTitleVi.isBlank() || state.draftTitleEn.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.admin_notif_error_title_required)) }
            return
        }
        if (state.draftBodyVi.isBlank() || state.draftBodyEn.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.admin_notif_error_body_required)) }
            return
        }
        if (state.draftAudienceType == "selected_users" && state.selectedUsers.isEmpty()) {
            _uiState.update { it.copy(error = getString(R.string.admin_notif_error_selected_users_empty)) }
            return
        }

        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val token = currentActionToken.incrementAndGet()
        val actionEp = actionEpoch.incrementAndGet()

        val requestId = state.draftMutationRequestId.ifBlank { UUID.randomUUID().toString() }
        val isEditing = state.editingCampaignId != null

        val snapshotTitleVi = state.draftTitleVi
        val snapshotTitleEn = state.draftTitleEn
        val snapshotBodyVi = state.draftBodyVi
        val snapshotBodyEn = state.draftBodyEn
        val snapshotLocale = state.draftLocale
        val snapshotAudience = state.draftAudienceType
        val snapshotRole = if (state.draftAudienceType == "role") state.draftTargetRole else null
        val snapshotSelectedIds = if (state.draftAudienceType == "selected_users") state.selectedUsers.map { it.id } else null
        val snapshotReason = state.draftReason.ifBlank { null }
        val snapshotRev = state.editingRevision

        // Persist draft snapshot before launch
        activeUserId?.let { saveDraftToHandle(it) }

        _uiState.update { it.copy(isActionInProgress = true, isDraftSaving = true, error = null) }

        viewModelScope.launch {
            try {
                val resp = if (isEditing) {
                    repository.updateCampaign(
                        requestId = requestId,
                        campaignId = state.editingCampaignId!!,
                        expectedRevision = snapshotRev,
                        titleVi = snapshotTitleVi,
                        titleEn = snapshotTitleEn,
                        bodyVi = snapshotBodyVi,
                        bodyEn = snapshotBodyEn,
                        defaultLocale = snapshotLocale,
                        audienceType = snapshotAudience,
                        targetRole = snapshotRole,
                        selectedUserIds = snapshotSelectedIds,
                        reason = snapshotReason,
                    )
                } else {
                    repository.createCampaign(
                        requestId = requestId,
                        titleVi = snapshotTitleVi,
                        titleEn = snapshotTitleEn,
                        bodyVi = snapshotBodyVi,
                        bodyEn = snapshotBodyEn,
                        defaultLocale = snapshotLocale,
                        audienceType = snapshotAudience,
                        targetRole = snapshotRole,
                        selectedUserIds = snapshotSelectedIds,
                        reason = snapshotReason,
                    )
                }

                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }

                _uiState.update { current ->
                    current.copy(
                        isActionInProgress = false,
                        isDraftSaving = false,
                        editingCampaignId = resp.campaign.id,
                        editingRevision = resp.campaign.revision,
                        draftMutationRequestId = UUID.randomUUID().toString(),
                        infoMessage = if (resp.replayed) {
                            "${getString(R.string.admin_notif_success_draft_saved)} ${getString(R.string.admin_notif_replayed_note)}"
                        } else {
                            getString(R.string.admin_notif_success_draft_saved)
                        },
                    )
                }
                activeUserId?.let { saveDraftToHandle(it) }
                loadHistory(clearError = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        isDraftSaving = false,
                        error = friendlyNotificationError(e),
                    )
                }
                if (e.message?.contains("REVISION_CONFLICT", ignoreCase = true) == true) {
                    loadHistory(clearError = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Lifecycle Operations: Schedule, SendNow, Cancel
    private fun resolveInitialDialogCampaign(campaign: AdminNotificationCampaign): AdminNotificationCampaign {
        if (!campaign.recipientSnapshotTaken && campaign.totalRecipients == 0 &&
            campaign.audienceType == "selected_users" && campaign.selectedUserIds.isNotEmpty()
        ) {
            return campaign.copy(totalRecipients = campaign.selectedUserIds.size)
        }
        return campaign
    }

    private fun refreshDialogRecipientCountIfNeeded(campaign: AdminNotificationCampaign) {
        if (campaign.recipientSnapshotTaken || campaign.totalRecipients > 0) return
        val reqOwner = activeUserId ?: return
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = dialogPreviewEpoch.incrementAndGet()
        viewModelScope.launch {
            try {
                val preview = repository.previewCampaign(campaignId = campaign.id)
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, dialogPreviewEpoch)) {
                    return@launch
                }
                var updatedToSave: NotificationActionDialog? = null
                _uiState.update { state ->
                    val updatedDialog = when (val d = state.activeDialog) {
                        is NotificationActionDialog.Schedule ->
                            if (d.campaign.id == campaign.id) d.copy(campaign = d.campaign.copy(totalRecipients = preview.recipientCount)) else d
                        is NotificationActionDialog.SendNow ->
                            if (d.campaign.id == campaign.id) d.copy(campaign = d.campaign.copy(totalRecipients = preview.recipientCount)) else d
                        else -> d
                    }
                    updatedToSave = updatedDialog
                    state.copy(activeDialog = updatedDialog)
                }
                updatedToSave?.let { saveUnresolvedActionToHandle(reqOwner, it) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Non-fatal: dialog keeps its current recipient count
            }
        }
    }

    fun openScheduleDialog(campaign: AdminNotificationCampaign) {
        if (reconcileSession()) return
        val owner = activeUserId ?: return

        // If an unresolved intent already exists for this exact action, do not discard it!
        val existingDialog = _uiState.value.activeDialog as? NotificationActionDialog.Schedule
        val stableReqId = if (existingDialog != null && existingDialog.isRetry && existingDialog.campaign.id == campaign.id) {
            existingDialog.requestId
        } else {
            UUID.randomUUID().toString()
        }

        val resolvedCampaign = resolveInitialDialogCampaign(campaign)
        val dialog = NotificationActionDialog.Schedule(
            campaign = resolvedCampaign,
            requestId = stableReqId,
            isRetry = existingDialog?.isRetry ?: false,
            ownerUid = owner,
        )
        _uiState.update { it.copy(activeDialog = dialog) }
        saveUnresolvedActionToHandle(owner, dialog)
        refreshDialogRecipientCountIfNeeded(resolvedCampaign)
    }

    fun openSendNowDialog(campaign: AdminNotificationCampaign) {
        if (reconcileSession()) return
        val owner = activeUserId ?: return

        val existingDialog = _uiState.value.activeDialog as? NotificationActionDialog.SendNow
        val stableReqId = if (existingDialog != null && existingDialog.isRetry && existingDialog.campaign.id == campaign.id) {
            existingDialog.requestId
        } else {
            UUID.randomUUID().toString()
        }

        val resolvedCampaign = resolveInitialDialogCampaign(campaign)
        val dialog = NotificationActionDialog.SendNow(
            campaign = resolvedCampaign,
            requestId = stableReqId,
            isRetry = existingDialog?.isRetry ?: false,
            ownerUid = owner,
        )
        _uiState.update { it.copy(activeDialog = dialog) }
        saveUnresolvedActionToHandle(owner, dialog)
        refreshDialogRecipientCountIfNeeded(resolvedCampaign)
    }

    fun openCancelDialog(campaign: AdminNotificationCampaign) {
        if (reconcileSession()) return
        val owner = activeUserId ?: return

        val existingDialog = _uiState.value.activeDialog as? NotificationActionDialog.Cancel
        val stableReqId = if (existingDialog != null && existingDialog.isRetry && existingDialog.campaign.id == campaign.id) {
            existingDialog.requestId
        } else {
            UUID.randomUUID().toString()
        }

        val dialog = NotificationActionDialog.Cancel(
            campaign = campaign,
            requestId = stableReqId,
            isRetry = existingDialog?.isRetry ?: false,
            ownerUid = owner,
        )
        _uiState.update { it.copy(activeDialog = dialog) }
        saveUnresolvedActionToHandle(owner, dialog)
    }

    fun openProcessDueDialog() {
        if (reconcileSession()) return
        _uiState.update {
            it.copy(activeDialog = NotificationActionDialog.ProcessDue)
        }
    }

    fun dismissDialog() {
        val currentDialog = _uiState.value.activeDialog
        val owner = activeUserId
        // Do not discard an uncertain unresolved intent on simple dialog dismissal!
        if (owner != null && (currentDialog == null || !currentDialog.isRetry)) {
            clearUnresolvedActionFromHandle(owner)
        }
        _uiState.update { it.copy(activeDialog = null) }
    }

    fun onScheduleFieldsChanged(dateStr: String, timeStr: String, reason: String) {
        val dialog = _uiState.value.activeDialog as? NotificationActionDialog.Schedule ?: return
        val owner = activeUserId ?: return
        val payloadChanged = dialog.scheduledDate != dateStr || dialog.scheduledTime != timeStr || dialog.reason != reason
        val newReqId = if (payloadChanged && dialog.isRetry) UUID.randomUUID().toString() else dialog.requestId

        val updated = dialog.copy(
            scheduledDate = dateStr,
            scheduledTime = timeStr,
            reason = reason,
            requestId = newReqId,
            isRetry = if (payloadChanged) false else dialog.isRetry,
        )
        _uiState.update { it.copy(activeDialog = updated) }
        saveUnresolvedActionToHandle(owner, updated)
    }

    fun onSendNowReasonChanged(reason: String) {
        val dialog = _uiState.value.activeDialog as? NotificationActionDialog.SendNow ?: return
        val owner = activeUserId ?: return
        val payloadChanged = dialog.reason != reason
        val newReqId = if (payloadChanged && dialog.isRetry) UUID.randomUUID().toString() else dialog.requestId

        val updated = dialog.copy(
            reason = reason,
            requestId = newReqId,
            isRetry = if (payloadChanged) false else dialog.isRetry,
        )
        _uiState.update { it.copy(activeDialog = updated) }
        saveUnresolvedActionToHandle(owner, updated)
    }

    fun onCancelReasonChanged(reason: String) {
        val dialog = _uiState.value.activeDialog as? NotificationActionDialog.Cancel ?: return
        val owner = activeUserId ?: return
        val payloadChanged = dialog.reason != reason
        val newReqId = if (payloadChanged && dialog.isRetry) UUID.randomUUID().toString() else dialog.requestId

        val updated = dialog.copy(
            reason = reason,
            requestId = newReqId,
            isRetry = if (payloadChanged) false else dialog.isRetry,
        )
        _uiState.update { it.copy(activeDialog = updated) }
        saveUnresolvedActionToHandle(owner, updated)
    }

    fun executeSchedule(
        campaign: AdminNotificationCampaign,
        dateStr: String,
        timeStr: String,
        reason: String?,
        requestId: String? = null,
        dialogOwner: String? = null,
    ) {
        if (reconcileSession()) return
        val currentOwner = activeUserId ?: return

        // Abort if the dialog was triggered under a different owner
        if (dialogOwner != null && dialogOwner != currentOwner) return

        val state = _uiState.value
        if (state.isActionInProgress) return

        val stableReqId = requestId ?: (state.activeDialog as? NotificationActionDialog.Schedule)?.requestId ?: UUID.randomUUID().toString()
        val isRetryIntent = (state.activeDialog as? NotificationActionDialog.Schedule)?.isRetry == true

        // Convert VN time to UTC ISO8601 instant
        val isoInstant: String
        try {
            val vnZone = ZoneId.of("Asia/Ho_Chi_Minh")
            val localDt = LocalDateTime.parse("${dateStr.trim()}T${timeStr.trim()}:00")
            val zoned = localDt.atZone(vnZone)
            val instant = zoned.toInstant()

            // If it's a new confirmation (not retry of ambiguous intent), reject past time
            if (!isRetryIntent && !instant.isAfter(now())) {
                val retryDialog = NotificationActionDialog.Schedule(
                    campaign = campaign,
                    scheduledDate = dateStr,
                    scheduledTime = timeStr,
                    reason = reason.orEmpty(),
                    requestId = stableReqId,
                    isRetry = false,
                    ownerUid = currentOwner,
                )
                _uiState.update {
                    it.copy(
                        error = getString(R.string.admin_notif_schedule_must_be_future),
                        activeDialog = retryDialog,
                    )
                }
                saveUnresolvedActionToHandle(currentOwner, retryDialog)
                return
            }
            isoInstant = DateTimeFormatter.ISO_INSTANT.format(instant)
        } catch (_: Exception) {
            val retryDialog = NotificationActionDialog.Schedule(
                campaign = campaign,
                scheduledDate = dateStr,
                scheduledTime = timeStr,
                reason = reason.orEmpty(),
                requestId = stableReqId,
                isRetry = isRetryIntent,
                ownerUid = currentOwner,
            )
            _uiState.update {
                it.copy(
                    error = getString(R.string.admin_notif_error_date_invalid),
                    activeDialog = retryDialog,
                )
            }
            saveUnresolvedActionToHandle(currentOwner, retryDialog)
            return
        }

        // Persist EXACT confirmed intent to SavedStateHandle BEFORE launching RPC
        val snapshotDialog = NotificationActionDialog.Schedule(
            campaign = campaign,
            scheduledDate = dateStr,
            scheduledTime = timeStr,
            reason = reason.orEmpty(),
            requestId = stableReqId,
            isRetry = true,
            ownerUid = currentOwner,
        )
        saveUnresolvedActionToHandle(currentOwner, snapshotDialog)

        val reqOwner = currentOwner
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val token = currentActionToken.incrementAndGet()
        val actionEp = actionEpoch.incrementAndGet()

        _uiState.update { it.copy(isActionInProgress = true, activeDialog = null, error = null) }

        viewModelScope.launch {
            try {
                val resp = repository.scheduleCampaign(
                    requestId = stableReqId,
                    campaignId = campaign.id,
                    expectedRevision = campaign.revision,
                    scheduledForIso = isoInstant,
                    reason = reason?.ifBlank { null },
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }

                clearUnresolvedActionFromHandle(reqOwner)

                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        infoMessage = if (resp.replayed) {
                            "${getString(R.string.admin_notif_success_scheduled)} ${getString(R.string.admin_notif_replayed_note)}"
                        } else {
                            getString(R.string.admin_notif_success_scheduled)
                        },
                    )
                }
                loadHistory(clearError = false)
                refreshDetailIfOpen(campaign.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        error = friendlyNotificationError(e),
                        activeDialog = snapshotDialog,
                    )
                }
            }
        }
    }

    fun executeSendNow(
        campaign: AdminNotificationCampaign,
        reason: String?,
        requestId: String? = null,
        dialogOwner: String? = null,
    ) {
        if (reconcileSession()) return
        val currentOwner = activeUserId ?: return

        if (dialogOwner != null && dialogOwner != currentOwner) return

        val state = _uiState.value
        if (state.isActionInProgress) return

        val stableReqId = requestId ?: (state.activeDialog as? NotificationActionDialog.SendNow)?.requestId ?: UUID.randomUUID().toString()

        // Persist EXACT confirmed intent to SavedStateHandle BEFORE launching RPC
        val snapshotDialog = NotificationActionDialog.SendNow(
            campaign = campaign,
            reason = reason.orEmpty(),
            requestId = stableReqId,
            isRetry = true,
            ownerUid = currentOwner,
        )
        saveUnresolvedActionToHandle(currentOwner, snapshotDialog)

        val reqOwner = currentOwner
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val token = currentActionToken.incrementAndGet()
        val actionEp = actionEpoch.incrementAndGet()

        _uiState.update { it.copy(isActionInProgress = true, activeDialog = null, error = null) }

        viewModelScope.launch {
            try {
                val resp = repository.sendNowCampaign(
                    requestId = stableReqId,
                    campaignId = campaign.id,
                    expectedRevision = campaign.revision,
                    reason = reason?.ifBlank { null },
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }

                clearUnresolvedActionFromHandle(reqOwner)

                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        infoMessage = if (resp.replayed) {
                            "${getString(R.string.admin_notif_success_sent)} ${getString(R.string.admin_notif_replayed_note)}"
                        } else {
                            getString(R.string.admin_notif_success_sent)
                        },
                    )
                }
                loadHistory(clearError = false)
                refreshDetailIfOpen(campaign.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        error = friendlyNotificationError(e),
                        activeDialog = snapshotDialog,
                    )
                }
            }
        }
    }

    fun executeCancel(
        campaign: AdminNotificationCampaign,
        reason: String,
        requestId: String? = null,
        dialogOwner: String? = null,
    ) {
        if (reconcileSession()) return
        val currentOwner = activeUserId ?: return

        if (dialogOwner != null && dialogOwner != currentOwner) return

        val state = _uiState.value
        if (state.isActionInProgress) return
        if (reason.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.admin_notif_cancel_reason_label)) }
            return
        }

        val stableReqId = requestId ?: (state.activeDialog as? NotificationActionDialog.Cancel)?.requestId ?: UUID.randomUUID().toString()

        // Persist EXACT confirmed intent to SavedStateHandle BEFORE launching RPC
        val snapshotDialog = NotificationActionDialog.Cancel(
            campaign = campaign,
            reason = reason.trim(),
            requestId = stableReqId,
            isRetry = true,
            ownerUid = currentOwner,
        )
        saveUnresolvedActionToHandle(currentOwner, snapshotDialog)

        val reqOwner = currentOwner
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val token = currentActionToken.incrementAndGet()
        val actionEp = actionEpoch.incrementAndGet()

        _uiState.update { it.copy(isActionInProgress = true, activeDialog = null, error = null) }

        viewModelScope.launch {
            try {
                val resp = repository.cancelCampaign(
                    requestId = stableReqId,
                    campaignId = campaign.id,
                    expectedRevision = campaign.revision,
                    reason = reason.trim(),
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }

                clearUnresolvedActionFromHandle(reqOwner)

                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        infoMessage = if (resp.replayed) {
                            "${getString(R.string.admin_notif_success_cancelled)} ${getString(R.string.admin_notif_replayed_note)}"
                        } else {
                            getString(R.string.admin_notif_success_cancelled)
                        },
                    )
                }
                loadHistory(clearError = false)
                refreshDetailIfOpen(campaign.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        error = friendlyNotificationError(e),
                        activeDialog = snapshotDialog,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- Detail View & Recipients
    fun openDetail(campaignId: String) {
        if (reconcileSession()) return
        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqDetailEpoch = detailEpoch.incrementAndGet()
        val reqRecipientEpoch = recipientEpoch.incrementAndGet()

        _uiState.update {
            it.copy(
                isDetailLoading = true,
                selectedDetailCampaign = null,
                detailRecipients = emptyList(),
                detailRecipientsNextCursor = null,
                detailRecipientsHasMore = false,
                isDetailRecipientsLoadingMore = false,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val detail = repository.getCampaignDetail(campaignId, eventsLimit = 50)
                val recipientsEnvelope = repository.listCampaignRecipients(campaignId, limit = 50)

                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqDetailEpoch, detailEpoch) ||
                    recipientEpoch.get() != reqRecipientEpoch) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        selectedDetailCampaign = detail,
                        detailRecipients = recipientsEnvelope.items,
                        detailRecipientsHasMore = recipientsEnvelope.hasMore,
                        detailRecipientsNextCursor = recipientsEnvelope.nextCursor,
                        isDetailLoading = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqDetailEpoch, detailEpoch) ||
                    recipientEpoch.get() != reqRecipientEpoch) {
                    return@launch
                }
                _uiState.update {
                    it.copy(isDetailLoading = false, error = friendlyNotificationError(e))
                }
            }
        }
    }

    fun closeDetail() {
        if (reconcileSession()) return
        detailEpoch.incrementAndGet()
        recipientEpoch.incrementAndGet()

        _uiState.update {
            it.copy(
                selectedDetailCampaign = null,
                detailRecipients = emptyList(),
                detailRecipientsNextCursor = null,
                detailRecipientsHasMore = false,
                isDetailLoading = false,
                isDetailRecipientsLoadingMore = false,
            )
        }
    }

    private fun refreshDetailIfOpen(campaignId: String) {
        if (_uiState.value.selectedDetailCampaign?.campaign?.id == campaignId) {
            openDetail(campaignId)
        }
    }

    fun loadMoreRecipients() {
        if (reconcileSession()) return
        val state = _uiState.value
        val campId = state.selectedDetailCampaign?.campaign?.id ?: return
        if (state.isDetailRecipientsLoadingMore || !state.detailRecipientsHasMore) return
        val cursor = state.detailRecipientsNextCursor ?: return

        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = recipientEpoch.get()

        _uiState.update { it.copy(isDetailRecipientsLoadingMore = true) }

        viewModelScope.launch {
            try {
                val envelope = repository.listCampaignRecipients(
                    campaignId = campId,
                    status = state.detailRecipientsFilterStatus,
                    limit = 50,
                    cursor = cursor,
                )
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, recipientEpoch)) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        detailRecipients = it.detailRecipients + envelope.items,
                        detailRecipientsHasMore = envelope.hasMore,
                        detailRecipientsNextCursor = envelope.nextCursor,
                        isDetailRecipientsLoadingMore = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, recipientEpoch)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(isDetailRecipientsLoadingMore = false, error = friendlyNotificationError(e))
                }
            }
        }
    }

    // ---------------------------------------------------------------- Monitor & Scheduler
    fun loadSchedulerHealth() {
        if (reconcileSession()) return
        val reqOwner = activeUserId
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val reqStreamEpoch = healthEpoch.incrementAndGet()

        _uiState.update { it.copy(isSchedulerHealthLoading = true) }
        viewModelScope.launch {
            try {
                val health = repository.getSchedulerHealth()
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, healthEpoch)) {
                    return@launch
                }
                _uiState.update {
                    it.copy(schedulerHealth = health, isSchedulerHealthLoading = false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, reqStreamEpoch, healthEpoch)) {
                    return@launch
                }
                _uiState.update { it.copy(isSchedulerHealthLoading = false) }
            }
        }
    }

    fun processDueBatches() {
        if (reconcileSession()) return
        val currentOwner = activeUserId ?: return
        val state = _uiState.value
        if (state.isActionInProgress || state.isProcessingDue) return

        val reqOwner = currentOwner
        val reqGen = activeSessionGen
        val reqSessionEpoch = sessionEpoch.get()
        val token = currentActionToken.incrementAndGet()
        val actionEp = actionEpoch.incrementAndGet()

        _uiState.update { it.copy(isProcessingDue = true, isActionInProgress = true, activeDialog = null) }

        viewModelScope.launch {
            try {
                val resp = repository.processDueCampaigns(batchSize = 200)
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isProcessingDue = false,
                        isActionInProgress = false,
                        infoMessage = getString(R.string.admin_notif_process_due_success, resp.campaignsProcessed, resp.notificationsSent),
                    )
                }
                loadSchedulerHealth()
                loadHistory(clearError = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isRequestValid(reqOwner, reqGen, reqSessionEpoch, actionEp, actionEpoch) ||
                    currentActionToken.get() != token) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isProcessingDue = false,
                        isActionInProgress = false,
                        error = friendlyNotificationError(e),
                    )
                }
            }
        }
    }
}
