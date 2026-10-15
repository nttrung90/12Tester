package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PlayStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    val isUnlockingSlot: Boolean = false,
    val unlockSuccessMsg: String? = null,
    val isLoadingMetadata: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val created: Boolean = false,
    val userCredits: Int = 100,
    val isLoadingCredits: Boolean = true,
    val isGuideConfirmed: Boolean = false,
    val installedApps: List<PlayStoreHelper.InstalledAppItem> = emptyList(),
    val isLoadingInstalledApps: Boolean = false,
) {
    val targetCost: Int get() = when (targetTesters) {
        15 -> 10
        20 -> 20
        else -> 0
    }
}

class CreateCampaignViewModel(
    private val repository: CampaignRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateCampaignUiState())
    val uiState: StateFlow<CreateCampaignUiState> = _uiState.asStateFlow()

    private var metadataJob: Job? = null

    init {
        loadCredits()
        loadSlots()
        observeGuideConfirmed()
    }

    private fun observeGuideConfirmed() {
        viewModelScope.launch {
            settingsRepository.isTestingGuideConfirmed.collect { confirmed ->
                _uiState.update { it.copy(isGuideConfirmed = confirmed) }
            }
        }
    }

    fun confirmGuide() {
        viewModelScope.launch {
            settingsRepository.setTestingGuideConfirmed(true)
        }
    }

    private fun loadCredits() {
        viewModelScope.launch {
            try {
                val profile = repository.getMyProfile()
                _uiState.update {
                    it.copy(
                        userCredits = profile?.credits ?: 0,
                        isLoadingCredits = false,
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoadingCredits = false) }
            }
        }
    }

    fun loadSlots() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSlots = true) }
            try {
                val slotsInfo = repository.getMySlotsInfo()
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
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoadingSlots = false) }
            }
        }
    }

    fun selectSlot(slotNumber: Int) {
        _uiState.update { it.copy(selectedSlotNumber = slotNumber, error = null) }
    }

    fun unlockOrRenewSlot(slotNumber: Int) {
        if (_uiState.value.isUnlockingSlot) return
        val slotCost = SlotInfo.costForSlot(slotNumber)
        if (_uiState.value.userCredits < slotCost) {
            _uiState.update {
                it.copy(error = "Bạn cần $slotCost Credit để mở khóa Slot $slotNumber (hiện có ${_uiState.value.userCredits} Credit).")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlockingSlot = true, error = null) }
            try {
                val result = repository.unlockOrRenewSlot(slotNumber)
                _uiState.update {
                    it.copy(
                        userCredits = result.balance,
                        unlockSuccessMsg = "Mở khóa Slot $slotNumber thành công (+16 ngày)!",
                        selectedSlotNumber = slotNumber,
                    )
                }
                loadSlots()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true -> "Bạn không đủ Credit để mở khóa slot này."
                    e.message?.contains("PREVIOUS_SLOT_NOT_ACTIVE") == true -> "Bạn cần mở khóa hoặc gia hạn Slot ${slotNumber - 1} trước."
                    else -> friendlyError(e)
                }
                _uiState.update { it.copy(error = msg) }
            } finally {
                _uiState.update { it.copy(isUnlockingSlot = false) }
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
        val cached = com.ntt.crosstest.util.InstalledAppCache.getInstalledUserApps(context)
        if (cached.isNotEmpty()) {
            _uiState.update { it.copy(installedApps = cached, isLoadingInstalledApps = false) }
            return
        }
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
            } catch (_: Exception) {
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

    fun create() {
        val s = _uiState.value
        if (s.isSaving) return

        if (s.isLoadingCredits || s.isLoadingSlots) {
            _uiState.update {
                it.copy(error = "Đang tải dữ liệu tài khoản và slot, vui lòng đợi trong giây lát...")
            }
            return
        }

        val targetUpgradeCost = s.targetCost
        if (s.userCredits <= 10) {
            _uiState.update {
                it.copy(error = "Bạn cần có trên 10 Credit để đăng app (hiện có ${s.userCredits} Credit). Hãy tham gia test app để kiếm thêm Credit.")
            }
            return
        }

        if (targetUpgradeCost > 0 && s.userCredits < targetUpgradeCost + 10) {
            _uiState.update {
                it.copy(error = "Bạn cần ít nhất ${targetUpgradeCost + 10} Credit để đăng app với mục tiêu ${s.targetTesters} Test/ngày (phí nâng mục tiêu: $targetUpgradeCost Credit, số dư hiện có: ${s.userCredits} Credit).")
            }
            return
        }

        val chosenSlot = s.slots.find { it.slotNumber == s.selectedSlotNumber }
        if (chosenSlot != null && !chosenSlot.isAvailable) {
            if (chosenSlot.isOccupied) {
                _uiState.update {
                    it.copy(error = "Slot ${chosenSlot.slotNumber} đang có ứng dụng chạy. Vui lòng chọn slot trống khác hoặc mở thêm slot mới.")
                }
            } else if (chosenSlot.canUnlock) {
                _uiState.update {
                    it.copy(error = "Slot ${chosenSlot.slotNumber} chưa mở hoặc đã hết hạn. Vui lòng mở khóa trước khi đăng app.")
                }
            }
            return
        }

        if (s.slots.isNotEmpty() && s.slots.none { it.isAvailable }) {
            _uiState.update {
                it.copy(error = "Bạn đã dùng hết tất cả các slot hiện có. Hãy mở khóa thêm slot hoặc đóng app cũ.")
            }
            return
        }

        val cleanPkg = PlayStoreHelper.extractPackageName(s.packageName) ?: s.packageName.trim()
        if (cleanPkg.isBlank()) {
            _uiState.update { it.copy(error = "Vui lòng nhập Package name hoặc dán link Play Store.") }
            return
        }

        if (!cleanPkg.contains(".") || !PlayStoreHelper.isValidPackageName(cleanPkg)) {
            _uiState.update { it.copy(error = "Package name không hợp lệ (Ví dụ đúng: com.example.myapp).") }
            return
        }

        // Check if user already has an active campaign with this package name
        val alreadyActive = s.slots.any { it.currentCampaign?.packageName?.equals(cleanPkg, ignoreCase = true) == true }
        if (alreadyActive) {
            _uiState.update {
                it.copy(error = "Ứng dụng với package \"$cleanPkg\" đang chạy trên một Slot khác của bạn.")
            }
            return
        }

        val finalAppName = s.appName.trim().ifBlank { cleanPkg }
        val finalTestingUrl = s.testingUrl.trim().ifBlank { "https://play.google.com/apps/testing/$cleanPkg" }
        val finalPlayUrl = s.playUrl.trim().ifBlank { "https://play.google.com/store/apps/details?id=$cleanPkg" }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                repository.createCampaign(
                    Campaign(
                        appName = finalAppName,
                        packageName = cleanPkg,
                        playUrl = finalPlayUrl,
                        testingUrl = finalTestingUrl,
                        description = s.description.trim(),
                        targetTesters = s.targetTesters,
                        iconUrl = s.iconUrl.trim(),
                        testUsername = s.testUsername.trim(),
                        testPassword = s.testPassword.trim(),
                        slotNumber = s.selectedSlotNumber,
                        startDate = LocalDate.now().toString(),
                    )
                )
                _uiState.update { it.copy(isSaving = false, created = true) }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS_FOR_TARGET") == true ->
                        "Bạn không đủ Credit để nâng cấp mục tiêu test này (cần $targetUpgradeCost Credit)."
                    e.message?.contains("INSUFFICIENT_CREDITS") == true -> "Bạn cần có trên 10 Credit để đăng app mới."
                    e.message?.contains("SLOT_NOT_ACTIVE_OR_EXPIRED") == true -> "Slot đăng app đã hết hạn hoặc chưa kích hoạt."
                    e.message?.contains("SLOT_ALREADY_IN_USE") == true -> "Slot này đang có một app khác chạy."
                    else -> friendlyError(e)
                }
                _uiState.update { it.copy(isSaving = false, error = errorMsg) }
            }
        }
    }
}
