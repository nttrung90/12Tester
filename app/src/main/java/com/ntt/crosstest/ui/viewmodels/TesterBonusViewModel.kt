package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CampaignTesterBonusStore
import com.ntt.crosstest.data.CreateTesterBonusResult
import com.ntt.crosstest.data.GetTesterBonusResult
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.TesterBonusIntent
import com.ntt.crosstest.data.TesterBonusStoreCorruptedStateException
import com.ntt.crosstest.data.TesterBonusStoreState
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TesterBonusUiState(
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val isReconciling: Boolean = false,
    val isNotSupported: Boolean = false,
    val isCampaignNotFound: Boolean = false,
    val campaign: Campaign? = null,
    val currentBalance: Int = 0,
    val currentUserId: String? = null,
    val sessionGeneration: Long = SessionCacheManager.currentGeneration(),

    // Input fields for creation
    val rewardCreditsInput: String = "",
    val maxRecipientsInput: String = "",
    val inputValidationError: String? = null,

    // Live budget calculation (checked bounds)
    val calculatedBudget: Long = 0L,
    val expectedBalance: Long = 0L,
    val isFormValid: Boolean = false,

    // Mode: VIEW_ROUND (unsettled) vs CREATE_FORM vs HISTORICAL_ROUND + CREATE_FORM
    val existingRound: GetTesterBonusResult? = null,
    val hasActiveOrClosingRound: Boolean = false,
    val hasTerminalRound: Boolean = false,

    // Intent persistence & ambiguous resolution
    val pendingIntent: TesterBonusIntent? = null,
    val hasUnresolvedIntent: Boolean = false,
    val isStoreCorrupted: Boolean = false,

    // Outcomes
    val message: String? = null,
    val error: String? = null,
    val isSuccess: Boolean = false,
    val confirmedResult: CreateTesterBonusResult? = null,
)

class TesterBonusViewModel(
    val campaignId: String,
    private val repository: CampaignRepository,
    private val localizer: Localizer,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TesterBonusUiState())
    val uiState: StateFlow<TesterBonusUiState> = _uiState.asStateFlow()

    private var currentOperationId: Long = 0L

    private fun effectiveUserId(): String? {
        if (repository.sessionStatus.value is SessionStatus.NotAuthenticated) {
            return null
        }
        return repository.currentUserId()
    }

    init {
        loadInitialData()
    }

    fun loadInitialData() {
        val opId = ++currentOperationId
        val uid = effectiveUserId()
        val gen = SessionCacheManager.currentGeneration()
        _uiState.update {
            it.copy(
                isLoading = true,
                currentUserId = uid,
                sessionGeneration = gen,
                error = null,
                isNotSupported = false,
                isCampaignNotFound = false,
            )
        }

        viewModelScope.launch {
            try {
                // 1. Phục hồi và đối soát intent lưu trữ TRƯỚC VÀ ĐỘC LẬP VỚI getCampaign
                // (Ngay cả khi chiến dịch đã bị xóa trên server, giao dịch tài chính trước đó vẫn cần đối soát)
                var pending: TesterBonusIntent? = null
                var corrupted = false
                if (uid != null) {
                    when (val storeState = CampaignTesterBonusStore.peekState(context, uid, campaignId)) {
                        is TesterBonusStoreState.Valid -> pending = storeState.intent
                        is TesterBonusStoreState.Corrupted -> corrupted = true
                        is TesterBonusStoreState.None -> Unit
                    }
                }

                if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) return@launch

                _uiState.update { state ->
                    state.copy(
                        pendingIntent = pending,
                        hasUnresolvedIntent = (pending != null),
                        isStoreCorrupted = corrupted,
                        rewardCreditsInput = pending?.rewardCredits?.toString() ?: state.rewardCreditsInput,
                        maxRecipientsInput = pending?.maxRecipients?.toString() ?: state.maxRecipientsInput,
                    )
                }

                if (corrupted) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = localizer.get(R.string.tester_bonus_err_store_corrupted),
                        )
                    }
                    return@launch
                }

                // Nếu có pending intent, thử đối soát với server bằng cùng client_request_id
                var reconciledHistoricalResult: CreateTesterBonusResult? = null
                if (pending != null && uid != null) {
                    try {
                        val reconcile = repository.reconcileCampaignTesterBonus(
                            campaignId = campaignId,
                            clientRequestId = pending.clientRequestId,
                            expectedRewardCredits = pending.rewardCredits,
                            expectedMaxRecipients = pending.maxRecipients,
                        )
                        if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) return@launch

                        if (reconcile.isValidEvidenceFor(
                            expectedClientRequestId = pending.clientRequestId,
                            expectedCampaignId = campaignId,
                            expectedRewardCredits = pending.rewardCredits,
                            expectedMaxRecipients = pending.maxRecipients,
                        )) {
                            // Xác nhận máy chủ đã ghi nhận thành công từ trước
                            CampaignTesterBonusStore.clearPendingIntentIfMatching(
                                context = context,
                                userId = uid,
                                campaignId = campaignId,
                                expectedClientRequestId = pending.clientRequestId,
                            )
                            pending = null
                            reconciledHistoricalResult = CreateTesterBonusResult(
                                success = true,
                                replayed = true,
                                roundId = reconcile.roundId,
                                clientRequestId = reconcile.clientRequestId,
                                campaignId = reconcile.campaignId,
                                rewardCredits = reconcile.rewardCredits,
                                maxRecipients = reconcile.maxRecipients,
                                paidCount = reconcile.paidCount,
                                remainingSlots = reconcile.remainingSlots,
                                reservedCredits = reconcile.reservedCredits,
                                remainingCredits = reconcile.remainingCredits,
                                spentCredits = reconcile.spentCredits,
                                refundedCredits = reconcile.refundedCredits,
                                ownerBalance = reconcile.ownerBalance,
                                status = reconcile.status ?: "active",
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Lỗi đối soát mạng -> giữ intent cho lần sau
                    }
                }

                // 2. Tải thông tin campaign và profile
                val campaign = try {
                    repository.getCampaign(campaignId, forceRefresh = true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }

                if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) return@launch

                val profile = repository.getMyProfile(forceRefresh = true)
                val balance = profile?.credits ?: 0

                // 3. Tra cứu đợt thưởng của chiến dịch
                var round: GetTesterBonusResult? = null
                var notSupported = false
                var roundError: String? = null
                try {
                    val r = repository.getCampaignTesterBonus(campaignId)
                    if (r.hasError) {
                        roundError = r.error ?: r.code
                    } else {
                        round = r
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val msg = e.message.orEmpty()
                    if (msg.contains("server_update_required", ignoreCase = true) || msg.contains("PGRST202", ignoreCase = true)) {
                        notSupported = true
                    } else {
                        roundError = mapTesterBonusError(e)
                    }
                }

                if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) return@launch

                val isUnsettled = round != null && round.isUnsettled
                val isTerminal = round != null && round.isTerminal

                val initialError = when {
                    notSupported -> localizer.get(R.string.tester_bonus_err_not_supported)
                    roundError != null -> roundError
                    campaign == null && reconciledHistoricalResult == null -> localizer.get(R.string.campaign_not_found)
                    pending != null -> localizer.get(R.string.tester_bonus_unresolved_prompt)
                    else -> null
                }

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        campaign = campaign,
                        isCampaignNotFound = (campaign == null && reconciledHistoricalResult == null),
                        isNotSupported = notSupported,
                        currentBalance = reconciledHistoricalResult?.safeOwnerBalanceInt ?: balance,
                        existingRound = round,
                        hasActiveOrClosingRound = isUnsettled,
                        hasTerminalRound = isTerminal,
                        pendingIntent = pending,
                        hasUnresolvedIntent = (pending != null),
                        isSuccess = (reconciledHistoricalResult != null),
                        confirmedResult = reconciledHistoricalResult,
                        message = if (reconciledHistoricalResult != null) localizer.get(R.string.tester_bonus_reconciled_success) else state.message,
                        error = initialError,
                    )
                }
                recalculateBudget()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isLoading = false, error = mapTesterBonusError(e)) }
                }
            }
        }
    }

    fun onRewardCreditsChanged(input: String) {
        if (_uiState.value.hasUnresolvedIntent || _uiState.value.hasActiveOrClosingRound) return
        _uiState.update { it.copy(rewardCreditsInput = input, error = null) }
        recalculateBudget()
    }

    fun onMaxRecipientsChanged(input: String) {
        if (_uiState.value.hasUnresolvedIntent || _uiState.value.hasActiveOrClosingRound) return
        _uiState.update { it.copy(maxRecipientsInput = input, error = null) }
        recalculateBudget()
    }

    private fun recalculateBudget() {
        val state = _uiState.value
        val rewardRaw = state.rewardCreditsInput.trim()
        val recipientsRaw = state.maxRecipientsInput.trim()
        val wallet = state.currentBalance.toLong()

        if (rewardRaw.isEmpty() || recipientsRaw.isEmpty()) {
            _uiState.update {
                it.copy(
                    calculatedBudget = 0L,
                    expectedBalance = wallet,
                    isFormValid = false,
                    inputValidationError = null,
                )
            }
            return
        }

        // Strict positive integer checks: reject decimals, negatives, non-digit chars
        val rewardLong = rewardRaw.toLongOrNull()
        val recipientsLong = recipientsRaw.toLongOrNull()

        if (rewardLong == null || recipientsLong == null || rewardLong <= 0L || recipientsLong <= 0L ||
            rewardLong > Int.MAX_VALUE.toLong() || recipientsLong > Int.MAX_VALUE.toLong() ||
            !rewardRaw.all { it.isDigit() } || !recipientsRaw.all { it.isDigit() }
        ) {
            _uiState.update {
                it.copy(
                    calculatedBudget = 0L,
                    expectedBalance = wallet,
                    isFormValid = false,
                    inputValidationError = localizer.get(R.string.tester_bonus_err_positive_int),
                )
            }
            return
        }

        // Checked multiplication to prevent arithmetic overflow
        val budgetLong = rewardLong * recipientsLong
        if (budgetLong > Int.MAX_VALUE.toLong() || (rewardLong > 0 && budgetLong / rewardLong != recipientsLong)) {
            _uiState.update {
                it.copy(
                    calculatedBudget = 0L,
                    expectedBalance = wallet,
                    isFormValid = false,
                    inputValidationError = localizer.get(R.string.tester_bonus_err_overflow_simple),
                )
            }
            return
        }

        val expected = wallet - budgetLong
        val isInsufficient = budgetLong > wallet

        val error = if (isInsufficient) {
            localizer.get(R.string.tester_bonus_err_insufficient_credits, budgetLong, wallet)
        } else {
            null
        }

        _uiState.update {
            it.copy(
                calculatedBudget = budgetLong,
                expectedBalance = expected,
                isFormValid = !isInsufficient,
                inputValidationError = error,
            )
        }
    }

    fun submitCreateBonus() {
        val state = _uiState.value
        if (state.isActionLoading || state.isReconciling) return

        val opId = ++currentOperationId
        val uid = effectiveUserId()
        val gen = SessionCacheManager.currentGeneration()
        if (uid == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_login_required)) }
            return
        }

        val campaign = state.campaign
        if (campaign == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.campaign_not_found)) }
            return
        }
        if (campaign.isFinalized) {
            _uiState.update { it.copy(error = localizer.get(R.string.tester_bonus_err_app_finalized)) }
            return
        }
        if (campaign.isPaused) {
            _uiState.update { it.copy(error = localizer.get(R.string.tester_bonus_err_app_paused)) }
            return
        }
        if (!campaign.isOpen) {
            _uiState.update { it.copy(error = localizer.get(R.string.tester_bonus_err_campaign_not_open)) }
            return
        }
        if (state.hasActiveOrClosingRound) {
            _uiState.update { it.copy(error = localizer.get(R.string.tester_bonus_err_active_round_exists)) }
            return
        }

        val reward = state.rewardCreditsInput.trim().toIntOrNull()
        val recipients = state.maxRecipientsInput.trim().toIntOrNull()
        if (reward == null || recipients == null || reward <= 0 || recipients <= 0 || !state.isFormValid) {
            _uiState.update { it.copy(error = state.inputValidationError ?: localizer.get(R.string.tester_bonus_err_positive_int)) }
            return
        }

        // Durable persistence BEFORE network call
        val intent = try {
            CampaignTesterBonusStore.getOrCreatePendingIntent(
                context = context,
                userId = uid,
                campaignId = campaignId,
                rewardCredits = reward,
                maxRecipients = recipients,
            )
        } catch (e: TesterBonusStoreCorruptedStateException) {
            _uiState.update { it.copy(isStoreCorrupted = true, error = localizer.get(R.string.tester_bonus_err_store_corrupted)) }
            return
        } catch (e: Exception) {
            _uiState.update { it.copy(error = localizer.get(R.string.tester_bonus_err_unknown)) }
            return
        }

        _uiState.update {
            it.copy(
                isActionLoading = true,
                pendingIntent = intent,
                hasUnresolvedIntent = true,
                error = null,
                message = null,
            )
        }

        viewModelScope.launch {
            try {
                val result = repository.createCampaignTesterBonus(
                    campaignId = campaignId,
                    rewardCredits = intent.rewardCredits,
                    maxRecipients = intent.maxRecipients,
                    clientRequestId = intent.clientRequestId,
                )

                if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                    return@launch
                }

                val isValidEvidence = result.isValidEvidenceFor(
                    expectedClientRequestId = intent.clientRequestId,
                    expectedCampaignId = campaignId,
                    expectedRewardCredits = intent.rewardCredits,
                    expectedMaxRecipients = intent.maxRecipients,
                )

                if (isValidEvidence) {
                    CampaignTesterBonusStore.clearPendingIntentIfMatching(
                        context = context,
                        userId = uid,
                        campaignId = campaignId,
                        expectedClientRequestId = intent.clientRequestId,
                    )

                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            hasUnresolvedIntent = false,
                            pendingIntent = null,
                            isSuccess = true,
                            confirmedResult = result,
                            currentBalance = result.safeOwnerBalanceInt ?: it.currentBalance,
                            message = localizer.get(R.string.tester_bonus_created_success, result.reservedCredits),
                            hasActiveOrClosingRound = (result.status in listOf("active", "closing")),
                            hasTerminalRound = (result.status in listOf("closed", "exhausted")),
                        )
                    }

                    // Tải lại round mới được tạo (nếu thất bại thì vẫn giữ trạng thái thành công đã xác nhận)
                    try {
                        val updatedRound = repository.getCampaignTesterBonus(campaignId)
                        if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                            _uiState.update { it.copy(
                                existingRound = updatedRound,
                                hasActiveOrClosingRound = updatedRound.isUnsettled,
                                hasTerminalRound = updatedRound.isTerminal,
                            ) }
                        }
                    } catch (_: Exception) {}
                } else {
                    // Phản hồi không đủ bằng chứng hợp lệ -> giữ intent để đối soát
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            hasUnresolvedIntent = true,
                            error = localizer.get(R.string.tester_bonus_unresolved_prompt),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                    handleCreateError(opId, uid, gen, intent, e)
                }
            }
        }
    }

    private suspend fun handleCreateError(
        opId: Long,
        uid: String,
        gen: Long,
        intent: TesterBonusIntent,
        error: Throwable,
    ) {
        val errorMsg = error.message.orEmpty()
        if (errorMsg.contains("server_update_required", ignoreCase = true) || errorMsg.contains("PGRST202", ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    isActionLoading = false,
                    isReconciling = false,
                    hasUnresolvedIntent = true,
                    isNotSupported = true,
                    error = localizer.get(R.string.tester_bonus_err_not_supported),
                )
            }
            return
        }

        val isDefinitive = isDefinitiveServerValidationError(error)
        if (isDefinitive) {
            // Máy chủ từ chối dứt khoát bằng typed Postgres rejection -> rollback hoàn toàn, an toàn giải phóng intent
            CampaignTesterBonusStore.clearPendingIntentIfMatching(
                context = context,
                userId = uid,
                campaignId = campaignId,
                expectedClientRequestId = intent.clientRequestId,
            )
            _uiState.update {
                it.copy(
                    isActionLoading = false,
                    isReconciling = false,
                    hasUnresolvedIntent = false,
                    pendingIntent = null,
                    error = mapTesterBonusError(error),
                )
            }
            return
        }

        // Lỗi mơ hồ (timeout, 5xx, mất mạng, ACCOUNT_LOCKED) -> GIỮ NGUYÊN intent và đối soát
        _uiState.update {
            it.copy(
                isActionLoading = false,
                isReconciling = true,
                error = null,
            )
        }
        reconcilePendingIntent(opId, uid, gen, intent, originalError = error)
    }

    private suspend fun reconcilePendingIntent(
        opId: Long,
        uid: String,
        gen: Long,
        intent: TesterBonusIntent,
        originalError: Throwable? = null,
    ) {
        _uiState.update { it.copy(isReconciling = true) }
        try {
            val reconcileResult = repository.reconcileCampaignTesterBonus(
                campaignId = campaignId,
                clientRequestId = intent.clientRequestId,
                expectedRewardCredits = intent.rewardCredits,
                expectedMaxRecipients = intent.maxRecipients,
            )

            if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) return

            if (reconcileResult.isValidEvidenceFor(
                expectedClientRequestId = intent.clientRequestId,
                expectedCampaignId = campaignId,
                expectedRewardCredits = intent.rewardCredits,
                expectedMaxRecipients = intent.maxRecipients,
            )) {
                // Xác nhận máy chủ đã ghi nhận thành công từ trước
                CampaignTesterBonusStore.clearPendingIntentIfMatching(
                    context = context,
                    userId = uid,
                    campaignId = campaignId,
                    expectedClientRequestId = intent.clientRequestId,
                )

                val confirmed = CreateTesterBonusResult(
                    success = true,
                    replayed = true,
                    roundId = reconcileResult.roundId,
                    clientRequestId = reconcileResult.clientRequestId,
                    campaignId = reconcileResult.campaignId,
                    rewardCredits = reconcileResult.rewardCredits,
                    maxRecipients = reconcileResult.maxRecipients,
                    paidCount = reconcileResult.paidCount,
                    remainingSlots = reconcileResult.remainingSlots,
                    reservedCredits = reconcileResult.reservedCredits,
                    remainingCredits = reconcileResult.remainingCredits,
                    spentCredits = reconcileResult.spentCredits,
                    refundedCredits = reconcileResult.refundedCredits,
                    ownerBalance = reconcileResult.ownerBalance,
                    status = reconcileResult.status ?: "active",
                )

                _uiState.update {
                    it.copy(
                        isReconciling = false,
                        hasUnresolvedIntent = false,
                        pendingIntent = null,
                        isSuccess = true,
                        confirmedResult = confirmed,
                        hasActiveOrClosingRound = (reconcileResult.status in listOf("active", "closing")),
                        hasTerminalRound = (reconcileResult.status in listOf("closed", "exhausted")),
                        currentBalance = reconcileResult.safeOwnerBalanceInt ?: it.currentBalance,
                        message = localizer.get(R.string.tester_bonus_reconciled_success),
                    )
                }

                try {
                    val updatedRound = repository.getCampaignTesterBonus(campaignId)
                    if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                        _uiState.update { it.copy(
                            existingRound = updatedRound,
                            hasActiveOrClosingRound = updatedRound.isUnsettled,
                            hasTerminalRound = updatedRound.isTerminal,
                        ) }
                    }
                } catch (_: Exception) {}
            } else if (!reconcileResult.found) {
                // Máy chủ xác nhận: client_request_id này CHƯA TỪNG được cam kết trên database
                if (originalError != null && isDefinitiveServerValidationError(originalError)) {
                    // Lỗi từ chối dứt khoát từ server (như INSUFFICIENT_CREDITS, INVALID_INPUT) -> an toàn giải phóng intent
                    CampaignTesterBonusStore.clearPendingIntentIfMatching(
                        context = context,
                        userId = uid,
                        campaignId = campaignId,
                        expectedClientRequestId = intent.clientRequestId,
                    )
                    _uiState.update {
                        it.copy(
                            isReconciling = false,
                            hasUnresolvedIntent = false,
                            pendingIntent = null,
                            error = mapTesterBonusError(originalError),
                        )
                    }
                } else {
                    // Lỗi mơ hồ hoặc chưa rõ ràng -> GIỮ NGUYÊN intent để bảo vệ giao dịch tài chính
                    _uiState.update {
                        it.copy(
                            isReconciling = false,
                            hasUnresolvedIntent = true,
                            error = originalError?.let { err -> mapTesterBonusError(err) }
                                ?: localizer.get(R.string.tester_bonus_unresolved_prompt),
                        )
                    }
                }
            } else {
                // Phản hồi đối soát không hợp lệ -> giữ intent
                _uiState.update {
                    it.copy(
                        isReconciling = false,
                        hasUnresolvedIntent = true,
                        error = localizer.get(R.string.tester_bonus_unresolved_prompt),
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                _uiState.update {
                    it.copy(
                        isReconciling = false,
                        hasUnresolvedIntent = true,
                        error = originalError?.let { err -> mapTesterBonusError(err) }
                            ?: localizer.get(R.string.tester_bonus_unresolved_prompt),
                    )
                }
            }
        }
    }

    fun retryUnresolvedIntent() {
        val state = _uiState.value
        if (state.isActionLoading || state.isReconciling) return
        val intent = state.pendingIntent ?: return

        val opId = ++currentOperationId
        val uid = effectiveUserId() ?: return
        val gen = SessionCacheManager.currentGeneration()

        // Phải đảm bảo intent đang gửi lại thuộc về đúng người dùng hiện tại
        if (intent.userId != uid || intent.campaignId != campaignId) return

        _uiState.update { it.copy(isActionLoading = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val result = repository.createCampaignTesterBonus(
                    campaignId = campaignId,
                    rewardCredits = intent.rewardCredits,
                    maxRecipients = intent.maxRecipients,
                    clientRequestId = intent.clientRequestId,
                )

                if (opId != currentOperationId || uid != effectiveUserId() || gen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                    return@launch
                }

                val isValidEvidence = result.isValidEvidenceFor(
                    expectedClientRequestId = intent.clientRequestId,
                    expectedCampaignId = campaignId,
                    expectedRewardCredits = intent.rewardCredits,
                    expectedMaxRecipients = intent.maxRecipients,
                )

                if (isValidEvidence) {
                    CampaignTesterBonusStore.clearPendingIntentIfMatching(
                        context = context,
                        userId = uid,
                        campaignId = campaignId,
                        expectedClientRequestId = intent.clientRequestId,
                    )

                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            hasUnresolvedIntent = false,
                            pendingIntent = null,
                            isSuccess = true,
                            confirmedResult = result,
                            currentBalance = result.safeOwnerBalanceInt ?: it.currentBalance,
                            message = localizer.get(R.string.tester_bonus_created_success, result.reservedCredits),
                            hasActiveOrClosingRound = (result.status in listOf("active", "closing")),
                            hasTerminalRound = (result.status in listOf("closed", "exhausted")),
                        )
                    }

                    try {
                        val updatedRound = repository.getCampaignTesterBonus(campaignId)
                        if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                            _uiState.update { it.copy(
                                existingRound = updatedRound,
                                hasActiveOrClosingRound = updatedRound.isUnsettled,
                                hasTerminalRound = updatedRound.isTerminal,
                            ) }
                        }
                    } catch (_: Exception) {}
                } else {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            hasUnresolvedIntent = true,
                            error = localizer.get(R.string.tester_bonus_unresolved_prompt),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (opId == currentOperationId && uid == effectiveUserId() && gen == SessionCacheManager.currentGeneration()) {
                    handleCreateError(opId, uid, gen, intent, e)
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun isDefinitiveServerValidationError(error: Throwable): Boolean {
        if (error !is PostgrestRestException) {
            return false
        }
        if (error.code != "P0001" || error.statusCode != 400) {
            return false
        }

        val msg = error.message.orEmpty()
        // CLIENT_REQUEST_ID_CONFLICT, auth, account locked NEVER clear intent
        if (msg.contains("CLIENT_REQUEST_ID_CONFLICT", ignoreCase = true) ||
            msg.contains("ACCOUNT_LOCKED", ignoreCase = true) ||
            msg.contains("NOT_AUTHENTICATED", ignoreCase = true)
        ) {
            return false
        }

        val knownRollbackValidationCodes = listOf(
            "INSUFFICIENT_CREDITS",
            "CAMPAIGN_NOT_OPEN",
            "CAMPAIGN_NOT_ELIGIBLE",
            "NOT_CAMPAIGN_OWNER",
            "PERMISSION_DENIED",
            "INVALID_INPUT",
            "INVALID_ARGUMENT",
            "OVERFLOW_DETECTED",
            "INPUT_OVERFLOW",
            "ACTIVE_ROUND_ALREADY_EXISTS",
        )

        return knownRollbackValidationCodes.any { code ->
            msg.contains(code, ignoreCase = true)
        }
    }

    private fun mapTesterBonusError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is TesterBonusStoreCorruptedStateException ||
                msg.contains("store_corrupted", ignoreCase = true) ||
                msg.contains("corrupt", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_store_corrupted)
            msg.contains("server_update_required", ignoreCase = true) || msg.contains("PGRST202", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_not_supported)
            msg.contains("INSUFFICIENT_CREDITS", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_insufficient_credits_simple)
            msg.contains("ACTIVE_ROUND_ALREADY_EXISTS", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_active_round_exists)
            msg.contains("CAMPAIGN_NOT_OPEN", ignoreCase = true) || msg.contains("CAMPAIGN_NOT_ELIGIBLE", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_campaign_not_open)
            msg.contains("NOT_CAMPAIGN_OWNER", ignoreCase = true) || msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_permission_denied)
            msg.contains("ACCOUNT_LOCKED", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_account_locked)
            msg.contains("CLIENT_REQUEST_ID_CONFLICT", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_conflict)
            msg.contains("OVERFLOW_DETECTED", ignoreCase = true) || msg.contains("INPUT_OVERFLOW", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_overflow_simple)
            msg.contains("INVALID_ARGUMENT", ignoreCase = true) || msg.contains("INVALID_INPUT", ignoreCase = true) ->
                localizer.get(R.string.tester_bonus_err_positive_int)
            msg.contains("NOT_AUTHENTICATED", ignoreCase = true) ->
                localizer.get(R.string.campaign_login_required)
            else ->
                localizer.get(R.string.tester_bonus_err_unknown)
        }
    }
}
