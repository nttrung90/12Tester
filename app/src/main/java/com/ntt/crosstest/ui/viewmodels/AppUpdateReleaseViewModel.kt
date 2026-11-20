package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AppUpdateRelease
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingPublishUpdateIntent
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.util.UiStateMessageResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AppUpdateReleaseUiState(
    val isLoading: Boolean = false,
    val isPublishing: Boolean = false,
    val isClosing: Boolean = false,
    val isQuoteLoading: Boolean = false,
    val quoteError: String? = null,
    val campaign: Campaign? = null,
    val ownerProfile: Profile? = null,
    val currentRelease: AppUpdateRelease? = null,
    val releaseHistory: List<AppUpdateRelease> = emptyList(),
    val versionName: String = "",
    val versionCode: String = "",
    val releaseNotes: String = "",
    val clientOperationId: String = UUID.randomUUID().toString(),
    val rewardPerClaim: Int = 6,
    val maxClaims: Int = 20,
    val reservedBudget: Int = 120,
    val policyRevision: String? = null,
    val pendingPublishIntent: PendingPublishUpdateIntent? = null,
    error: String? = null,
    successMessage: String? = null,
) {
    private val rawError: String? = error
    private val rawSuccessMessage: String? = successMessage

    val error: String?
        get() = UiStateMessageResolver.resolve(rawError)

    val successMessage: String?
        get() = UiStateMessageResolver.resolve(rawSuccessMessage)

    val hasValidQuote: Boolean
        get() = !policyRevision.isNullOrBlank() && reservedBudget > 0 && rewardPerClaim > 0 && maxClaims > 0 && quoteError == null

    fun copy(
        isLoading: Boolean = this.isLoading,
        isPublishing: Boolean = this.isPublishing,
        isClosing: Boolean = this.isClosing,
        isQuoteLoading: Boolean = this.isQuoteLoading,
        quoteError: String? = this.quoteError,
        campaign: Campaign? = this.campaign,
        ownerProfile: Profile? = this.ownerProfile,
        currentRelease: AppUpdateRelease? = this.currentRelease,
        releaseHistory: List<AppUpdateRelease> = this.releaseHistory,
        versionName: String = this.versionName,
        versionCode: String = this.versionCode,
        releaseNotes: String = this.releaseNotes,
        clientOperationId: String = this.clientOperationId,
        rewardPerClaim: Int = this.rewardPerClaim,
        maxClaims: Int = this.maxClaims,
        reservedBudget: Int = this.reservedBudget,
        policyRevision: String? = this.policyRevision,
        pendingPublishIntent: PendingPublishUpdateIntent? = this.pendingPublishIntent,
        error: String? = this.rawError,
        successMessage: String? = this.rawSuccessMessage,
    ): AppUpdateReleaseUiState = AppUpdateReleaseUiState(
        isLoading = isLoading,
        isPublishing = isPublishing,
        isClosing = isClosing,
        isQuoteLoading = isQuoteLoading,
        quoteError = quoteError,
        campaign = campaign,
        ownerProfile = ownerProfile,
        currentRelease = currentRelease,
        releaseHistory = releaseHistory,
        versionName = versionName,
        versionCode = versionCode,
        releaseNotes = releaseNotes,
        clientOperationId = clientOperationId,
        rewardPerClaim = rewardPerClaim,
        maxClaims = maxClaims,
        reservedBudget = reservedBudget,
        policyRevision = policyRevision,
        pendingPublishIntent = pendingPublishIntent,
        error = error,
        successMessage = successMessage,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppUpdateReleaseUiState) return false
        return isLoading == other.isLoading &&
            isPublishing == other.isPublishing &&
            isClosing == other.isClosing &&
            isQuoteLoading == other.isQuoteLoading &&
            quoteError == other.quoteError &&
            campaign == other.campaign &&
            ownerProfile == other.ownerProfile &&
            currentRelease == other.currentRelease &&
            releaseHistory == other.releaseHistory &&
            versionName == other.versionName &&
            versionCode == other.versionCode &&
            releaseNotes == other.releaseNotes &&
            clientOperationId == other.clientOperationId &&
            rewardPerClaim == other.rewardPerClaim &&
            maxClaims == other.maxClaims &&
            reservedBudget == other.reservedBudget &&
            policyRevision == other.policyRevision &&
            pendingPublishIntent == other.pendingPublishIntent &&
            rawError == other.rawError &&
            rawSuccessMessage == other.rawSuccessMessage
    }

    override fun hashCode(): Int {
        var result = isLoading.hashCode()
        result = 31 * result + isPublishing.hashCode()
        result = 31 * result + isClosing.hashCode()
        result = 31 * result + isQuoteLoading.hashCode()
        result = 31 * result + (quoteError?.hashCode() ?: 0)
        result = 31 * result + (campaign?.hashCode() ?: 0)
        result = 31 * result + (ownerProfile?.hashCode() ?: 0)
        result = 31 * result + (currentRelease?.hashCode() ?: 0)
        result = 31 * result + releaseHistory.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + releaseNotes.hashCode()
        result = 31 * result + clientOperationId.hashCode()
        result = 31 * result + rewardPerClaim.hashCode()
        result = 31 * result + maxClaims.hashCode()
        result = 31 * result + reservedBudget.hashCode()
        result = 31 * result + (policyRevision?.hashCode() ?: 0)
        result = 31 * result + (pendingPublishIntent?.hashCode() ?: 0)
        result = 31 * result + (rawError?.hashCode() ?: 0)
        result = 31 * result + (rawSuccessMessage?.hashCode() ?: 0)
        return result
    }

    val availableCredits: Int get() = ownerProfile?.credits ?: 0
    val refundFromCurrent: Int get() = if (currentRelease?.status == "active") currentRelease.remainingCredits else 0
    val effectiveBudget: Int get() = availableCredits + refundFromCurrent
    val projectedRemaining: Int get() = effectiveBudget - reservedBudget
    val hasEnoughCredits: Boolean get() = effectiveBudget >= reservedBudget

    val canPublish: Boolean
        get() {
            val code = versionCode.trim().toLongOrNull() ?: return false
            val historyHighest = releaseHistory.maxOfOrNull { it.targetVersionCode } ?: 0L
            val highest = maxOf(currentRelease?.targetVersionCode ?: 0L, historyHighest)
            return hasValidQuote &&
                !isLoading &&
                !isQuoteLoading &&
                hasEnoughCredits &&
                versionName.isNotBlank() &&
                versionName.length <= 100 &&
                code > 0 &&
                code > highest &&
                releaseNotes.isNotBlank() &&
                releaseNotes.length <= 4000 &&
                !isPublishing &&
                !isClosing
        }
}

class AppUpdateReleaseViewModel(
    val campaignId: String,
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val actionLock = AtomicBoolean(false)
    private var activeUserId: String? = repository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    private val loadDataSeq = AtomicLong(0L)
    private val publishSeq = AtomicLong(0L)
    private val closeSeq = AtomicLong(0L)

    private var lastSubmittedOperationHash: String? = null

    private val _uiState = MutableStateFlow(
        AppUpdateReleaseUiState(
            isLoading = true,
            isQuoteLoading = true,
            policyRevision = null,
        )
    )
    val uiState: StateFlow<AppUpdateReleaseUiState> = _uiState.asStateFlow()

    init {
        loadData()
        viewModelScope.launch {
            repository.sessionStatus.collect {
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != activeUserId || currentGen != activeGeneration) {
                    handleAccountChange(currentUid, currentGen)
                }
            }
        }
    }

    private fun handleAccountChange(newUid: String?, newGen: Long) {
        loadDataSeq.incrementAndGet()
        publishSeq.incrementAndGet()
        closeSeq.incrementAndGet()
        actionLock.set(false)
        activeUserId = newUid
        activeGeneration = newGen
        lastSubmittedOperationHash = null

        _uiState.update { AppUpdateReleaseUiState() }
        loadData()
    }

    fun refresh() {
        loadData()
    }

    private fun checkAndRotateOperationId() {
        if (lastSubmittedOperationHash != null) {
            lastSubmittedOperationHash = null
            _uiState.update { it.copy(clientOperationId = UUID.randomUUID().toString()) }
        }
    }

    fun onVersionNameChange(name: String) {
        checkAndRotateOperationId()
        _uiState.update { it.copy(versionName = name, error = null) }
    }

    fun onVersionCodeChange(code: String) {
        checkAndRotateOperationId()
        val filtered = code.filter { it.isDigit() }
        _uiState.update { it.copy(versionCode = filtered, error = null) }
    }

    fun onReleaseNotesChange(notes: String) {
        checkAndRotateOperationId()
        _uiState.update { it.copy(releaseNotes = notes, error = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }

    private fun loadData(preserveError: Boolean = false) {
        val seq = loadDataSeq.incrementAndGet()
        val reqUid = activeUserId
        val reqGen = activeGeneration

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isQuoteLoading = true, error = if (preserveError) it.error else null, quoteError = null) }
            try {
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = true)
                val historyResult = repository.getCampaignUpdateHistory(campaignId)
                val history = historyResult.getOrDefault(emptyList())
                val myProfile = repository.getMyProfile(forceRefresh = true)

                val quoteResult = runCatching { repository.getAppUpdateQuote() }
                if (quoteResult.exceptionOrNull() is CancellationException) {
                    throw quoteResult.exceptionOrNull() as CancellationException
                }
                val quote = quoteResult.getOrNull()
                val qErr = quoteResult.exceptionOrNull()?.let { friendlyBusinessPolicyError(it, localizer) }

                val pending = reqUid?.let { uid ->
                    FinancialIntentStore.peekPublishIntent(localizer.context, uid, campaignId)
                }

                if (seq != loadDataSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isQuoteLoading = false,
                        quoteError = qErr,
                        policyRevision = quote?.revision,
                        rewardPerClaim = quote?.rewardPerClaim ?: it.rewardPerClaim,
                        maxClaims = quote?.maxClaims ?: it.maxClaims,
                        reservedBudget = quote?.reservedBudget ?: it.reservedBudget,
                        campaign = detail?.campaign,
                        ownerProfile = myProfile ?: detail?.myProfile,
                        currentRelease = detail?.appUpdate ?: history.firstOrNull { r -> r.status == "active" },
                        releaseHistory = history,
                        pendingPublishIntent = pending,
                        clientOperationId = pending?.clientOperationId ?: it.clientOperationId.takeIf { id -> id.isNotBlank() } ?: UUID.randomUUID().toString(),
                        error = if (preserveError) it.error ?: qErr else qErr,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != loadDataSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isQuoteLoading = false,
                        error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_load_failed) },
                    )
                }
            }
        }
    }

    fun publishUpdate() {
        val state = _uiState.value
        if (!state.canPublish) return
        if (!actionLock.compareAndSet(false, true)) return

        val targetCode = state.versionCode.trim().toLongOrNull() ?: run {
            actionLock.set(false)
            return
        }
        val currentExpectedId = state.currentRelease?.id
        val reqUid = activeUserId ?: run {
            actionLock.set(false)
            return
        }
        val reqGen = activeGeneration
        val seq = publishSeq.incrementAndGet()

        val submittedHash = "${state.versionName}:${state.versionCode}:${state.releaseNotes}:${state.reservedBudget}:${state.policyRevision}"
        lastSubmittedOperationHash = submittedHash

        val intent = PendingPublishUpdateIntent(
            userId = reqUid,
            campaignId = campaignId,
            versionName = state.versionName.trim(),
            targetVersionCode = targetCode,
            releaseNotes = state.releaseNotes.trim(),
            expectedCurrentReleaseId = currentExpectedId,
            expectedBudget = state.reservedBudget,
            expectedRevision = state.policyRevision ?: "",
            clientOperationId = state.clientOperationId,
        )
        FinancialIntentStore.savePublishIntent(localizer.context, intent)

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, error = null, successMessage = null) }
            try {
                val result = repository.publishAppUpdateV2(
                    campaignId = campaignId,
                    versionName = intent.versionName,
                    targetVersionCode = intent.targetVersionCode,
                    releaseNotes = intent.releaseNotes,
                    clientOperationId = intent.clientOperationId,
                    expectedBudget = intent.expectedBudget,
                    expectedRevision = intent.expectedRevision,
                    expectedCurrentReleaseId = intent.expectedCurrentReleaseId,
                ).getOrThrow()

                if (seq != publishSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }

                if (result.success && result.release != null) {
                    FinancialIntentStore.clearPublishIntent(localizer.context, reqUid, campaignId)
                    val updatedHistory = repository.getCampaignUpdateHistory(campaignId).getOrDefault(emptyList())
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)

                    lastSubmittedOperationHash = null
                    _uiState.update {
                        val userKeptTyping = "${it.versionName}:${it.versionCode}:${it.releaseNotes}" != "${intent.versionName}:${intent.targetVersionCode}:${intent.releaseNotes}"
                        it.copy(
                            isPublishing = false,
                            currentRelease = result.release,
                            releaseHistory = updatedHistory,
                            ownerProfile = updatedProfile ?: it.ownerProfile?.copy(credits = result.ownerCredits),
                            versionName = if (userKeptTyping) it.versionName else "",
                            versionCode = if (userKeptTyping) it.versionCode else "",
                            releaseNotes = if (userKeptTyping) it.releaseNotes else "",
                            pendingPublishIntent = null,
                            clientOperationId = UUID.randomUUID().toString(),
                            successMessage = localizer.get(R.string.app_update_apply_success_format, result.release.versionName),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = friendlyError(result.message, localizer).ifBlank { localizer.get(R.string.app_update_publish_failed) },
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != publishSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true
                if (isConflict) {
                    FinancialIntentStore.clearPublishIntent(localizer.context, reqUid, campaignId)
                    lastSubmittedOperationHash = null
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            clientOperationId = UUID.randomUUID().toString(),
                            pendingPublishIntent = null,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                    loadData(preserveError = true)
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            pendingPublishIntent = intent,
                            error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_apply_error) },
                        )
                    }
                }
            } finally {
                if (reqUid == repository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    actionLock.set(false)
                    _uiState.update { it.copy(isPublishing = false) }
                }
            }
        }
    }

    fun retryPendingPublish() {
        val pending = _uiState.value.pendingPublishIntent ?: return
        if (!actionLock.compareAndSet(false, true)) return
        val reqUid = activeUserId ?: run {
            actionLock.set(false)
            return
        }
        val reqGen = activeGeneration
        val seq = publishSeq.incrementAndGet()

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, error = null, successMessage = null) }
            try {
                val result = repository.publishAppUpdateV2(
                    campaignId = pending.campaignId,
                    versionName = pending.versionName,
                    targetVersionCode = pending.targetVersionCode,
                    releaseNotes = pending.releaseNotes,
                    clientOperationId = pending.clientOperationId,
                    expectedBudget = pending.expectedBudget,
                    expectedRevision = pending.expectedRevision,
                    expectedCurrentReleaseId = pending.expectedCurrentReleaseId,
                ).getOrThrow()

                if (seq != publishSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }

                if (result.success && result.release != null) {
                    FinancialIntentStore.clearPublishIntent(localizer.context, reqUid, campaignId)
                    val updatedHistory = repository.getCampaignUpdateHistory(campaignId).getOrDefault(emptyList())
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)

                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            currentRelease = result.release,
                            releaseHistory = updatedHistory,
                            ownerProfile = updatedProfile ?: it.ownerProfile?.copy(credits = result.ownerCredits),
                            pendingPublishIntent = null,
                            versionName = "",
                            versionCode = "",
                            releaseNotes = "",
                            clientOperationId = UUID.randomUUID().toString(),
                            successMessage = localizer.get(R.string.app_update_apply_success_format, result.release.versionName),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = friendlyError(result.message, localizer).ifBlank { localizer.get(R.string.app_update_publish_failed) },
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != publishSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true
                if (isConflict) {
                    FinancialIntentStore.clearPublishIntent(localizer.context, reqUid, campaignId)
                    _uiState.update { it.copy(clientOperationId = UUID.randomUUID().toString(), pendingPublishIntent = null) }
                    loadData()
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_apply_error) },
                        )
                    }
                }
            } finally {
                if (reqUid == repository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    actionLock.set(false)
                    _uiState.update { it.copy(isPublishing = false) }
                }
            }
        }
    }

    fun discardPendingPublish() {
        val uid = activeUserId ?: return
        FinancialIntentStore.clearPublishIntent(localizer.context, uid, campaignId)
        _uiState.update { it.copy(pendingPublishIntent = null, clientOperationId = UUID.randomUUID().toString()) }
    }

    fun closeCurrentRelease() {
        val current = _uiState.value.currentRelease ?: return
        if (current.status != "active") return
        if (!actionLock.compareAndSet(false, true)) return

        val reqUid = activeUserId ?: run {
            actionLock.set(false)
            return
        }
        val reqGen = activeGeneration
        val seq = closeSeq.incrementAndGet()

        viewModelScope.launch {
            _uiState.update { it.copy(isClosing = true, error = null, successMessage = null) }
            try {
                val result = repository.closeAppUpdateRelease(current.id, campaignId = campaignId).getOrThrow()
                if (seq != closeSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                if (result.success) {
                    val updatedHistory = repository.getCampaignUpdateHistory(campaignId).getOrDefault(emptyList())
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)

                    _uiState.update {
                        it.copy(
                            isClosing = false,
                            currentRelease = updatedHistory.firstOrNull { r -> r.id == current.id }?.copy(status = "closed"),
                            releaseHistory = updatedHistory,
                            ownerProfile = updatedProfile ?: it.ownerProfile?.copy(credits = result.ownerCredits),
                            successMessage = localizer.get(R.string.app_update_close_success_format, result.refundAmount),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isClosing = false,
                            error = localizer.get(R.string.app_update_close_failed),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != closeSeq.get() || reqUid != repository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isClosing = false,
                        error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_close_error) },
                    )
                }
            } finally {
                if (reqUid == repository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    actionLock.set(false)
                    _uiState.update { it.copy(isClosing = false) }
                }
            }
        }
    }
}
