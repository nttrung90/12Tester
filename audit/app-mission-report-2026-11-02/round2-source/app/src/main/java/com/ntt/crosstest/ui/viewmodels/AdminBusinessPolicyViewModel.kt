package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminCancelBusinessPolicyParams
import com.ntt.crosstest.data.AdminScheduleBusinessPolicyParams
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.BusinessPolicy
import com.ntt.crosstest.data.BusinessPolicyConfigResponse
import com.ntt.crosstest.data.BusinessPolicyHistoryItem
import com.ntt.crosstest.data.BusinessPolicyRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class BusinessPolicyAdminTab {
    ACTIVE, SCHEDULED, EDITOR, HISTORY
}

fun friendlyBusinessPolicyError(e: Throwable, localizer: Localizer): String {
    val msg = e.message.orEmpty()
    val lower = msg.lowercase()
    return when {
        "business_policy_conflict" in lower -> localizer.get(R.string.error_business_policy_conflict)
        "business_policy_price_mismatch" in lower -> localizer.get(R.string.error_business_policy_price_mismatch)
        "business_policy_future_date_required" in lower -> localizer.get(R.string.error_business_policy_future_date_required)
        "business_policy_note_required" in lower -> localizer.get(R.string.error_business_policy_note_required)
        "business_policy_not_found" in lower -> localizer.get(R.string.error_business_policy_not_found)
        "business_policy_history_read_only" in lower -> localizer.get(R.string.error_business_policy_history_read_only)
        "client_update_required" in lower -> localizer.get(R.string.error_client_update_required)
        "request_id_conflict" in lower -> localizer.get(R.string.error_request_id_conflict)
        "insufficient_credits" in lower -> localizer.get(R.string.profile_insufficient_credits)
        else -> friendlyError(e, localizer)
    }
}

private fun isDefinitiveError(e: Throwable): Boolean {
    if (e is IOException) return false
    val msg = e.message.orEmpty().lowercase()
    return "business_policy_conflict" in msg ||
            "business_policy_price_mismatch" in msg ||
            "business_policy_future_date_required" in msg ||
            "business_policy_note_required" in msg ||
            "business_policy_not_found" in msg ||
            "business_policy_history_read_only" in msg ||
            "permission_denied" in msg ||
            "client_update_required" in msg ||
            "request_id_conflict" in msg ||
            "insufficient_credits" in msg ||
            "not_authenticated" in msg ||
            "account_locked" in msg
}

data class AdminBusinessPolicyUiState(
    val activeTab: BusinessPolicyAdminTab = BusinessPolicyAdminTab.ACTIVE,
    val isLoading: Boolean = false,
    val isActionInProgress: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,

    // Policy Config & Active/Scheduled
    val config: BusinessPolicyConfigResponse? = null,
    val activePolicy: BusinessPolicy? = null,
    val futurePolicies: List<BusinessPolicy> = emptyList(),
    val isFutureLoadingMore: Boolean = false,
    val futureHasMore: Boolean = false,
    val futureNextCursor: String? = null,
    val currentRevision: String = "",
    val todayDate: String = "",

    // Editor Draft State
    val draftEffectiveDate: String = "",
    val draftWelcomeCredits: String = "100",
    val draftPenaltyTier1: String = "1",
    val draftPenaltyTier2: String = "2",
    val draftPenaltyTier3: String = "5",
    val draftPenaltyTier4: String = "10",
    val draftPushFee: String = "100",
    val draftSlotFee2: String = "50",
    val draftSlotFee3: String = "100",
    val draftSlotFee4Plus: String = "200",
    val draftSlotRenewalDays: String = "30",
    val draftReportProvisional: String = "10",
    val draftReportReviewTesterBonus: String = "2",
    val draftReportReviewOwnerBonus: String = "2",
    val draftAppUpdateRewardPerClaim: String = "6",
    val draftAppUpdateMaxClaims: String = "20",
    val draftOperatorNote: String = "",
    val draftMutationRequestId: String = UUID.randomUUID().toString(),

    // History
    val historyItems: List<BusinessPolicyHistoryItem> = emptyList(),
    val isHistoryLoading: Boolean = false,
    val isHistoryLoadingMore: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyNextCursor: Long? = null,

    // Cancellation Dialog
    val cancelDialogTarget: BusinessPolicy? = null,
    val cancelReason: String = "",
    val cancelRequestId: String? = null,
    val cancelExpectedRevision: String? = null,

    // Durable Confirmed Intents (Schedule & Cancel)
    val pendingScheduleIntent: AdminScheduleBusinessPolicyParams? = null,
    val pendingCancellationIntent: AdminCancelBusinessPolicyParams? = null,
) {
    val derivedUpdateBudget: Int
        get() {
            val perClaim = draftAppUpdateRewardPerClaim.toIntOrNull() ?: 0
            val maxClaims = draftAppUpdateMaxClaims.toIntOrNull() ?: 0
            return perClaim * maxClaims
        }
}

class AdminBusinessPolicyViewModel(
    private val repository: BusinessPolicyRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val isActionInProgress = AtomicBoolean(false)
    private var activeUserId: String? = authRepository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    private val configSeq = AtomicLong(0L)
    private val futureSeq = AtomicLong(0L)
    private val mutationSeq = AtomicLong(0L)
    private val historySeq = AtomicLong(0L)

    private var lastSchedulePayloadHash: String? = null

    private val _uiState = MutableStateFlow(AdminBusinessPolicyUiState())
    val uiState: StateFlow<AdminBusinessPolicyUiState> = _uiState.asStateFlow()

    init {
        restoreDraftFromSavedState()
        restorePendingScheduleFromSavedState()
        restorePendingCancelFromSavedState()
        loadConfig(keepDraft = hasSavedDraft())

        viewModelScope.launch {
            authRepository.sessionStatus.collect {
                val currentUid = authRepository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != activeUserId || currentGen != activeGeneration) {
                    handleAccountChange(currentUid, currentGen)
                }
            }
        }
    }

    private fun reconcileActiveUser(): String? {
        val currentUid = authRepository.currentUserId()
        val currentGen = SessionCacheManager.currentGeneration()
        if (currentUid != activeUserId || currentGen != activeGeneration) {
            handleAccountChange(currentUid, currentGen)
        }
        return activeUserId
    }

    private fun handleAccountChange(newUid: String?, newGen: Long) {
        configSeq.incrementAndGet()
        futureSeq.incrementAndGet()
        mutationSeq.incrementAndGet()
        historySeq.incrementAndGet()

        activeUserId = newUid
        activeGeneration = newGen
        isActionInProgress.set(false)
        lastSchedulePayloadHash = null

        _uiState.update {
            AdminBusinessPolicyUiState()
        }

        restoreDraftFromSavedState()
        restorePendingScheduleFromSavedState()
        restorePendingCancelFromSavedState()

        loadConfig(keepDraft = hasSavedDraft())
    }

    private fun ownerKey(name: String, uid: String? = activeUserId): String = "bp_${uid.orEmpty()}_$name"
    private fun key(name: String): String = ownerKey(name, activeUserId)

    private fun savePrimitive(name: String, value: String?) {
        val k = key(name)
        if (value != null) {
            savedStateHandle[k] = value
        } else {
            savedStateHandle.remove<String>(k)
        }
    }

    private fun getPrimitive(name: String): String? = savedStateHandle[key(name)]

    private fun hasSavedDraft(): Boolean = getPrimitive("draft_date") != null || getPrimitive("draft_note") != null

    private fun savePendingSchedule(uid: String?, params: AdminScheduleBusinessPolicyParams) {
        if (uid.isNullOrBlank()) return
        val json = Json.encodeToString(params)
        savedStateHandle[ownerKey("pending_schedule_json", uid)] = json
    }

    private fun getPendingSchedule(uid: String?): AdminScheduleBusinessPolicyParams? {
        if (uid.isNullOrBlank()) return null
        val json: String = savedStateHandle[ownerKey("pending_schedule_json", uid)] ?: return null
        return try {
            Json.decodeFromString<AdminScheduleBusinessPolicyParams>(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPendingSchedule(uid: String?) {
        if (uid.isNullOrBlank()) return
        savedStateHandle.remove<String>(ownerKey("pending_schedule_json", uid))
    }

    private fun savePendingCancellation(uid: String?, params: AdminCancelBusinessPolicyParams) {
        if (uid.isNullOrBlank()) return
        val json = Json.encodeToString(params)
        savedStateHandle[ownerKey("pending_cancel_json", uid)] = json
        savePrimitive("cancel_req_id", params.requestId)
        savePrimitive("cancel_date", params.effectiveFrom)
        savePrimitive("cancel_rev", params.expectedRevision)
        savePrimitive("cancel_reason", params.operatorNote)
    }

    private fun getPendingCancellation(uid: String?): AdminCancelBusinessPolicyParams? {
        if (uid.isNullOrBlank()) return null
        val json: String = savedStateHandle[ownerKey("pending_cancel_json", uid)] ?: return null
        return try {
            Json.decodeFromString<AdminCancelBusinessPolicyParams>(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPendingCancellation(uid: String?) {
        if (uid.isNullOrBlank()) return
        savedStateHandle.remove<String>(ownerKey("pending_cancel_json", uid))
        savePrimitive("cancel_req_id", null)
        savePrimitive("cancel_date", null)
        savePrimitive("cancel_rev", null)
        savePrimitive("cancel_reason", null)
    }

    private fun restorePendingScheduleFromSavedState() {
        val pending = getPendingSchedule(activeUserId)
        if (pending != null) {
            _uiState.update {
                it.copy(pendingScheduleIntent = pending)
            }
        }
    }

    private fun restorePendingCancelFromSavedState() {
        val pending = getPendingCancellation(activeUserId)
        if (pending != null) {
            _uiState.update {
                it.copy(
                    cancelDialogTarget = BusinessPolicy(effectiveFrom = pending.effectiveFrom),
                    cancelReason = pending.operatorNote,
                    cancelRequestId = pending.requestId,
                    cancelExpectedRevision = pending.expectedRevision,
                    pendingCancellationIntent = pending,
                )
            }
        }
    }

    private fun restoreDraftFromSavedState() {
        val savedDate = getPrimitive("draft_date") ?: ""
        val savedWelcome = getPrimitive("draft_welcome") ?: "100"
        val savedT1 = getPrimitive("draft_t1") ?: "1"
        val savedT2 = getPrimitive("draft_t2") ?: "2"
        val savedT3 = getPrimitive("draft_t3") ?: "5"
        val savedT4 = getPrimitive("draft_t4") ?: "10"
        val savedPush = getPrimitive("draft_push") ?: "100"
        val savedS2 = getPrimitive("draft_s2") ?: "50"
        val savedS3 = getPrimitive("draft_s3") ?: "100"
        val savedS4 = getPrimitive("draft_s4") ?: "200"
        val savedDays = getPrimitive("draft_days") ?: "30"
        val savedProv = getPrimitive("draft_prov") ?: "10"
        val savedRTester = getPrimitive("draft_r_tester") ?: "2"
        val savedROwner = getPrimitive("draft_r_owner") ?: "2"
        val savedUpdReward = getPrimitive("draft_upd_reward") ?: "6"
        val savedUpdMax = getPrimitive("draft_upd_max") ?: "20"
        val savedNote = getPrimitive("draft_note") ?: ""
        val savedReqId = getPrimitive("draft_req_id") ?: UUID.randomUUID().toString()

        _uiState.update {
            it.copy(
                draftEffectiveDate = savedDate,
                draftWelcomeCredits = savedWelcome,
                draftPenaltyTier1 = savedT1,
                draftPenaltyTier2 = savedT2,
                draftPenaltyTier3 = savedT3,
                draftPenaltyTier4 = savedT4,
                draftPushFee = savedPush,
                draftSlotFee2 = savedS2,
                draftSlotFee3 = savedS3,
                draftSlotFee4Plus = savedS4,
                draftSlotRenewalDays = savedDays,
                draftReportProvisional = savedProv,
                draftReportReviewTesterBonus = savedRTester,
                draftReportReviewOwnerBonus = savedROwner,
                draftAppUpdateRewardPerClaim = savedUpdReward,
                draftAppUpdateMaxClaims = savedUpdMax,
                draftOperatorNote = savedNote,
                draftMutationRequestId = savedReqId,
            )
        }
    }

    fun selectTab(tab: BusinessPolicyAdminTab) {
        _uiState.update { it.copy(activeTab = tab, error = null, successMessage = null) }
        if (tab == BusinessPolicyAdminTab.HISTORY && _uiState.value.historyItems.isEmpty()) {
            loadHistory(forceRefresh = true)
        }
    }

    fun loadConfig(keepDraft: Boolean = true) {
        reconcileActiveUser()
        val reqUid = authRepository.currentUserId() ?: return
        val reqGen = SessionCacheManager.currentGeneration()
        val seq = configSeq.incrementAndGet()

        viewModelScope.launch {
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != reqUid || preGen != reqGen) {
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = repository.getBusinessPolicyConfig(futureLimit = 20)

                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != reqUid || postGen != reqGen || seq != configSeq.get()) {
                    return@launch
                }

                _uiState.update { state ->
                    val active = config.activePolicy
                    val shouldPopulate = !keepDraft && active != null
                    state.copy(
                        isLoading = false,
                        config = config,
                        activePolicy = active,
                        futurePolicies = config.futurePolicies,
                        futureHasMore = config.futureHasMore,
                        futureNextCursor = config.futureNextCursor,
                        currentRevision = config.revision,
                        todayDate = config.today,
                        draftWelcomeCredits = if (shouldPopulate) active.welcomeCredits.toString() else state.draftWelcomeCredits,
                        draftPenaltyTier1 = if (shouldPopulate) active.dailyPenaltyTiers.tier1Pct.toString() else state.draftPenaltyTier1,
                        draftPenaltyTier2 = if (shouldPopulate) active.dailyPenaltyTiers.tier2Pct.toString() else state.draftPenaltyTier2,
                        draftPenaltyTier3 = if (shouldPopulate) active.dailyPenaltyTiers.tier3Pct.toString() else state.draftPenaltyTier3,
                        draftPenaltyTier4 = if (shouldPopulate) active.dailyPenaltyTiers.tier4Pct.toString() else state.draftPenaltyTier4,
                        draftPushFee = if (shouldPopulate) active.pushFee.toString() else state.draftPushFee,
                        draftSlotFee2 = if (shouldPopulate) active.slotFees.slot2.toString() else state.draftSlotFee2,
                        draftSlotFee3 = if (shouldPopulate) active.slotFees.slot3.toString() else state.draftSlotFee3,
                        draftSlotFee4Plus = if (shouldPopulate) active.slotFees.slot4Plus.toString() else state.draftSlotFee4Plus,
                        draftSlotRenewalDays = if (shouldPopulate) active.slotFees.renewalDays.toString() else state.draftSlotRenewalDays,
                        draftReportProvisional = if (shouldPopulate) active.reportRewards.provisionalReward.toString() else state.draftReportProvisional,
                        draftReportReviewTesterBonus = if (shouldPopulate) active.reportRewards.reviewTesterBonus.toString() else state.draftReportReviewTesterBonus,
                        draftReportReviewOwnerBonus = if (shouldPopulate) active.reportRewards.reviewOwnerBonus.toString() else state.draftReportReviewOwnerBonus,
                        draftAppUpdateRewardPerClaim = if (shouldPopulate) active.appUpdateRewards.rewardPerClaim.toString() else state.draftAppUpdateRewardPerClaim,
                        draftAppUpdateMaxClaims = if (shouldPopulate) active.appUpdateRewards.maxClaims.toString() else state.draftAppUpdateMaxClaims,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != reqUid || errGen != reqGen || seq != configSeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyBusinessPolicyError(e, localizer),
                    )
                }
            }
        }
    }

    fun loadMoreFuturePolicies() {
        val s = _uiState.value
        if (s.isFutureLoadingMore || !s.futureHasMore || s.futureNextCursor == null) return
        val cursor = s.futureNextCursor
        reconcileActiveUser()
        val reqUid = authRepository.currentUserId() ?: return
        val reqGen = SessionCacheManager.currentGeneration()
        val seq = futureSeq.incrementAndGet()

        viewModelScope.launch {
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != reqUid || preGen != reqGen) {
                return@launch
            }

            _uiState.update { it.copy(isFutureLoadingMore = true) }
            try {
                val envelope = repository.listBusinessPolicies(limit = 20, cursorDate = cursor)

                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != reqUid || postGen != reqGen || seq != futureSeq.get()) {
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        isFutureLoadingMore = false,
                        futurePolicies = state.futurePolicies + envelope.futurePolicies,
                        futureHasMore = envelope.hasMore,
                        futureNextCursor = envelope.nextCursor,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != reqUid || errGen != reqGen || seq != futureSeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isFutureLoadingMore = false,
                        error = friendlyBusinessPolicyError(e, localizer),
                    )
                }
            }
        }
    }

    private fun checkAndRotateDraftRequestId() {
        val currentHash = computeCurrentDraftPayloadHash()
        if (lastSchedulePayloadHash != null && currentHash != lastSchedulePayloadHash) {
            val newId = UUID.randomUUID().toString()
            savePrimitive("draft_req_id", newId)
            _uiState.update { it.copy(draftMutationRequestId = newId) }
        }
    }

    private fun computePayloadHash(
        date: String, welcome: String, t1: String, t2: String, t3: String, t4: String,
        push: String, s2: String, s3: String, s4: String, days: String, prov: String,
        rTester: String, rOwner: String, updReward: String, updMax: String, note: String
    ): String {
        return "$date:$welcome:$t1:$t2:$t3:$t4:$push:$s2:$s3:$s4:$days:$prov:$rTester:$rOwner:$updReward:$updMax:$note"
    }

    private fun computeCurrentDraftPayloadHash(): String {
        val s = _uiState.value
        return computePayloadHash(
            s.draftEffectiveDate, s.draftWelcomeCredits, s.draftPenaltyTier1, s.draftPenaltyTier2,
            s.draftPenaltyTier3, s.draftPenaltyTier4, s.draftPushFee, s.draftSlotFee2, s.draftSlotFee3,
            s.draftSlotFee4Plus, s.draftSlotRenewalDays, s.draftReportProvisional, s.draftReportReviewTesterBonus,
            s.draftReportReviewOwnerBonus, s.draftAppUpdateRewardPerClaim, s.draftAppUpdateMaxClaims, s.draftOperatorNote
        )
    }

    fun updateDraftEffectiveDate(date: String) {
        val v = date.trim()
        savePrimitive("draft_date", v)
        _uiState.update { it.copy(draftEffectiveDate = v, error = null) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftWelcomeCredits(v: String) {
        val filtered = v.filter(Char::isDigit).take(6)
        savePrimitive("draft_welcome", filtered)
        _uiState.update { it.copy(draftWelcomeCredits = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftPenaltyTier1(v: String) {
        val filtered = v.filter(Char::isDigit).take(3)
        savePrimitive("draft_t1", filtered)
        _uiState.update { it.copy(draftPenaltyTier1 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftPenaltyTier2(v: String) {
        val filtered = v.filter(Char::isDigit).take(3)
        savePrimitive("draft_t2", filtered)
        _uiState.update { it.copy(draftPenaltyTier2 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftPenaltyTier3(v: String) {
        val filtered = v.filter(Char::isDigit).take(3)
        savePrimitive("draft_t3", filtered)
        _uiState.update { it.copy(draftPenaltyTier3 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftPenaltyTier4(v: String) {
        val filtered = v.filter(Char::isDigit).take(3)
        savePrimitive("draft_t4", filtered)
        _uiState.update { it.copy(draftPenaltyTier4 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftPushFee(v: String) {
        val filtered = v.filter(Char::isDigit).take(6)
        savePrimitive("draft_push", filtered)
        _uiState.update { it.copy(draftPushFee = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftSlotFee2(v: String) {
        val filtered = v.filter(Char::isDigit).take(6)
        savePrimitive("draft_s2", filtered)
        _uiState.update { it.copy(draftSlotFee2 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftSlotFee3(v: String) {
        val filtered = v.filter(Char::isDigit).take(6)
        savePrimitive("draft_s3", filtered)
        _uiState.update { it.copy(draftSlotFee3 = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftSlotFee4Plus(v: String) {
        val filtered = v.filter(Char::isDigit).take(6)
        savePrimitive("draft_s4", filtered)
        _uiState.update { it.copy(draftSlotFee4Plus = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftSlotRenewalDays(v: String) {
        val filtered = v.filter(Char::isDigit).take(4)
        savePrimitive("draft_days", filtered)
        _uiState.update { it.copy(draftSlotRenewalDays = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftReportProvisional(v: String) {
        val filtered = v.filter(Char::isDigit).take(5)
        savePrimitive("draft_prov", filtered)
        _uiState.update { it.copy(draftReportProvisional = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftReportReviewTesterBonus(v: String) {
        val filtered = v.filter(Char::isDigit).take(5)
        savePrimitive("draft_r_tester", filtered)
        _uiState.update { it.copy(draftReportReviewTesterBonus = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftReportReviewOwnerBonus(v: String) {
        val filtered = v.filter(Char::isDigit).take(5)
        savePrimitive("draft_r_owner", filtered)
        _uiState.update { it.copy(draftReportReviewOwnerBonus = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftAppUpdateRewardPerClaim(v: String) {
        val filtered = v.filter(Char::isDigit).take(5)
        savePrimitive("draft_upd_reward", filtered)
        _uiState.update { it.copy(draftAppUpdateRewardPerClaim = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftAppUpdateMaxClaims(v: String) {
        val filtered = v.filter(Char::isDigit).take(5)
        savePrimitive("draft_upd_max", filtered)
        _uiState.update { it.copy(draftAppUpdateMaxClaims = filtered) }
        checkAndRotateDraftRequestId()
    }

    fun updateDraftOperatorNote(v: String) {
        val trimmed = v.take(1000)
        savePrimitive("draft_note", trimmed)
        _uiState.update { it.copy(draftOperatorNote = trimmed, error = null) }
        checkAndRotateDraftRequestId()
    }

    fun populateDraftFromPolicy(policy: BusinessPolicy, targetDate: String? = null) {
        val newReqId = UUID.randomUUID().toString()
        val target = targetDate ?: _uiState.value.draftEffectiveDate

        savePrimitive("draft_date", target)
        savePrimitive("draft_welcome", policy.welcomeCredits.toString())
        savePrimitive("draft_t1", policy.dailyPenaltyTiers.tier1Pct.toString())
        savePrimitive("draft_t2", policy.dailyPenaltyTiers.tier2Pct.toString())
        savePrimitive("draft_t3", policy.dailyPenaltyTiers.tier3Pct.toString())
        savePrimitive("draft_t4", policy.dailyPenaltyTiers.tier4Pct.toString())
        savePrimitive("draft_push", policy.pushFee.toString())
        savePrimitive("draft_s2", policy.slotFees.slot2.toString())
        savePrimitive("draft_s3", policy.slotFees.slot3.toString())
        savePrimitive("draft_s4", policy.slotFees.slot4Plus.toString())
        savePrimitive("draft_days", policy.slotFees.renewalDays.toString())
        savePrimitive("draft_prov", policy.reportRewards.provisionalReward.toString())
        savePrimitive("draft_r_tester", policy.reportRewards.reviewTesterBonus.toString())
        savePrimitive("draft_r_owner", policy.reportRewards.reviewOwnerBonus.toString())
        savePrimitive("draft_upd_reward", policy.appUpdateRewards.rewardPerClaim.toString())
        savePrimitive("draft_upd_max", policy.appUpdateRewards.maxClaims.toString())
        savePrimitive("draft_note", policy.operatorNote)
        savePrimitive("draft_req_id", newReqId)

        _uiState.update {
            it.copy(
                activeTab = BusinessPolicyAdminTab.EDITOR,
                draftEffectiveDate = target,
                draftWelcomeCredits = policy.welcomeCredits.toString(),
                draftPenaltyTier1 = policy.dailyPenaltyTiers.tier1Pct.toString(),
                draftPenaltyTier2 = policy.dailyPenaltyTiers.tier2Pct.toString(),
                draftPenaltyTier3 = policy.dailyPenaltyTiers.tier3Pct.toString(),
                draftPenaltyTier4 = policy.dailyPenaltyTiers.tier4Pct.toString(),
                draftPushFee = policy.pushFee.toString(),
                draftSlotFee2 = policy.slotFees.slot2.toString(),
                draftSlotFee3 = policy.slotFees.slot3.toString(),
                draftSlotFee4Plus = policy.slotFees.slot4Plus.toString(),
                draftSlotRenewalDays = policy.slotFees.renewalDays.toString(),
                draftReportProvisional = policy.reportRewards.provisionalReward.toString(),
                draftReportReviewTesterBonus = policy.reportRewards.reviewTesterBonus.toString(),
                draftReportReviewOwnerBonus = policy.reportRewards.reviewOwnerBonus.toString(),
                draftAppUpdateRewardPerClaim = policy.appUpdateRewards.rewardPerClaim.toString(),
                draftAppUpdateMaxClaims = policy.appUpdateRewards.maxClaims.toString(),
                draftOperatorNote = policy.operatorNote,
                draftMutationRequestId = newReqId,
                error = null,
                successMessage = null,
            )
        }
        lastSchedulePayloadHash = null
    }

    fun schedulePolicy() {
        reconcileActiveUser()
        val confirmedUid = authRepository.currentUserId()
        val confirmedGen = SessionCacheManager.currentGeneration()
        if (confirmedUid == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_notif_error_not_authenticated)) }
            return
        }

        // Synchronous action gate before coroutine launch
        if (!isActionInProgress.compareAndSet(false, true)) {
            return
        }

        val s = _uiState.value
        val pending = getPendingSchedule(confirmedUid)
        val paramsToDispatch: AdminScheduleBusinessPolicyParams
        val submittedPayloadHash: String

        if (pending != null) {
            // Unresolved confirmed intent must be retried with exact original snapshot
            paramsToDispatch = pending
            submittedPayloadHash = computePayloadHash(
                pending.effectiveFrom, pending.welcomeCredits.toString(), pending.penaltyTier1Pct.toString(),
                pending.penaltyTier2Pct.toString(), pending.penaltyTier3Pct.toString(), pending.penaltyTier4Pct.toString(),
                pending.pushFee.toString(), pending.slotFee2.toString(), pending.slotFee3.toString(),
                pending.slotFee4Plus.toString(), pending.slotRenewalDays.toString(), pending.reportProvisionalReward.toString(),
                pending.reportReviewTesterBonus.toString(), pending.reportReviewOwnerBonus.toString(),
                pending.appUpdateRewardPerClaim.toString(), pending.appUpdateMaxClaims.toString(), pending.operatorNote
            )
        } else {
            val effectiveDate = s.draftEffectiveDate.trim()
            val note = s.draftOperatorNote.trim()
            val today = s.todayDate

            val effectiveLocalDate = try {
                java.time.LocalDate.parse(effectiveDate)
            } catch (_: Exception) {
                isActionInProgress.set(false)
                _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_date_required)) }
                return
            }

            val todayLocalDate = try {
                if (today.isNotBlank()) java.time.LocalDate.parse(today) else com.ntt.crosstest.util.VnTime.today()
            } catch (_: Exception) {
                com.ntt.crosstest.util.VnTime.today()
            }

            if (!effectiveLocalDate.isAfter(todayLocalDate)) {
                isActionInProgress.set(false)
                _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_date_required)) }
                return
            }

            if (note.isBlank() || note.length > 1000) {
                isActionInProgress.set(false)
                _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_note_required)) }
                return
            }

            val welcome = s.draftWelcomeCredits.toIntOrNull() ?: -1
            val t1 = s.draftPenaltyTier1.toIntOrNull() ?: -1
            val t2 = s.draftPenaltyTier2.toIntOrNull() ?: -1
            val t3 = s.draftPenaltyTier3.toIntOrNull() ?: -1
            val t4 = s.draftPenaltyTier4.toIntOrNull() ?: -1
            val push = s.draftPushFee.toIntOrNull() ?: -1
            val s2 = s.draftSlotFee2.toIntOrNull() ?: -1
            val s3 = s.draftSlotFee3.toIntOrNull() ?: -1
            val s4 = s.draftSlotFee4Plus.toIntOrNull() ?: -1
            val days = s.draftSlotRenewalDays.toIntOrNull() ?: -1
            val prov = s.draftReportProvisional.toIntOrNull() ?: -1
            val rTester = s.draftReportReviewTesterBonus.toIntOrNull() ?: -1
            val rOwner = s.draftReportReviewOwnerBonus.toIntOrNull() ?: -1
            val updReward = s.draftAppUpdateRewardPerClaim.toIntOrNull() ?: -1
            val updMax = s.draftAppUpdateMaxClaims.toIntOrNull() ?: -1

            val isValid = welcome in 0..10000 &&
                    t1 in 0..100 && t2 in 0..100 && t3 in 0..100 && t4 in 0..100 &&
                    t1 <= t2 && t2 <= t3 && t3 <= t4 &&
                    push in 1..10000 &&
                    s2 in 1..10000 && s3 in 1..10000 && s4 in 1..10000 &&
                    days in 1..365 &&
                    prov in 0..1000 && rTester in 0..1000 && rOwner in 0..1000 &&
                    updReward in 1..1000 && updMax in 1..1000

            if (!isValid) {
                isActionInProgress.set(false)
                _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_validation_error)) }
                return
            }

            submittedPayloadHash = computeCurrentDraftPayloadHash()
            val requestId = s.draftMutationRequestId.ifBlank { UUID.randomUUID().toString() }
            lastSchedulePayloadHash = submittedPayloadHash

            paramsToDispatch = AdminScheduleBusinessPolicyParams(
                effectiveFrom = effectiveDate,
                operatorNote = note,
                expectedRevision = s.currentRevision,
                welcomeCredits = welcome,
                penaltyTier1Pct = t1,
                penaltyTier2Pct = t2,
                penaltyTier3Pct = t3,
                penaltyTier4Pct = t4,
                pushFee = push,
                slotFee2 = s2,
                slotFee3 = s3,
                slotFee4Plus = s4,
                slotRenewalDays = days,
                reportProvisionalReward = prov,
                reportReviewTesterBonus = rTester,
                reportReviewOwnerBonus = rOwner,
                appUpdateRewardPerClaim = updReward,
                appUpdateMaxClaims = updMax,
                requestId = requestId,
            )
        }

        // Save durable confirmed intent BEFORE dispatch
        savePendingSchedule(confirmedUid, paramsToDispatch)
        _uiState.update { it.copy(pendingScheduleIntent = paramsToDispatch) }

        val seq = mutationSeq.incrementAndGet()

        viewModelScope.launch {
            // Immediate check BEFORE setting state or doing anything
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != confirmedUid || preGen != confirmedGen) {
                if (preUid != activeUserId || preGen != activeGeneration) {
                    reconcileActiveUser()
                }
                return@launch
            }

            _uiState.update { it.copy(isActionInProgress = true, error = null) }
            try {
                val newConfig = repository.scheduleBusinessPolicy(paramsToDispatch)

                // Immediate check AFTER RPC
                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != confirmedUid || postGen != confirmedGen) {
                    if (postUid != activeUserId || postGen != activeGeneration) {
                        reconcileActiveUser()
                    }
                    return@launch
                }

                // Intent successfully executed: clear pending intent
                clearPendingSchedule(confirmedUid)
                lastSchedulePayloadHash = null

                val freshReqId = UUID.randomUUID().toString()
                savePrimitive("draft_req_id", freshReqId)

                val currentPayloadHash = computeCurrentDraftPayloadHash()
                val draftWasEditedWhileInFlight = currentPayloadHash != submittedPayloadHash

                if (!draftWasEditedWhileInFlight) {
                    savePrimitive("draft_date", null)
                    savePrimitive("draft_note", null)
                }

                _uiState.update { state ->
                    val userKeptTyping = computeCurrentDraftPayloadHash() != submittedPayloadHash
                    state.copy(
                        config = newConfig,
                        activePolicy = newConfig.activePolicy,
                        futurePolicies = newConfig.futurePolicies,
                        futureHasMore = newConfig.futureHasMore,
                        futureNextCursor = newConfig.futureNextCursor,
                        currentRevision = newConfig.revision,
                        activeTab = BusinessPolicyAdminTab.SCHEDULED,
                        draftMutationRequestId = freshReqId,
                        draftEffectiveDate = if (userKeptTyping) state.draftEffectiveDate else "",
                        draftOperatorNote = if (userKeptTyping) state.draftOperatorNote else "",
                        pendingScheduleIntent = null,
                        successMessage = localizer.get(R.string.admin_policy_schedule_success, paramsToDispatch.effectiveFrom),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                // Immediate check AFTER error
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != confirmedUid || errGen != confirmedGen) {
                    if (errUid != activeUserId || errGen != activeGeneration) {
                        reconcileActiveUser()
                    }
                    return@launch
                }

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true
                if (isConflict) {
                    // Definitive CAS conflict: not committed. Clear pending schedule so user can re-submit with fresh revision.
                    clearPendingSchedule(confirmedUid)
                    try {
                        val freshConfig = repository.getBusinessPolicyConfig(futureLimit = 20)
                        if (seq != mutationSeq.get() || errUid != authRepository.currentUserId() || errGen != SessionCacheManager.currentGeneration()) {
                            return@launch
                        }
                        _uiState.update {
                            it.copy(
                                config = freshConfig,
                                activePolicy = freshConfig.activePolicy,
                                futurePolicies = freshConfig.futurePolicies,
                                futureHasMore = freshConfig.futureHasMore,
                                futureNextCursor = freshConfig.futureNextCursor,
                                currentRevision = freshConfig.revision,
                                pendingScheduleIntent = null,
                                error = localizer.get(R.string.admin_policy_conflict_reload),
                            )
                        }
                    } catch (reloadErr: Exception) {
                        if (reloadErr is CancellationException) throw reloadErr
                        _uiState.update {
                            it.copy(
                                pendingScheduleIntent = null,
                                error = friendlyBusinessPolicyError(e, localizer),
                            )
                        }
                    }
                } else if (isDefinitiveError(e)) {
                    // Definitive rejection: clear pending intent
                    clearPendingSchedule(confirmedUid)
                    _uiState.update {
                        it.copy(
                            pendingScheduleIntent = null,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    // Ambiguous network failure: retain pendingScheduleIntent intact for retry
                    _uiState.update {
                        it.copy(
                            pendingScheduleIntent = paramsToDispatch,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                }
            } finally {
                val finUid = authRepository.currentUserId()
                val finGen = SessionCacheManager.currentGeneration()
                if (finUid == confirmedUid && finGen == confirmedGen) {
                    isActionInProgress.set(false)
                    _uiState.update { it.copy(isActionInProgress = false) }
                }
            }
        }
    }

    fun openCancelDialog(policy: BusinessPolicy) {
        reconcileActiveUser()
        val date = policy.effectiveFrom ?: return
        val expectedRev = _uiState.value.currentRevision
        val confirmedUid = activeUserId

        // Check if there is an unresolved pending cancellation for this policy date
        val pending = getPendingCancellation(confirmedUid)
        if (pending != null && pending.effectiveFrom != date) {
            _uiState.update {
                it.copy(
                    cancelDialogTarget = null,
                    error = localizer.get(R.string.admin_policy_pending_cancel_banner, pending.effectiveFrom),
                )
            }
            return
        }
        if (pending != null && pending.effectiveFrom == date) {
            _uiState.update {
                it.copy(
                    cancelDialogTarget = policy,
                    cancelReason = pending.operatorNote,
                    cancelRequestId = pending.requestId,
                    cancelExpectedRevision = pending.expectedRevision,
                    pendingCancellationIntent = pending,
                    error = null,
                )
            }
            return
        }

        val savedReqId = getPrimitive("cancel_req_id")
        val savedDate = getPrimitive("cancel_date")
        val savedRev = getPrimitive("cancel_rev")

        val reqId = if (savedDate == date && savedRev == expectedRev && savedReqId != null) {
            savedReqId
        } else {
            val newId = UUID.randomUUID().toString()
            savePrimitive("cancel_req_id", newId)
            savePrimitive("cancel_date", date)
            savePrimitive("cancel_rev", expectedRev)
            newId
        }

        _uiState.update {
            it.copy(
                cancelDialogTarget = policy,
                cancelReason = getPrimitive("cancel_reason") ?: "",
                cancelRequestId = reqId,
                cancelExpectedRevision = expectedRev,
                error = null,
            )
        }
    }

    fun dismissCancelDialog() {
        val confirmedUid = activeUserId
        val pending = getPendingCancellation(confirmedUid)
        if (pending == null) {
            savePrimitive("cancel_req_id", null)
            savePrimitive("cancel_date", null)
            savePrimitive("cancel_rev", null)
            savePrimitive("cancel_reason", null)
        }
        _uiState.update {
            it.copy(
                cancelDialogTarget = null,
                cancelReason = "",
                cancelRequestId = null,
                cancelExpectedRevision = null,
            )
        }
    }

    fun updateCancelReason(reason: String) {
        val confirmedUid = activeUserId
        val pending = getPendingCancellation(confirmedUid)
        if (pending != null) {
            // Unresolved confirmed intent reason is immutable until reconciled
            return
        }
        val trimmed = reason.take(1000)
        savePrimitive("cancel_reason", trimmed)
        _uiState.update { it.copy(cancelReason = trimmed) }
    }

    fun confirmCancelPolicy() {
        reconcileActiveUser()
        val confirmedUid = authRepository.currentUserId()
        val confirmedGen = SessionCacheManager.currentGeneration()
        if (confirmedUid == null) return

        // Synchronous action gate before launch
        if (!isActionInProgress.compareAndSet(false, true)) {
            return
        }

        val target = _uiState.value.cancelDialogTarget
        val pending = getPendingCancellation(confirmedUid)

        // A new target cannot replace an unresolved confirmed operation.
        if (pending != null && target != null && target.effectiveFrom != pending.effectiveFrom) {
            isActionInProgress.set(false)
            _uiState.update {
                it.copy(
                    cancelDialogTarget = null,
                    error = localizer.get(R.string.admin_policy_pending_cancel_banner, pending.effectiveFrom),
                )
            }
            return
        }
        val activePending = pending

        val effectiveFrom = target?.effectiveFrom ?: activePending?.effectiveFrom
        if (effectiveFrom == null) {
            isActionInProgress.set(false)
            return
        }

        val reason = (activePending?.operatorNote ?: _uiState.value.cancelReason).trim()
        if (reason.isBlank()) {
            isActionInProgress.set(false)
            _uiState.update { it.copy(error = localizer.get(R.string.admin_policy_note_required)) }
            return
        }

        val expectedRev = activePending?.expectedRevision ?: _uiState.value.cancelExpectedRevision ?: _uiState.value.currentRevision
        val reqId = activePending?.requestId ?: _uiState.value.cancelRequestId ?: UUID.randomUUID().toString()

        val paramsToDispatch = AdminCancelBusinessPolicyParams(
            effectiveFrom = effectiveFrom,
            operatorNote = reason,
            expectedRevision = expectedRev,
            requestId = reqId,
        )

        // Save durable confirmed intent BEFORE dispatch
        savePendingCancellation(confirmedUid, paramsToDispatch)
        _uiState.update { it.copy(pendingCancellationIntent = paramsToDispatch) }

        val seq = mutationSeq.incrementAndGet()

        viewModelScope.launch {
            // Immediate check BEFORE setting state or doing anything
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != confirmedUid || preGen != confirmedGen) {
                if (preUid != activeUserId || preGen != activeGeneration) {
                    reconcileActiveUser()
                }
                return@launch
            }

            _uiState.update { it.copy(isActionInProgress = true, error = null) }
            try {
                val newConfig = repository.cancelBusinessPolicy(
                    effectiveFrom = paramsToDispatch.effectiveFrom,
                    operatorNote = paramsToDispatch.operatorNote,
                    expectedRevision = paramsToDispatch.expectedRevision,
                    requestId = paramsToDispatch.requestId,
                )

                // Immediate check AFTER RPC
                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != confirmedUid || postGen != confirmedGen) {
                    if (postUid != activeUserId || postGen != activeGeneration) {
                        reconcileActiveUser()
                    }
                    return@launch
                }

                // Successfully executed cancellation
                clearPendingCancellation(confirmedUid)

                _uiState.update {
                    it.copy(
                        cancelDialogTarget = null,
                        cancelReason = "",
                        cancelRequestId = null,
                        cancelExpectedRevision = null,
                        pendingCancellationIntent = null,
                        config = newConfig,
                        activePolicy = newConfig.activePolicy,
                        futurePolicies = newConfig.futurePolicies,
                        futureHasMore = newConfig.futureHasMore,
                        futureNextCursor = newConfig.futureNextCursor,
                        currentRevision = newConfig.revision,
                        successMessage = localizer.get(R.string.admin_policy_cancel_success),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                // Immediate check AFTER error
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != confirmedUid || errGen != confirmedGen) {
                    if (errUid != activeUserId || errGen != activeGeneration) {
                        reconcileActiveUser()
                    }
                    return@launch
                }

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true
                if (isConflict) {
                    try {
                        val freshConfig = repository.getBusinessPolicyConfig(futureLimit = 20)
                        if (seq != mutationSeq.get() || errUid != authRepository.currentUserId() || errGen != SessionCacheManager.currentGeneration()) {
                            return@launch
                        }

                        val updatedPending = paramsToDispatch.copy(expectedRevision = freshConfig.revision)
                        savePendingCancellation(confirmedUid, updatedPending)

                        val matchingTarget = freshConfig.futurePolicies.find { it.effectiveFrom == paramsToDispatch.effectiveFrom }
                        _uiState.update {
                            it.copy(
                                config = freshConfig,
                                currentRevision = freshConfig.revision,
                                futurePolicies = freshConfig.futurePolicies,
                                futureHasMore = freshConfig.futureHasMore,
                                futureNextCursor = freshConfig.futureNextCursor,
                                cancelDialogTarget = matchingTarget ?: it.cancelDialogTarget ?: BusinessPolicy(effectiveFrom = paramsToDispatch.effectiveFrom),
                                cancelReason = paramsToDispatch.operatorNote,
                                cancelRequestId = paramsToDispatch.requestId,
                                cancelExpectedRevision = freshConfig.revision,
                                pendingCancellationIntent = updatedPending,
                                error = localizer.get(R.string.admin_policy_conflict_reload),
                            )
                        }
                    } catch (reloadErr: Exception) {
                        if (reloadErr is CancellationException) throw reloadErr
                        _uiState.update {
                            it.copy(error = friendlyBusinessPolicyError(e, localizer))
                        }
                    }
                } else if (isDefinitiveError(e)) {
                    clearPendingCancellation(confirmedUid)
                    _uiState.update {
                        it.copy(
                            cancelDialogTarget = null,
                            cancelReason = "",
                            cancelRequestId = null,
                            cancelExpectedRevision = null,
                            pendingCancellationIntent = null,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    // Ambiguous network failure: retain original snapshot for explicit retry
                    _uiState.update {
                        it.copy(
                            pendingCancellationIntent = paramsToDispatch,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                }
            } finally {
                val finUid = authRepository.currentUserId()
                val finGen = SessionCacheManager.currentGeneration()
                if (finUid == confirmedUid && finGen == confirmedGen) {
                    isActionInProgress.set(false)
                    _uiState.update { it.copy(isActionInProgress = false) }
                }
            }
        }
    }

    fun loadHistory(forceRefresh: Boolean = false) {
        reconcileActiveUser()
        val reqUid = authRepository.currentUserId() ?: return
        val reqGen = SessionCacheManager.currentGeneration()
        val seq = historySeq.incrementAndGet()

        viewModelScope.launch {
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != reqUid || preGen != reqGen) {
                return@launch
            }

            _uiState.update { it.copy(isHistoryLoading = true, error = null) }
            try {
                val envelope = repository.getBusinessPolicyHistory(limit = 20, cursorId = null)

                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != reqUid || postGen != reqGen || seq != historySeq.get()) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isHistoryLoading = false,
                        historyItems = envelope.items,
                        historyHasMore = envelope.hasMore,
                        historyNextCursor = envelope.nextCursor,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != reqUid || errGen != reqGen || seq != historySeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHistoryLoading = false,
                        error = friendlyBusinessPolicyError(e, localizer),
                    )
                }
            }
        }
    }

    fun loadMoreHistory() {
        val s = _uiState.value
        if (s.isHistoryLoadingMore || !s.historyHasMore || s.historyNextCursor == null) return
        val cursor = s.historyNextCursor
        reconcileActiveUser()
        val reqUid = authRepository.currentUserId() ?: return
        val reqGen = SessionCacheManager.currentGeneration()
        val seq = historySeq.incrementAndGet()

        viewModelScope.launch {
            val preUid = authRepository.currentUserId()
            val preGen = SessionCacheManager.currentGeneration()
            if (preUid != reqUid || preGen != reqGen) {
                return@launch
            }

            _uiState.update { it.copy(isHistoryLoadingMore = true) }
            try {
                val envelope = repository.getBusinessPolicyHistory(limit = 20, cursorId = cursor)

                val postUid = authRepository.currentUserId()
                val postGen = SessionCacheManager.currentGeneration()
                if (postUid != reqUid || postGen != reqGen || seq != historySeq.get()) {
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        isHistoryLoadingMore = false,
                        historyItems = state.historyItems + envelope.items,
                        historyHasMore = envelope.hasMore,
                        historyNextCursor = envelope.nextCursor,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errUid = authRepository.currentUserId()
                val errGen = SessionCacheManager.currentGeneration()
                if (errUid != reqUid || errGen != reqGen || seq != historySeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHistoryLoadingMore = false,
                        error = friendlyBusinessPolicyError(e, localizer),
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
