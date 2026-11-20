package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CreateCampaignV2Params
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingCreateCampaignIntent
import com.ntt.crosstest.data.PendingSlotIntent
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SlotFees
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.data.TargetFees
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PlayStoreHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class CreateCampaignUiState(
    val playUrl: String = "",
    val testingUrl: String = "",
    val packageName: String = "",
    val appName: String = "",
    val iconUrl: String = "",
    val description: String = "",
    val targetTesters: Int = 12,
    val testUsername: String = "",
    val testPassword: String = "",
    val slots: List<SlotInfo> = emptyList(),
    val selectedSlotNumber: Int = 1,
    val usedSlots: Int = 0,
    val maxSlots: Int = 1,
    val isLoadingSlots: Boolean = true,
    val isLoadingPolicyQuote: Boolean = true,
    val policyRevision: String? = null,
    val minCreditsToPostApp: Int = 10,
    val slot1PostFee: Int = 0,
    val slotFees: SlotFees = SlotFees(),
    val targetFees: TargetFees = TargetFees(),
    val isUnlockingSlot: Boolean = false,
    val unlockSuccessMsg: String? = null,
    val isLoadingMetadata: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val created: Boolean = false,
    val userCredits: Int = 0,
    val isLoadingCredits: Boolean = true,
    val installedApps: List<PlayStoreHelper.InstalledAppItem> = emptyList(),
    val isLoadingInstalledApps: Boolean = false,
) {
    val slotPostCost: Int
        get() = if (selectedSlotNumber == 1) slot1PostFee else 0

    val targetCost: Int
        get() = targetFees.feeForTarget(targetTesters)

    val totalPostCost: Int
        get() = slotPostCost + targetCost

    val requiredCredits: Int
        get() = totalPostCost + minCreditsToPostApp

    val hasEnoughCredits: Boolean
        get() = userCredits >= requiredCredits
}

class CreateCampaignViewModel(
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateCampaignUiState())
    val uiState: StateFlow<CreateCampaignUiState> = _uiState.asStateFlow()

    private var metadataJob: Job? = null
    private var activeUserId: String? = repository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()
    private val isSlotActionInProgress = AtomicBoolean(false)
    private val isCreateInProgress = AtomicBoolean(false)

    init {
        loadCredits()
        loadSlots()

        viewModelScope.launch {
            repository.sessionStatus.collect {
                val currentUid = repository.currentUserId()
                val currentGen = SessionCacheManager.currentGeneration()
                if (currentUid != activeUserId || currentGen != activeGeneration) {
                    activeUserId = currentUid
                    activeGeneration = currentGen
                    isSlotActionInProgress.set(false)
                    isCreateInProgress.set(false)
                    metadataJob?.cancel()
                    _uiState.update { CreateCampaignUiState() }
                    if (currentUid != null) {
                        loadCredits()
                        loadSlots(forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun loadCredits(forceRefresh: Boolean = false) {
        val uid = repository.currentUserId()
        val gen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            try {
                val profile = repository.getMyProfile(forceRefresh = forceRefresh)
                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch
                _uiState.update {
                    it.copy(
                        userCredits = profile?.credits ?: 0,
                        isLoadingCredits = false,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (repository.currentUserId() == uid && SessionCacheManager.currentGeneration() == gen) {
                    _uiState.update { it.copy(isLoadingCredits = false) }
                }
            }
        }
    }

    fun loadSlots(forceRefresh: Boolean = false) {
        val uid = repository.currentUserId()
        val gen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSlots = true, isLoadingPolicyQuote = true) }
            try {
                val slotsInfo = repository.getMySlotsInfo(forceRefresh = forceRefresh)
                val quote = runCatching {
                    repository.getAllBusinessQuote(forceRefresh = forceRefresh)
                }.getOrNull()

                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch

                val used = slotsInfo.count { it.isOccupied }
                val unlocked = slotsInfo.count { it.isPermanent || (it.isUnlocked && !it.isExpired) }

                val currentSelected = _uiState.value.selectedSlotNumber
                val currentSlot = slotsInfo.find { it.slotNumber == currentSelected }
                val newSelected = if (currentSlot != null && currentSlot.isAvailable) {
                    currentSelected
                } else {
                    slotsInfo.firstOrNull { it.isAvailable }?.slotNumber ?: 1
                }

                _uiState.update {
                    it.copy(
                        slots = slotsInfo,
                        usedSlots = used,
                        maxSlots = unlocked,
                        selectedSlotNumber = newSelected,
                        isLoadingSlots = false,
                        isLoadingPolicyQuote = false,
                        policyRevision = quote?.revision,
                        minCreditsToPostApp = quote?.minCreditsToPostApp ?: it.minCreditsToPostApp,
                        slot1PostFee = quote?.slotFees?.slot1 ?: it.slot1PostFee,
                        slotFees = quote?.slotFees ?: it.slotFees,
                        targetFees = quote?.targetFees ?: it.targetFees,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (repository.currentUserId() == uid && SessionCacheManager.currentGeneration() == gen) {
                    _uiState.update {
                        it.copy(
                            isLoadingSlots = false,
                            isLoadingPolicyQuote = false,
                            policyRevision = null,
                        )
                    }
                }
            }
        }
    }

    /** Tải lại đồng thời số dư và báo giá/Slot (dùng cho nút "Tải lại" khi thiếu báo giá). */
    fun retryLoadData() {
        repository.invalidateBusinessQuote()
        loadCredits(forceRefresh = true)
        loadSlots(forceRefresh = true)
    }

    fun selectSlot(slotNumber: Int) {
        _uiState.update { it.copy(selectedSlotNumber = slotNumber, error = null) }
    }

    fun unlockOrRenewSlot(
        slotNumber: Int,
        expectedCost: Int? = null,
        expectedRevision: String? = null,
    ) {
        if (!isSlotActionInProgress.compareAndSet(false, true)) return
        val currentSlot = _uiState.value.slots.find { it.slotNumber == slotNumber }
        val slotCost = expectedCost ?: currentSlot?.cost ?: SlotInfo.costForSlot(slotNumber)
        val revision = expectedRevision ?: currentSlot?.policyRevision ?: _uiState.value.policyRevision

        if (revision == null) {
            isSlotActionInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.admin_policy_quote_unavailable))
            }
            loadSlots(forceRefresh = true)
            return
        }

        if (_uiState.value.userCredits < slotCost) {
            isSlotActionInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.create_slot_cost_format, slotCost, slotNumber, _uiState.value.userCredits))
            }
            return
        }

        val uid = repository.currentUserId() ?: run {
            isSlotActionInProgress.set(false)
            return
        }
        val gen = SessionCacheManager.currentGeneration()

        val pending = FinancialIntentStore.peekSlotIntent(localizer.context, uid, slotNumber)
        val intentToUse = if (pending != null && pending.expectedCost == slotCost && pending.expectedRevision == revision) {
            pending
        } else {
            val newIntent = PendingSlotIntent(
                userId = uid,
                slotNumber = slotNumber,
                expectedCost = slotCost,
                expectedRevision = revision,
                renewalDays = currentSlot?.renewalDays ?: _uiState.value.slotFees.renewalDays,
                clientRequestId = UUID.randomUUID().toString(),
            )
            FinancialIntentStore.saveSlotIntent(localizer.context, newIntent)
            newIntent
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlockingSlot = true, error = null) }
            try {
                val result = repository.unlockOrRenewSlotV2(
                    slotNumber = intentToUse.slotNumber,
                    clientRequestId = intentToUse.clientRequestId,
                    expectedCost = intentToUse.expectedCost,
                    expectedRevision = intentToUse.expectedRevision,
                )
                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch

                FinancialIntentStore.clearSlotIntent(localizer.context, uid, slotNumber)

                _uiState.update {
                    it.copy(
                        userCredits = result.balance,
                        unlockSuccessMsg = localizer.get(R.string.create_slot_unlock_success_format, slotNumber, result.renewalDays),
                        selectedSlotNumber = slotNumber,
                    )
                }
                loadSlots()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch

                val isConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true

                if (isConflict) {
                    FinancialIntentStore.clearSlotIntent(localizer.context, uid, slotNumber)
                    repository.invalidateBusinessQuote()
                    loadSlots(forceRefresh = true)
                    _uiState.update {
                        it.copy(error = friendlyBusinessPolicyError(e, localizer))
                    }
                } else {
                    val msg = when {
                        e.message?.contains("INSUFFICIENT_CREDITS") == true -> localizer.get(R.string.create_insufficient_credits_slot)
                        e.message?.contains("PREVIOUS_SLOT_NOT_ACTIVE") == true -> localizer.get(R.string.profile_prev_slot_required_format, slotNumber - 1)
                        else -> friendlyBusinessPolicyError(e, localizer)
                    }
                    _uiState.update { it.copy(error = msg) }
                }
            } finally {
                if (repository.currentUserId() == uid && SessionCacheManager.currentGeneration() == gen) {
                    isSlotActionInProgress.set(false)
                    _uiState.update { it.copy(isUnlockingSlot = false) }
                }
            }
        }
    }

    fun clearUnlockSuccess() {
        _uiState.update { it.copy(unlockSuccessMsg = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onPlayUrlChanged(url: String, context: Context? = null) {
        val trimmedUrl = url.trim()
        val extractedPackage = PlayStoreHelper.extractPackageName(trimmedUrl)

        if (!extractedPackage.isNullOrBlank()) {
            val isTestingTrack = trimmedUrl.contains("/apps/testing/") || trimmedUrl.contains("/apps/internaltest/")
            val finalTesting = if (isTestingTrack) trimmedUrl else "https://play.google.com/apps/testing/$extractedPackage"
            val finalPlay = if (!isTestingTrack && trimmedUrl.contains("/store/apps/details")) trimmedUrl else "https://play.google.com/store/apps/details?id=$extractedPackage"

            _uiState.update {
                it.copy(
                    playUrl = finalPlay,
                    testingUrl = finalTesting,
                    packageName = extractedPackage,
                    error = null
                )
            }
            fetchMetadata(extractedPackage, context)
        } else {
            _uiState.update { it.copy(playUrl = trimmedUrl, error = null) }
        }
    }

    fun onTestingUrlChanged(url: String) {
        val trimmedUrl = url.trim()
        _uiState.update { it.copy(testingUrl = trimmedUrl, error = null) }
        val extractedPackage = PlayStoreHelper.extractPackageName(trimmedUrl)
        if (!extractedPackage.isNullOrBlank() && _uiState.value.packageName.isBlank()) {
            onPackageNameChanged(extractedPackage)
        }
    }

    fun onPackageNameChanged(pkg: String, context: Context? = null) {
        val cleanPkg = PlayStoreHelper.extractPackageName(pkg) ?: pkg.trim()
        val isFormatValid = cleanPkg.contains(".")

        val autoTestingUrl = if (isFormatValid && _uiState.value.testingUrl.isBlank()) {
            "https://play.google.com/apps/testing/$cleanPkg"
        } else _uiState.value.testingUrl

        val autoPlayUrl = if (isFormatValid && _uiState.value.playUrl.isBlank()) {
            "https://play.google.com/store/apps/details?id=$cleanPkg"
        } else _uiState.value.playUrl

        _uiState.update {
            it.copy(
                packageName = cleanPkg,
                testingUrl = autoTestingUrl,
                playUrl = autoPlayUrl,
                error = null
            )
        }

        if (isFormatValid) {
            fetchMetadata(cleanPkg, context)
        }
    }

    fun loadInstalledApps(context: Context) {
        if (_uiState.value.installedApps.isNotEmpty() || _uiState.value.isLoadingInstalledApps) return
        val cached = com.ntt.crosstest.util.InstalledAppCache.peekInstalledUserApps()
        if (cached != null) {
            _uiState.update { it.copy(installedApps = cached, isLoadingInstalledApps = false) }
            return
        }
        _uiState.update { it.copy(isLoadingInstalledApps = true) }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingInstalledApps = true) }
            val apps = com.ntt.crosstest.util.InstalledAppCache.getInstalledUserApps(context)
            _uiState.update { it.copy(installedApps = apps, isLoadingInstalledApps = false) }
        }
    }

    fun selectInstalledApp(app: PlayStoreHelper.InstalledAppItem) {
        val pkg = app.packageName
        _uiState.update {
            it.copy(
                packageName = pkg,
                appName = it.appName.ifBlank { app.appName.take(60) },
                testingUrl = "https://play.google.com/apps/testing/$pkg",
                playUrl = "https://play.google.com/store/apps/details?id=$pkg",
                error = null,
            )
        }
        if (app.iconDrawable != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoadingMetadata = true) }
                try {
                    val bitmap = PlayStoreHelper.drawableToBitmap(app.iconDrawable)
                    val firebaseUrl = FirebaseStorageHelper.uploadAppIconBitmap(bitmap, app.packageName)
                    _uiState.update { it.copy(iconUrl = firebaseUrl) }
                } catch (_: Exception) {
                    val base64 = PlayStoreHelper.drawableToBase64(app.iconDrawable).orEmpty()
                    _uiState.update { it.copy(iconUrl = base64) }
                } finally {
                    _uiState.update { it.copy(isLoadingMetadata = false) }
                }
            }
        }
    }

    fun fetchMetadata(packageName: String, context: Context? = null) {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMetadata = true) }
            try {
                // 1. Instant check: inspect locally installed app on device first
                if (context != null) {
                    val localDrawable = PlayStoreHelper.getInstalledAppDrawable(context, packageName)
                    if (localDrawable != null) {
                        try {
                            val pm = context.packageManager
                            val appInfo = pm.getApplicationInfo(packageName, 0)
                            val label = pm.getApplicationLabel(appInfo).toString()
                            _uiState.update { current ->
                                current.copy(appName = current.appName.ifBlank { label.take(60) })
                            }
                        } catch (_: Exception) {}

                        // Upload local icon to Firebase Storage
                        try {
                            val bitmap = PlayStoreHelper.drawableToBitmap(localDrawable)
                            val firebaseUrl = FirebaseStorageHelper.uploadAppIconBitmap(bitmap, packageName)
                            _uiState.update { current ->
                                current.copy(iconUrl = firebaseUrl)
                            }
                            return@launch
                        } catch (_: Exception) {
                            val base64 = PlayStoreHelper.drawableToBase64(localDrawable).orEmpty()
                            _uiState.update { current ->
                                current.copy(iconUrl = current.iconUrl.ifBlank { base64 })
                            }
                            return@launch
                        }
                    }
                }

                // 2. Network check: try public Play Store metadata (provides high-res web icon)
                val webMeta = PlayStoreHelper.fetchPlayStoreMetadata(packageName)
                if (webMeta != null) {
                    _uiState.update { current ->
                        current.copy(
                            appName = current.appName.ifBlank { webMeta.appName.take(60) },
                            iconUrl = if (webMeta.iconUrl.isNotBlank()) webMeta.iconUrl else current.iconUrl,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Ignore silent scraping errors
            } finally {
                _uiState.update { it.copy(isLoadingMetadata = false) }
            }
        }
    }

    fun onIconPicked(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMetadata = true) }
            try {
                val firebaseUrl = FirebaseStorageHelper.uploadAppIconUri(
                    context = context,
                    imageUri = uri,
                    packageName = _uiState.value.packageName.ifBlank { "app" },
                )
                _uiState.update { it.copy(iconUrl = firebaseUrl) }
            } catch (_: Exception) {
                val base64 = PlayStoreHelper.uriToBase64(context, uri).orEmpty()
                _uiState.update { it.copy(iconUrl = base64) }
            } finally {
                _uiState.update { it.copy(isLoadingMetadata = false) }
            }
        }
    }

    fun selectTarget(target: Int) {
        if (target in listOf(12, 15, 20)) {
            _uiState.update { it.copy(targetTesters = target, error = null) }
        }
    }

    fun update(
        appName: String? = null,
        packageName: String? = null,
        playUrl: String? = null,
        testingUrl: String? = null,
        iconUrl: String? = null,
        description: String? = null,
        targetTesters: Int? = null,
        testUsername: String? = null,
        testPassword: String? = null,
    ) {
        _uiState.update {
            it.copy(
                appName = appName?.take(60) ?: it.appName,
                packageName = packageName ?: it.packageName,
                playUrl = playUrl ?: it.playUrl,
                testingUrl = testingUrl ?: it.testingUrl,
                iconUrl = iconUrl ?: it.iconUrl,
                description = description ?: it.description,
                targetTesters = targetTesters ?: it.targetTesters,
                testUsername = testUsername ?: it.testUsername,
                testPassword = testPassword ?: it.testPassword,
                error = null,
            )
        }
    }

    private fun isDefinitiveCreateError(e: Throwable): Boolean {
        if (e is IOException) return false
        val lower = e.message.orEmpty().lowercase()
        return "business_policy_conflict" in lower ||
                "business_policy_price_mismatch" in lower ||
                "request_id_conflict" in lower ||
                "insufficient_credits" in lower ||
                "slot_not_active_or_expired" in lower ||
                "slot_already_in_use" in lower ||
                "package_already_active" in lower ||
                "invalid_package_name" in lower ||
                "invalid_slot_number" in lower ||
                "invalid_target_testers" in lower ||
                "invalid_app_name" in lower ||
                "invalid_description" in lower ||
                "invalid_url" in lower ||
                "invalid_test_credentials" in lower ||
                "invalid_icon_url" in lower ||
                "permission_denied" in lower ||
                "account_locked" in lower ||
                "not_authenticated" in lower
    }

    fun create() {
        val s = _uiState.value
        if (s.isSaving || !isCreateInProgress.compareAndSet(false, true)) return

        if (s.isLoadingCredits || s.isLoadingSlots || s.isLoadingPolicyQuote) {
            isCreateInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.create_loading_data))
            }
            return
        }

        val revision = s.policyRevision
        if (revision.isNullOrBlank()) {
            isCreateInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.admin_policy_quote_unavailable))
            }
            loadSlots(forceRefresh = true)
            return
        }

        if (!s.hasEnoughCredits) {
            isCreateInProgress.set(false)
            val errMsg = if (s.totalPostCost > 0) {
                localizer.get(
                    R.string.create_posting_insufficient_summary_format,
                    s.requiredCredits,
                    s.totalPostCost,
                    s.minCreditsToPostApp,
                    s.userCredits,
                )
            } else {
                localizer.get(
                    R.string.create_posting_min_balance_only_warning_format,
                    s.minCreditsToPostApp,
                    s.userCredits,
                )
            }
            _uiState.update { it.copy(error = errMsg) }
            return
        }

        val chosenSlot = s.slots.find { it.slotNumber == s.selectedSlotNumber }
        if (chosenSlot != null && !chosenSlot.isAvailable) {
            isCreateInProgress.set(false)
            if (chosenSlot.isOccupied) {
                _uiState.update {
                    it.copy(error = localizer.get(R.string.create_slot_in_use_format, chosenSlot.slotNumber))
                }
            } else if (chosenSlot.canUnlock) {
                _uiState.update {
                    it.copy(error = localizer.get(R.string.create_slot_expired_format, chosenSlot.slotNumber))
                }
            }
            return
        }

        if (s.slots.isNotEmpty() && s.slots.none { it.isAvailable }) {
            isCreateInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.create_all_slots_used))
            }
            return
        }

        val cleanPkg = PlayStoreHelper.extractPackageName(s.packageName) ?: s.packageName.trim()
        if (cleanPkg.isBlank()) {
            isCreateInProgress.set(false)
            _uiState.update { it.copy(error = localizer.get(R.string.create_package_required)) }
            return
        }

        if (!cleanPkg.contains(".") || !PlayStoreHelper.isValidPackageName(cleanPkg)) {
            isCreateInProgress.set(false)
            _uiState.update { it.copy(error = localizer.get(R.string.create_package_invalid)) }
            return
        }

        val alreadyActive = s.slots.any {
            it.currentCampaign?.packageName?.equals(cleanPkg, ignoreCase = true) == true
        }
        if (alreadyActive) {
            isCreateInProgress.set(false)
            _uiState.update {
                it.copy(error = localizer.get(R.string.create_package_duplicate_format, cleanPkg))
            }
            return
        }

        val uid = repository.currentUserId() ?: run {
            isCreateInProgress.set(false)
            _uiState.update { it.copy(error = friendlyError(IllegalStateException("NOT_AUTHENTICATED"), localizer)) }
            return
        }
        val gen = SessionCacheManager.currentGeneration()

        val finalAppName = s.appName.trim().ifBlank { cleanPkg }
        val finalTestingUrl = s.testingUrl.trim().ifBlank { "https://play.google.com/apps/testing/$cleanPkg" }
        val finalPlayUrl = s.playUrl.trim().ifBlank { "https://play.google.com/store/apps/details?id=$cleanPkg" }
        // Hash nội dung form khớp v_payload_hash phía server: gửi lại y hệt -> giữ clientRequestId,
        // sửa nội dung sau lỗi mạng mập mờ -> xoay clientRequestId mới.
        val currentMetadataHash = PendingCreateCampaignIntent.computeMetadataHash(
            appName = finalAppName,
            packageName = cleanPkg,
            playUrl = finalPlayUrl,
            testingUrl = finalTestingUrl,
            description = s.description,
            iconUrl = s.iconUrl,
            testUsername = s.testUsername,
            testPassword = s.testPassword,
        )

        val pending = FinancialIntentStore.peekCreateCampaignIntent(
            context = localizer.context,
            userId = uid,
            packageName = cleanPkg,
            slotNumber = s.selectedSlotNumber,
        )
        val intentToUse = if (
            pending != null &&
            pending.targetTesters == s.targetTesters &&
            pending.expectedTotalCost == s.totalPostCost &&
            pending.expectedMinCredits == s.minCreditsToPostApp &&
            pending.expectedRevision == revision &&
            pending.metadataHash == currentMetadataHash
        ) {
            pending
        } else {
            val freshIntent = PendingCreateCampaignIntent(
                userId = uid,
                packageName = cleanPkg,
                slotNumber = s.selectedSlotNumber,
                targetTesters = s.targetTesters,
                expectedTotalCost = s.totalPostCost,
                expectedMinCredits = s.minCreditsToPostApp,
                expectedRevision = revision,
                clientRequestId = UUID.randomUUID().toString(),
                metadataHash = currentMetadataHash,
            )
            FinancialIntentStore.saveCreateCampaignIntent(localizer.context, freshIntent)
            freshIntent
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val res = repository.createCampaignV2(
                    CreateCampaignV2Params(
                        appName = finalAppName,
                        packageName = cleanPkg,
                        playUrl = finalPlayUrl,
                        testingUrl = finalTestingUrl,
                        description = s.description.trim(),
                        targetTesters = intentToUse.targetTesters,
                        iconUrl = s.iconUrl.trim(),
                        testUsername = s.testUsername.trim(),
                        testPassword = s.testPassword.trim(),
                        slotNumber = intentToUse.slotNumber,
                        clientRequestId = intentToUse.clientRequestId,
                        expectedTotalCost = intentToUse.expectedTotalCost,
                        expectedMinCredits = intentToUse.expectedMinCredits,
                        expectedRevision = intentToUse.expectedRevision,
                    )
                )
                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch

                FinancialIntentStore.clearCreateCampaignIntent(
                    context = localizer.context,
                    userId = uid,
                    packageName = cleanPkg,
                    slotNumber = intentToUse.slotNumber,
                )

                _uiState.update {
                    it.copy(
                        userCredits = res.balanceAfter,
                        isSaving = false,
                        created = true,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch

                if (isDefinitiveCreateError(e)) {
                    FinancialIntentStore.clearCreateCampaignIntent(
                        context = localizer.context,
                        userId = uid,
                        packageName = cleanPkg,
                        slotNumber = intentToUse.slotNumber,
                    )
                }

                val isPolicyConflict = e.message?.contains("BUSINESS_POLICY_CONFLICT", ignoreCase = true) == true ||
                        e.message?.contains("BUSINESS_POLICY_PRICE_MISMATCH", ignoreCase = true) == true

                if (isPolicyConflict) {
                    repository.invalidateBusinessQuote()
                    loadCredits()
                    loadSlots(forceRefresh = true)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = friendlyBusinessPolicyError(e, localizer),
                        )
                    }
                } else {
                    val msg = e.message.orEmpty()
                    val isInsufficient = msg.contains("INSUFFICIENT_CREDITS")
                    val isSlotError = msg.contains("SLOT_NOT_ACTIVE_OR_EXPIRED") || msg.contains("SLOT_ALREADY_IN_USE")
                    if (isInsufficient) {
                        // Số dư trên máy có thể đã lệch so với server: làm mới rồi mới hiển thị lỗi theo số dư thật.
                        val freshCredits = runCatching {
                            repository.getMyProfile(forceRefresh = true)?.credits
                        }.getOrNull()
                        if (repository.currentUserId() != uid || SessionCacheManager.currentGeneration() != gen) return@launch
                        val currentCredits = freshCredits ?: s.userCredits
                        _uiState.update { it.copy(userCredits = currentCredits) }
                        val errorMsg = if (msg.contains("INSUFFICIENT_CREDITS_FOR_TARGET") || s.totalPostCost > 0) {
                            localizer.get(
                                R.string.create_posting_insufficient_summary_format,
                                s.requiredCredits,
                                s.totalPostCost,
                                s.minCreditsToPostApp,
                                currentCredits,
                            )
                        } else {
                            localizer.get(
                                R.string.create_posting_min_balance_only_warning_format,
                                s.minCreditsToPostApp,
                                currentCredits,
                            )
                        }
                        _uiState.update { it.copy(isSaving = false, error = errorMsg) }
                        return@launch
                    }
                    if (isSlotError) {
                        loadSlots(forceRefresh = true)
                    }
                    val errorMsg = when {
                        msg.contains("SLOT_NOT_ACTIVE_OR_EXPIRED") ->
                            localizer.get(R.string.create_slot_not_active)
                        msg.contains("SLOT_ALREADY_IN_USE") ->
                            localizer.get(R.string.create_slot_busy)
                        msg.contains("PACKAGE_ALREADY_ACTIVE") ->
                            localizer.get(R.string.create_package_duplicate_format, cleanPkg)
                        msg.contains("INVALID_PACKAGE_NAME") ->
                            localizer.get(R.string.create_package_invalid)
                        else -> friendlyBusinessPolicyError(e, localizer)
                    }
                    _uiState.update { it.copy(isSaving = false, error = errorMsg) }
                }
            } finally {
                if (repository.currentUserId() == uid && SessionCacheManager.currentGeneration() == gen) {
                    isCreateInProgress.set(false)
                }
            }
        }
    }
}
