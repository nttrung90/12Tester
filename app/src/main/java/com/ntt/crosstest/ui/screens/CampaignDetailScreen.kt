package com.ntt.crosstest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.ui.components.CreditRewardOverlay
import com.ntt.crosstest.ui.components.FullscreenImageViewer
import com.ntt.crosstest.ui.components.TesterImageComparisonDialog
import com.ntt.crosstest.util.ImageHashHelper
import com.ntt.crosstest.util.ReportComparisonSelector
import com.ntt.crosstest.util.ScreenshotManager
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.data.EgressTriggerSource
import com.ntt.crosstest.util.ScreenshotRequestFactory
import com.ntt.crosstest.util.formatIsoUtcToLocal
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.util.ScreenshotVariant
import com.ntt.crosstest.util.WarningRenderer
import com.ntt.crosstest.util.RejectionReasonRenderer
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.ui.theme.AmberStar
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.ui.viewmodels.CampaignDetailUiState
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignDetailScreen(
    campaignId: String,
    initialMissionId: String? = null,
    autoLaunchNextTest: Boolean = false,
    onBack: () -> Unit,
    onOwnerManageClick: ((String) -> Unit)? = null,
    onNavigateToCampaign: ((String, Boolean) -> Unit)? = null,
    onAccountLocked: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as TestCheoApp
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val localizer = remember(context) { Localizer(context) }
    val viewModel: CampaignDetailViewModel = viewModel(
        key = if (initialMissionId != null) "$campaignId:$initialMissionId" else campaignId,
        factory = viewModelFactory {
            initializer {
                CampaignDetailViewModel(
                    campaignId,
                    app.campaignRepository,
                    app.reportSubmitManager,
                    app.authRepository.currentUserId(),
                    Localizer(app),
                    app,
                    initialMissionId = initialMissionId,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adminPauseDialogState by viewModel.adminPauseDialogState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val testMode by app.settingsRepository.testMode.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.getSavedTestModeSync(context)
    )
    val isTurboMode = testMode == SettingsRepository.TEST_MODE_TURBO
    var autoLaunchConsumed by rememberSaveable(campaignId) { mutableStateOf(false) }
    var autoAdvanceAttempts by remember(uiState.showNextAppPrompt) { mutableStateOf(0) }

    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    var showHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showNotInstalledDialog by rememberSaveable { mutableStateOf(false) }
    var showHowToInstallDialog by rememberSaveable { mutableStateOf(false) }
    var showCannotDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showAdminDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showAdminDeleteMissionDialog by rememberSaveable { mutableStateOf(false) }
    var pinnedMissionIdForDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var adminDeleteMissionReason by rememberSaveable { mutableStateOf("") }
    var showAdminResumeDialog by rememberSaveable { mutableStateOf(false) }
    var previewImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var previewImageExpired by rememberSaveable { mutableStateOf(false) }
    var previewReport by remember { mutableStateOf<TesterReport?>(null) }

    LaunchedEffect(uiState.currentUserId, uiState.sessionGeneration, uiState.isAdmin) {
        viewModel.adminPauseDialogController.syncIdentity(
            userId = uiState.currentUserId,
            generation = uiState.sessionGeneration,
            isAdmin = uiState.isAdmin,
        )
    }

    var isInstalledLocally by remember { mutableStateOf(false) }

    val pickScreenshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onScreenshotSelected(uri, context)
    }

    LifecycleResumeEffect(campaignId, uiState.campaign?.packageName, uiState.appUpdate?.id) {
        val pkg = uiState.campaign?.packageName.orEmpty()
        if (pkg.isNotBlank()) {
            isInstalledLocally = try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) { false }
            viewModel.checkInstalledAppVersion(context)
        }
        viewModel.refresh(forceRefresh = false, triggerSource = EgressTriggerSource.RESUME)
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.appUpdate?.id, uiState.appUpdate?.targetVersionCode) {
        if (uiState.appUpdate != null && !uiState.isOwner) {
            viewModel.checkInstalledAppVersion(context)
        }
    }

    LaunchedEffect(uiState.updateClaimSuccessMessage) {
        uiState.updateClaimSuccessMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUpdateClaimMessages()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            if (!adminPauseDialogState.isOpen) {
                snackbarHostState.showSnackbar(it)
            }
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.isUserLocked) {
        if (uiState.isUserLocked) {
            onAccountLocked()
        }
    }

    val autoLaunchTodayReport = uiState.myTodayReport
    val autoLaunchApprovedToday = (uiState.checkedInToday || autoLaunchTodayReport?.isApproved == true) &&
        autoLaunchTodayReport?.isPending != true && autoLaunchTodayReport?.isRejected != true
    val autoLaunchPendingToday = autoLaunchTodayReport?.isPending == true

    // Test siêu tốc: tự chọn & chuyển sang app test tiếp theo ngay khi có kết quả gợi ý.
    LaunchedEffect(
        isTurboMode,
        uiState.showNextAppPrompt,
        uiState.nextSuggestionLoading,
        uiState.nextCampaignToTest?.id,
        uiState.nextSuggestionFailed,
        autoAdvanceAttempts,
    ) {
        if (!isTurboMode || !uiState.showNextAppPrompt || uiState.nextSuggestionLoading) return@LaunchedEffect
        if (uiState.nextCampaignToTest == null || uiState.nextSuggestionFailed) return@LaunchedEffect
        if (autoAdvanceAttempts >= 2 || onNavigateToCampaign == null) return@LaunchedEffect

        val nextId = viewModel.validateAndConsumeNextCampaign()
        if (!nextId.isNullOrBlank()) {
            viewModel.dismissNextAppPrompt()
            onNavigateToCampaign(nextId, true)
        } else {
            autoAdvanceAttempts += 1
        }
    }

    // Test siêu tốc: khi vừa chuyển sang app tiếp theo, tự mở app (hoặc tải & cài đặt nếu chưa cài).
    LaunchedEffect(
        autoLaunchNextTest,
        autoLaunchConsumed,
        uiState.campaign?.id,
        uiState.campaign?.status,
        uiState.isLoading,
        uiState.isBackgroundSyncing,
        uiState.isOwner,
        uiState.unreadWarning,
        autoLaunchApprovedToday,
        autoLaunchPendingToday,
    ) {
        if (!autoLaunchNextTest || autoLaunchConsumed) return@LaunchedEffect
        val targetCampaign = uiState.campaign ?: return@LaunchedEffect
        if (uiState.isLoading || uiState.isBackgroundSyncing) return@LaunchedEffect
        if (uiState.unreadWarning != null) return@LaunchedEffect

        autoLaunchConsumed = true
        if (!targetCampaign.isOpen || uiState.isOwner || autoLaunchApprovedToday || autoLaunchPendingToday) {
            return@LaunchedEffect
        }
        launchOrInstallCampaignApp(
            context = context,
            campaign = targetCampaign,
            isOwner = uiState.isOwner,
            viewModel = viewModel,
        )
    }

    // Warning Dialog for tester
    val activeWarning = uiState.unreadWarning
    if (activeWarning != null) {
        AlertDialog(
            onDismissRequest = { /* Must acknowledge */ },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.statusColors.warning,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.warning_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = WarningRenderer.render(activeWarning, Localizer(context)),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.warning_quota_format, uiState.weeklyWarningsCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeWarning.id?.let { viewModel.dismissWarning(it) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.warning_confirm_btn))
                }
            }
        )
    }

    val campaign = uiState.campaign

    // Dialog: Gợi ý test app tiếp theo — hiện NGAY khi nộp; upload + RPC chạy nền
    if (uiState.showNextAppPrompt) {
        val nextCamp = uiState.nextCampaignToTest
        val findingNext = uiState.nextSuggestionLoading && nextCamp == null
        val waitingTurboAutoAdvance = isTurboMode &&
            !uiState.nextSuggestionFailed &&
            (uiState.nextSuggestionLoading || (nextCamp != null && autoAdvanceAttempts < 2 && onNavigateToCampaign != null))
        if (waitingTurboAutoAdvance) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissNextAppPrompt() },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.campaign_turbo_next_loading_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.campaign_turbo_next_loading_msg),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissNextAppPrompt() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        } else {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNextAppPrompt() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(if (findingNext || nextCamp != null || uiState.nextSuggestionFailed) R.string.campaign_next_dialog_title else R.string.campaign_done_dialog_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Trạng thái nộp báo cáo (chạy nền, không chặn thao tác)
                    when (uiState.submitStage) {
                        ReportSubmitManager.Stage.SENDING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.campaign_submit_sending_bg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReportSubmitManager.Stage.RETRYING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.statusColors.warning)
                            Text(
                                text = stringResource(R.string.campaign_submit_retrying_format, uiState.submitAttempt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.statusColors.warning,
                            )
                        }
                        ReportSubmitManager.Stage.DONE -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.statusColors.success,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (uiState.isMissionReport) {
                                    stringResource(R.string.mission_report_submitted_waiting)
                                } else {
                                    stringResource(R.string.campaign_submit_sent_ok)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.statusColors.success,
                            )
                        }
                        ReportSubmitManager.Stage.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.campaign_report_not_sent_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = uiState.submitError ?: stringResource(R.string.campaign_submit_failed_bg),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = { viewModel.retrySubmit() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.campaign_submit_retry_btn))
                            }
                        }
                        null -> {}
                    }

                    when {
                        nextCamp != null -> {
                            Text(
                                text = stringResource(
                                    if (uiState.submitStage == ReportSubmitManager.Stage.DONE) {
                                        R.string.campaign_submitted_next_msg
                                    } else {
                                        R.string.campaign_next_prompt_msg
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIcon(
                                        iconUrl = nextCamp.safeIconUrl,
                                        packageName = nextCamp.packageName,
                                        appName = nextCamp.appName,
                                        size = 46.dp,
                                        shapeRadius = 10.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = nextCamp.appName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = nextCamp.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.campaign_reward_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF2E7D32),
                                        )
                                    }
                                }
                            }
                        }
                        findingNext -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF1E88E5))
                                Text(
                                    text = stringResource(R.string.campaign_next_searching),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        uiState.nextSuggestionFailed -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.campaign_next_load_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = stringResource(R.string.campaign_all_done_msg),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    nextCamp != null -> {
                        Button(
                            onClick = {
                                val nextId = viewModel.validateAndConsumeNextCampaign()
                                if (!nextId.isNullOrBlank()) {
                                    viewModel.dismissNextAppPrompt()
                                    if (onNavigateToCampaign != null) {
                                        onNavigateToCampaign(nextId, false)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.campaign_test_now_btn))
                        }
                    }
                    findingNext -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        ) {
                            Text(stringResource(R.string.campaign_finding_next_btn))
                        }
                    }
                    uiState.nextSuggestionFailed -> {
                        Button(
                            onClick = { viewModel.retryPrefetchNextCampaign() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.campaign_next_retry_btn))
                        }
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.dismissNextAppPrompt() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            },
            dismissButton = {
                if (nextCamp != null || uiState.nextSuggestionFailed) {
                    TextButton(onClick = { viewModel.dismissNextAppPrompt() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.no))
                    }
                } else if (findingNext) {
                    TextButton(onClick = { viewModel.dismissNextAppPrompt() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.close))
                    }
                }
            }
        )
        }
    }

    // Dialog: Chưa cài ứng dụng
    if (showNotInstalledDialog && campaign != null) {
        AlertDialog(
            onDismissRequest = { showNotInstalledDialog = false },
            title = { Text(stringResource(R.string.app_not_installed_title)) },
            text = { Text(stringResource(R.string.campaign_install_dialog_msg, campaign.appName, campaign.packageName)) },
            confirmButton = {
                Button(onClick = {
                    showNotInstalledDialog = false
                    viewModel.ensureJoined()
                    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                    openUrlSafely(context, targetUrl, campaign.packageName)
                }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.download_app_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotInstalledDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Dialog: Hướng dẫn cài đặt
    if (showHowToInstallDialog && campaign != null) {
        val groupUrl = stringResource(R.string.post_app_guide_group_url)
        AlertDialog(
            onDismissRequest = { showHowToInstallDialog = false },
            title = { Text(stringResource(R.string.campaign_install_guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.campaign_install_guide_step1))
                    OutlinedButton(
                        onClick = {
                            openUrlSafely(context, groupUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.campaign_install_guide_group_btn), fontSize = 12.sp)
                    }
                    Text(stringResource(R.string.campaign_install_guide_step2))
                    Text(stringResource(R.string.campaign_install_guide_step3))
                }
            },
            confirmButton = {
                Button(onClick = {
                    showHowToInstallDialog = false
                    viewModel.ensureJoined()
                    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                    openUrlSafely(context, targetUrl, campaign.packageName)
                }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.campaign_install_guide_open_link_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHowToInstallDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Dialog: Không tải được
    if (showCannotDownloadDialog && campaign != null) {
        val groupUrl = stringResource(R.string.post_app_guide_group_url)
        AlertDialog(
            onDismissRequest = { showCannotDownloadDialog = false },
            title = { Text(stringResource(R.string.campaign_cant_download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.campaign_cant_download_common_causes), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.campaign_cant_download_cause1))
                    OutlinedButton(
                        onClick = {
                            openUrlSafely(context, groupUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.campaign_cant_download_group_btn), fontSize = 12.sp)
                    }
                    Text(stringResource(R.string.campaign_cant_download_cause2))
                    Text(stringResource(R.string.campaign_cant_download_cause3))
                    Text(stringResource(R.string.campaign_cant_download_cause4))
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCannotDownloadDialog = false
                    val url = campaign.testingUrl.ifBlank { campaign.playUrl }
                    openUrlSafely(context, url, campaign.packageName)
                }) {
                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.campaign_cant_download_web_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCannotDownloadDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Dialog: Lịch sử test
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text(stringResource(R.string.campaign_history_title)) },
            text = {
                val reports = uiState.myReports
                val checkinDates = uiState.myCheckinDates
                if (reports.isEmpty() && checkinDates.isEmpty()) {
                    Text(stringResource(R.string.campaign_history_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (reports.isNotEmpty()) {
                            items(reports) { rep ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.campaign_history_date_format, rep.createdAt?.take(10) ?: stringResource(R.string.today)),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            val badgeText = when {
                                                rep.isApproved -> stringResource(R.string.campaign_history_status_approved_format, rep.approvedReward)
                                                rep.isPending -> stringResource(R.string.campaign_history_status_pending_format, rep.remainingHoursUntilAutoAccept())
                                                else -> stringResource(R.string.campaign_history_status_rejected)
                                            }
                                            val badgeColor = when {
                                                rep.isApproved -> MaterialTheme.statusColors.success
                                                rep.isPending -> MaterialTheme.statusColors.warning
                                                else -> MaterialTheme.colorScheme.error
                                            }
                                            Text(text = badgeText, color = badgeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (rep.isMissionReport) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                    shape = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.mission_report_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                                if (!rep.missionTitle.isNullOrBlank()) {
                                                    Text(
                                                        text = rep.missionTitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                }
                                            }
                                        }
                                        if (rep.content.isNotBlank()) {
                                            Text(text = rep.content, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (rep.isRejected && !rep.rejectionReason.isNullOrBlank()) {
                                            val renderedReason = RejectionReasonRenderer.render(rep.rejectionReason, rep.reasonCode, localizer)
                                            Text(text = stringResource(R.string.campaign_history_reason_format, renderedReason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        if (rep.isScreenshotExpired) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Schedule,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.img_expired_storage_policy),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        } else if (!rep.screenshotUrl.isNullOrBlank()) {
                                            LaunchedEffect(rep.screenshotUrl) {
                                                ScreenshotManager.registerReportMetadata(rep)
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        previewImageUrl = rep.screenshotUrl
                                                        previewImageExpired = rep.isScreenshotExpired
                                                        previewReport = rep
                                                    }
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                    .padding(6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                AsyncImage(
                                                model = remember(context, rep) { ScreenshotRequestFactory.createRequest(context, rep, variant = ScreenshotVariant.THUMBNAIL) } ?: (rep.thumbnailUrl ?: rep.screenshotUrl),
                                                    contentDescription = stringResource(R.string.campaign_submitted_photo_cd),
                                                    modifier = Modifier
                                                        .size(width = 60.dp, height = 42.dp)
                                                        .clip(RoundedCornerShape(4.dp)),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Text(
                                                    text = stringResource(R.string.campaign_view_submitted_photo),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(checkinDates) { date ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.campaign_history_test_day_format, date), fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.campaign_history_reward_format, 2), color = MaterialTheme.statusColors.success, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // BottomSheet: Nộp báo cáo kiểm thử (+10 Credit tạm nhận khi gửi) - Rộng rãi, preview ảnh lớn, phát hiện trùng lặp
    if (showReportDialog && campaign != null) {
        SubmitReportBottomSheet(
            campaign = campaign,
            uiState = uiState,
            onDismiss = { if (!uiState.isActionLoading) showReportDialog = false },
            onPickScreenshot = {
                pickScreenshotLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onScreenshotSelected = { uri -> viewModel.onScreenshotSelected(uri, context) },
            onRatingChange = viewModel::onRatingChange,
            onCommentChange = viewModel::onCommentChange,
            onSubmit = {
                coroutineScope.launch {
                    val accepted = viewModel.startSubmit()
                    if (accepted) {
                        showReportDialog = false
                    }
                }
            },
            onPreviewImage = { url: String, expired: Boolean, rep: TesterReport? -> previewImageUrl = url; previewImageExpired = expired; previewReport = rep },
        )
    }

    if (adminPauseDialogState.isOpen) {
        val appName = uiState.campaign?.appName ?: stringResource(R.string.campaign_this_app)
        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: stringResource(R.string.campaign_owner_fallback)

        val options = listOf(
            com.ntt.crosstest.data.AdminPauseReason.MISSING_GOOGLE_GROUP.code to stringResource(R.string.campaign_admin_pause_opt_missing_google_group),
            com.ntt.crosstest.data.AdminPauseReason.POLICY_VIOLATION.code to stringResource(R.string.campaign_admin_pause_opt_policy_violation),
            com.ntt.crosstest.data.AdminPauseReason.CUSTOM.code to stringResource(R.string.campaign_admin_pause_opt_custom),
        )

        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) viewModel.dismissAdminPauseDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.PauseCircle,
                    contentDescription = null,
                    tint = MaterialTheme.statusColors.warning,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = { Text(stringResource(R.string.campaign_admin_pause_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!adminPauseDialogState.error.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = adminPauseDialogState.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.campaign_admin_pause_dialog_desc, appName, ownerName),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    options.forEach { (code, label) ->
                        val isSelected = adminPauseDialogState.reasonCode == code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    enabled = !uiState.isActionLoading,
                                    onClick = { viewModel.setAdminPauseReasonCode(code) },
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                enabled = !uiState.isActionLoading,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    when (adminPauseDialogState.reasonCode) {
                        com.ntt.crosstest.data.AdminPauseReason.MISSING_GOOGLE_GROUP.code -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.campaign_admin_pause_preview_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.campaign_admin_pause_preview_missing_google_group, appName),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        com.ntt.crosstest.data.AdminPauseReason.POLICY_VIOLATION.code -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.campaign_admin_pause_preview_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.campaign_admin_pause_preview_policy_violation),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        else -> {
                            OutlinedTextField(
                                value = adminPauseDialogState.customReason,
                                onValueChange = { if (it.length <= 200) viewModel.setAdminPauseCustomReason(it) },
                                label = { Text(stringResource(R.string.campaign_admin_pause_reason_label)) },
                                placeholder = { Text(stringResource(R.string.campaign_admin_pause_reason_hint)) },
                                minLines = 2,
                                maxLines = 4,
                                enabled = !uiState.isActionLoading,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminPauseCampaignWithReason(
                            reasonCode = adminPauseDialogState.reasonCode,
                            customReason = adminPauseDialogState.customReason.ifBlank { null },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB26A00)),
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.campaign_admin_pause_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissAdminPauseDialog() },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAdminResumeDialog) {
        val appName = uiState.campaign?.appName ?: stringResource(R.string.campaign_this_app)
        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: stringResource(R.string.campaign_owner_fallback)

        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showAdminResumeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = { Text(stringResource(R.string.campaign_admin_resume_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.campaign_admin_resume_dialog_desc, appName, ownerName),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminResumeCampaign()
                        showAdminResumeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.campaign_admin_resume_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAdminResumeDialog = false },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAdminDeleteDialog) {
        val appName = uiState.campaign?.appName ?: stringResource(R.string.campaign_this_app)
        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: stringResource(R.string.campaign_owner_fallback)

        val adminRemovedReason = stringResource(R.string.admin_removed_campaign_reason)
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showAdminDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.campaign_admin_remove))
            },
            text = {
                Text(
                    text = stringResource(R.string.campaign_admin_delete_confirm_format, appName, ownerName)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminDeleteCampaign(
                            reason = adminRemovedReason,
                            onDeleted = onBack,
                            onPendingRemoval = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.campaign_pending_removal_msg),
                                    Toast.LENGTH_LONG,
                                ).show()
                                onBack()
                            },
                        )
                        showAdminDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.campaign_admin_delete_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAdminDeleteDialog = false },
                    enabled = !uiState.isActionLoading,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAdminDeleteMissionDialog && pinnedMissionIdForDelete != null) {
        val targetMissionId = pinnedMissionIdForDelete!!
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showAdminDeleteMissionDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.mission_admin_delete_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mission_admin_delete_confirm_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = adminDeleteMissionReason,
                        onValueChange = { adminDeleteMissionReason = it },
                        label = { Text(stringResource(R.string.mission_admin_delete_prompt)) },
                        placeholder = { Text(stringResource(R.string.mission_admin_delete_reason_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminDeleteMission(missionId = targetMissionId, reason = adminDeleteMissionReason) {
                            showAdminDeleteMissionDialog = false
                            adminDeleteMissionReason = ""
                            pinnedMissionIdForDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !uiState.isActionLoading && adminDeleteMissionReason.trim().isNotBlank(),
                ) {
                    Text(stringResource(R.string.mission_admin_delete_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdminDeleteMissionDialog = false
                        adminDeleteMissionReason = ""
                        pinnedMissionIdForDelete = null
                    },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = campaign?.appName ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHistoryDialog = true }) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.campaign_history_cd))
                    }
                    if (uiState.isAdmin) {
                        IconButton(onClick = { showAdminDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.campaign_admin_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refresh(triggerSource = EgressTriggerSource.MANUAL) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.campaign_refresh_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading && campaign == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (campaign == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(uiState.error ?: stringResource(R.string.campaign_not_found))
            }
            return@Scaffold
        }

        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: "test app"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isBackgroundSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // Owner banner if viewed by app owner
            if (uiState.isOwner) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.campaign_owner_badge),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.campaign_owner_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onOwnerManageClick != null) {
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { onOwnerManageClick(campaign.id.orEmpty()) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(R.string.campaign_manage_btn))
                            }
                        }
                    }
                }
            } else if (uiState.isAdmin) {
                // Admin có thể test như tester nhưng vẫn mở được màn quản lý app.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Groups,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.campaign_manager_badge),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.campaign_manager_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onOwnerManageClick != null) {
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { onOwnerManageClick(campaign.id.orEmpty()) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(R.string.campaign_manage_btn))
                            }
                        }
                    }
                }
            } else if (uiState.todayTestedCount >= campaign.dailyTarget) {
                // Info banner for testers when target is reached
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.successContainer),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.statusColors.success.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.statusColors.success,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.campaign_target_reached_msg_format, campaign.dailyTarget),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.statusColors.onSuccessContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // -------------------------------------------------------------
            // CARD 1: Thông tin App & Thống kê 4 cột (Ảnh 2)
            // -------------------------------------------------------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header row: Icon + Name + Owner
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            iconUrl = campaign.safeIconUrl,
                            packageName = campaign.packageName,
                            appName = campaign.appName,
                            size = 58.dp,
                            shapeRadius = 14.dp,
                        )

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = campaign.appName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = campaign.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.campaign_owner_line_format, ownerName),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.campaign_owner_tested_stat_format,
                                        uiState.ownerStats?.testedCount ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.campaign_owner_installed_stat_format,
                                        uiState.ownerStats?.installedCount ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(Modifier.height(12.dp))

                    // 4 Columns Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatColumn(
                            icon = Icons.Filled.Check,
                            value = "${uiState.members.size}",
                            label = stringResource(R.string.campaign_badge_installed),
                        )
                        StatColumn(
                            icon = Icons.Filled.CalendarMonth,
                            value = "${uiState.todayTestedCount}/${campaign.dailyTarget}",
                            label = stringResource(R.string.campaign_tested_today),
                        )
                        StatColumn(
                            icon = Icons.AutoMirrored.Filled.Assignment,
                            value = "${uiState.reportsCount}",
                            label = stringResource(R.string.campaign_stat_reports),
                        )
                        StatColumn(
                            icon = Icons.Filled.Star,
                            value = if (uiState.avgRating > 0) "%.1f".format(uiState.avgRating) else "-",
                            label = stringResource(R.string.campaign_stat_rating),
                        )
                    }
                }
            }

            // -------------------------------------------------------------
            // Đợt thưởng thêm cho tester (nếu có đợt active/closing)
            // -------------------------------------------------------------
            val bonusInfo = uiState.testerBonusInfo
            val myAwarded = bonusInfo?.myAwarded == true
            val myPending = bonusInfo?.myPendingEligible == true
            val isRoundActive = (bonusInfo?.status == "active" || (bonusInfo == null && campaign.hasActiveTesterBonus))
            val hasActiveBonusRound = (bonusInfo?.hasRound == true && bonusInfo.status == "active" && bonusInfo.remainingSlots > 0) || campaign.hasActiveTesterBonus
            val hasTerminalRound = bonusInfo != null && (bonusInfo.status == "closed" || bonusInfo.status == "exhausted")
            val showBonusCard = hasActiveBonusRound || myAwarded || myPending || hasTerminalRound

            if (showBonusCard) {
                val rewardCredits = bonusInfo?.rewardCredits?.takeIf { it > 0 } ?: (campaign.testerBonusCredits ?: 0)
                val remainingSlots = bonusInfo?.remainingSlots ?: (campaign.testerBonusRemainingSlots ?: 0)
                val totalSlots = bonusInfo?.maxRecipients?.takeIf { it > 0 } ?: ((campaign.testerBonusRemainingSlots ?: 0) + (campaign.testerBonusPaidCount ?: 0))
                val myAwardAmount = bonusInfo?.myAwardAmount?.takeIf { it > 0 } ?: rewardCredits

                val surface = MaterialTheme.colorScheme.surface
                val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
                val orangeAccent = if (isDark) Color(0xFFFFB74D) else Color(0xFFB54700)
                val badgeBg = if (myAwarded) MaterialTheme.statusColors.successContainer else if (isDark) Color(0xFF422100) else Color(0xFFFFECE0)
                val badgeFg = if (myAwarded) MaterialTheme.statusColors.onSuccessContainer else orangeAccent

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, orangeAccent.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CardGiftcard,
                                    contentDescription = null,
                                    tint = orangeAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(R.string.campaign_detail_bonus_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (myAwarded) MaterialTheme.statusColors.success.copy(alpha = 0.4f) else orangeAccent.copy(alpha = 0.5f),
                                ),
                            ) {
                                Text(
                                    text = when {
                                        myAwarded -> stringResource(R.string.campaign_detail_bonus_awarded, myAwardAmount)
                                        isRoundActive && remainingSlots > 0 -> "+$rewardCredits Credit"
                                        bonusInfo?.status == "closed" -> stringResource(R.string.tester_bonus_status_closed)
                                        bonusInfo?.status == "exhausted" -> stringResource(R.string.tester_bonus_status_exhausted)
                                        else -> "+$rewardCredits Credit"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeFg,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        when {
                            myAwarded -> {
                                Text(
                                    text = stringResource(R.string.campaign_detail_bonus_awarded, myAwardAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.statusColors.success,
                                )
                            }
                            myPending -> {
                                Text(
                                    text = stringResource(R.string.campaign_detail_bonus_pending),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.statusColors.warning,
                                )
                            }
                            bonusInfo?.status == "closed" -> {
                                Text(
                                    text = stringResource(R.string.tester_bonus_status_closed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            remainingSlots <= 0 || bonusInfo?.status == "exhausted" -> {
                                Text(
                                    text = stringResource(R.string.campaign_detail_bonus_exhausted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            isRoundActive && remainingSlots > 0 -> {
                                Text(
                                    text = stringResource(
                                        R.string.campaign_detail_bonus_slots_format,
                                        remainingSlots,
                                        totalSlots,
                                        rewardCredits,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            else -> {
                                Text(
                                    text = stringResource(R.string.tester_bonus_status_closed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if ((isRoundActive && remainingSlots > 0) || myPending) {
                            Text(
                                text = stringResource(R.string.campaign_detail_bonus_condition),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // CARD 2: Cài app vào máy (Kiểm tra trạng thái cài đặt)
            // -------------------------------------------------------------
            if (!isInstalledLocally) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.warningContainer),
                    border = BorderStroke(1.dp, MaterialTheme.statusColors.warning.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.statusColors.warning,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.campaign_step1_install_title),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.statusColors.onWarningContainer,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.statusColors.warning.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.campaign_badge_not_installed),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.statusColors.onWarningContainer,
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.campaign_install_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.statusColors.onWarningContainer.copy(alpha = 0.85f),
                        )

                        Button(
                            onClick = {
                                if (!uiState.isOwner) {
                                    viewModel.ensureJoined()
                                }
                                val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                                openUrlSafely(context, targetUrl, campaign.packageName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.campaign_open_closed_test_btn),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                // Đã cài đặt trên máy
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.successContainer),
                    border = BorderStroke(1.dp, MaterialTheme.statusColors.success.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.statusColors.success,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.campaign_badge_installed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.statusColors.onSuccessContainer,
                                )
                                Text(
                                    text = stringResource(R.string.campaign_installed_ready_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.statusColors.onSuccessContainer.copy(alpha = 0.85f),
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                                openUrlSafely(context, targetUrl, campaign.packageName)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.campaign_view_test_link),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.statusColors.success,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.statusColors.success,
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // Banner: trạng thái vòng đời (tạm dừng / đã hoàn thành kiểm thử)
            // -------------------------------------------------------------
            if (!campaign.isOpen) {
                val bannerBg = when {
                    campaign.isProduction -> MaterialTheme.statusColors.successContainer
                    campaign.isCompleted -> MaterialTheme.statusColors.infoContainer
                    else -> MaterialTheme.statusColors.warningContainer
                }
                val bannerFg = when {
                    campaign.isProduction -> MaterialTheme.statusColors.onSuccessContainer
                    campaign.isCompleted -> MaterialTheme.statusColors.onInfoContainer
                    else -> MaterialTheme.statusColors.onWarningContainer
                }
                val bannerTitle = when {
                    campaign.isProduction -> stringResource(R.string.campaign_badge_production)
                    campaign.isCompleted -> stringResource(R.string.campaign_badge_completed)
                    else -> stringResource(R.string.campaign_badge_paused)
                }
                val adminNoteSuffix = if (
                    campaign.isAdminPaused &&
                    (uiState.isOwner || uiState.isAdmin)
                ) {
                    val noteText = when (campaign.adminPauseReasonCode) {
                        com.ntt.crosstest.data.AdminPauseReason.MISSING_GOOGLE_GROUP.code ->
                            stringResource(R.string.campaign_admin_pause_reason_missing_google_group)
                        com.ntt.crosstest.data.AdminPauseReason.POLICY_VIOLATION.code ->
                            stringResource(R.string.campaign_admin_pause_reason_policy_violation)
                        else -> campaign.adminNote?.takeIf { it.isNotBlank() }
                    }
                    if (!noteText.isNullOrBlank()) {
                        "\n" + stringResource(R.string.oad_paused_admin_note_format, noteText)
                    } else {
                        ""
                    }
                } else {
                    ""
                }
                val bannerText = when {
                    campaign.isProduction -> stringResource(R.string.campaign_lifecycle_production_msg)
                    campaign.isCompleted -> stringResource(R.string.campaign_lifecycle_completed_msg)
                    campaign.isAdminPaused -> stringResource(R.string.campaign_lifecycle_admin_paused_msg) + adminNoteSuffix
                    else -> stringResource(R.string.campaign_lifecycle_paused_msg)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = bannerBg),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when {
                                campaign.isProduction -> Icons.Filled.RocketLaunch
                                campaign.isCompleted -> Icons.Filled.CheckCircle
                                else -> Icons.Filled.HourglassTop
                            },
                            contentDescription = null,
                            tint = bannerFg,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = bannerTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = bannerFg,
                            )
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // CARD 3: Khối thực hiện Test & Nộp báo cáo
            // -------------------------------------------------------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Khối Cập nhật phiên bản mới (+6 Credit) dành cho Tester
                    if (uiState.showAppUpdateBlock && isInstalledLocally) {
                        val rel = uiState.appUpdate!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (uiState.isAppUpdated) {
                                    Color(0xFF2E7D32).copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (uiState.isAppUpdated) Color(0xFF2E7D32).copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (uiState.isAppUpdated) Icons.Filled.CheckCircle else Icons.Filled.SystemUpdate,
                                            contentDescription = null,
                                            tint = if (uiState.isAppUpdated) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.tester_update_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (uiState.isAppUpdated) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.tester_update_reward_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (uiState.isAppUpdated) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                Text(
                                    text = "v${rel.versionName}: ${rel.releaseNotes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (uiState.isAppUpdated) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2E7D32).copy(alpha = 0.12f))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (rel.hasClaimed) {
                                                stringResource(R.string.tester_update_already_updated)
                                            } else if (uiState.isClaimingUpdateReward) {
                                                stringResource(R.string.tester_update_verifying)
                                            } else {
                                                stringResource(R.string.tester_update_already_updated)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32),
                                        )
                                        if (uiState.isClaimingUpdateReward) {
                                            Spacer(Modifier.width(8.dp))
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF2E7D32),
                                            )
                                        }
                                    }
                                }

                                if (uiState.updateClaimError != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = uiState.updateClaimError ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (uiState.isAppUpdated && !rel.hasClaimed) {
                                                TextButton(
                                                    onClick = {
                                                        uiState.installedVersionCode?.let { code ->
                                                            viewModel.claimAppUpdateReward(rel.id, code)
                                                        } ?: viewModel.checkInstalledAppVersion(context)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.campaign_submit_retry_btn),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            val pkg = campaign.packageName
                                            val marketUri = Uri.parse("market://details?id=$pkg")
                                            val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                                                    setPackage("com.android.vending")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                try {
                                                    val browserIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(browserIntent)
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.SystemUpdate,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.tester_update_btn),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Blue pill button: Test app
                    Button(
                        onClick = {
                            launchOrInstallCampaignApp(
                                context = context,
                                campaign = campaign,
                                isOwner = uiState.isOwner,
                                viewModel = viewModel,
                            )
                        },
                        enabled = campaign.isOpen,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE8F1FD),
                            contentColor = Color(0xFF1E3A8A)
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF1E3A8A)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (uiState.isOwner) R.string.campaign_open_self_test_btn else if (isInstalledLocally) R.string.campaign_open_test_btn else R.string.campaign_download_install_btn),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }

                    if (!uiState.isOwner && isInstalledLocally) {
                        Text(
                            text = stringResource(R.string.campaign_tip_open_directly),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    if (uiState.isOwner) {
                        Text(
                            text = stringResource(R.string.campaign_owner_self_test_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        if (onOwnerManageClick != null) {
                            Button(
                                onClick = { onOwnerManageClick(campaign.id.orEmpty()) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73)),
                            ) {
                                Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.campaign_open_manage_btn), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // Personal progress toward 14 days
                        val myDays = uiState.myCheckinDates.size
                        val targetDays = campaign.targetDays.coerceAtLeast(14)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.campaign_your_progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (myDays >= targetDays) stringResource(R.string.campaign_progress_met_format, myDays, targetDays) else stringResource(R.string.campaign_progress_days_format, myDays, targetDays),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (myDays >= targetDays) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            )
                        }

                        // Check status line
                        val todayReport = uiState.myTodayReport
                        val isApprovedToday = (uiState.checkedInToday || todayReport?.isApproved == true) &&
                            todayReport?.isPending != true && todayReport?.isRejected != true
                        val isPendingToday = todayReport?.isPending == true
                        val isRejectedToday = todayReport?.isRejected == true

                        val statusIcon = if (isApprovedToday) Icons.Filled.Check
                            else if (isPendingToday) Icons.Filled.Schedule
                            else if (isRejectedToday) Icons.Filled.Close
                            else Icons.Filled.Check

                        val statusColor = if (isApprovedToday) Color(0xFF2E7D32)
                            else if (isPendingToday) Color(0xFFE65100)
                            else if (isRejectedToday) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant

                        val statusText = if (isApprovedToday) {
                            stringResource(R.string.campaign_today_approved_format, todayReport?.approvedReward ?: 2L)
                        } else if (isPendingToday) {
                            stringResource(R.string.campaign_today_pending_format, todayReport?.remainingHoursUntilAutoAccept() ?: 8)
                        } else if (isRejectedToday) {
                            val reason = todayReport?.let { RejectionReasonRenderer.render(it.rejectionReason, it.reasonCode, localizer) }?.ifBlank { null }
                            if (reason != null) stringResource(R.string.campaign_today_rejected_reason_format, reason)
                            else stringResource(R.string.campaign_today_rejected)
                        } else {
                            stringResource(R.string.campaign_today_ready)
                        }

                        if (todayReport?.isMissionReport == true || (uiState.isMissionReport && !isApprovedToday && !isPendingToday && !isRejectedToday)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.mission_report_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                val mTitle = todayReport?.missionTitle ?: uiState.activeMission?.title
                                if (!mTitle.isNullOrBlank()) {
                                    Text(
                                        text = mTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isApprovedToday || isPendingToday || isRejectedToday) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isApprovedToday || isPendingToday || isRejectedToday) statusColor else MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        // Thumbnail preview of screenshot submitted today (if any)
                        if (!todayReport?.screenshotUrl.isNullOrBlank()) {
                            LaunchedEffect(todayReport.screenshotUrl) {
                                ScreenshotManager.registerReportMetadata(todayReport)
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        previewImageUrl = todayReport.screenshotUrl
                                        previewImageExpired = todayReport.isScreenshotExpired
                                        previewReport = todayReport
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    AsyncImage(
                                    model = remember(context, todayReport) { ScreenshotRequestFactory.createRequest(context, todayReport, variant = ScreenshotVariant.THUMBNAIL) } ?: (todayReport.thumbnailUrl ?: todayReport.screenshotUrl),
                                        contentDescription = stringResource(R.string.campaign_your_photo_cd),
                                        modifier = Modifier
                                            .size(width = 72.dp, height = 50.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.campaign_today_photo_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = stringResource(R.string.campaign_tap_to_zoom_long),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        // Primary Action Button
                        val canSubmitNow = campaign.isOpen
                        val buttonText = if (!canSubmitNow) {
                            when {
                                campaign.isProduction -> stringResource(R.string.campaign_overlay_production)
                                campaign.isCompleted -> stringResource(R.string.campaign_badge_completed)
                                else -> stringResource(R.string.campaign_overlay_paused)
                            }
                        } else if (isApprovedToday) {
                            stringResource(R.string.campaign_action_approved)
                        } else if (isPendingToday) {
                            stringResource(R.string.campaign_action_pending)
                        } else if (isRejectedToday) {
                            stringResource(R.string.campaign_action_rejected)
                        } else if (uiState.isMissionReport) {
                            stringResource(R.string.mission_report_title)
                        } else {
                            stringResource(R.string.campaign_action_submit)
                        }

                        val actionIcon = when {
                            !canSubmitNow -> Icons.Filled.Close
                            isApprovedToday -> Icons.Filled.CheckCircle
                            isPendingToday -> Icons.Filled.HourglassTop
                            isRejectedToday -> Icons.Filled.Replay
                            else -> Icons.Filled.CameraAlt
                        }

                        Button(
                            onClick = {
                                val (canSubmit, errorMsg) = viewModel.validateCanSubmit()
                                if (!canSubmit && errorMsg != null) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    viewModel.prefetchNextCampaign()
                                    showReportDialog = true
                                }
                            },
                            enabled = canSubmitNow,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRejectedToday) MaterialTheme.colorScheme.error else Color(0xFF2C4A73)
                            ),
                        ) {
                            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = buttonText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    // Footer action links: ↩ Chưa cài   ⚠ Không tải được
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showHowToInstallDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.campaign_badge_not_installed), style = MaterialTheme.typography.labelMedium)
                        }

                        TextButton(onClick = { showCannotDownloadDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFFD32F2F),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.campaign_cant_download_short), style = MaterialTheme.typography.labelMedium, color = Color(0xFFD32F2F))
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // CARD 4: Ghi chú từ chủ app (Ảnh 2)
            // -------------------------------------------------------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.campaign_owner_note_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        IconButton(
                            enabled = campaign.description.isNotBlank(),
                            onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("App note", campaign.description))
                                Toast.makeText(context, resources.getString(R.string.campaign_note_copied_toast), Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.campaign_copy_note_cd),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (campaign.description.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = campaign.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    listOf(
                        R.string.label_test_username to campaign.safeTestUsername,
                        R.string.label_test_password to campaign.safeTestPassword,
                    ).forEach { (labelRes, value) ->
                        if (value.isNotBlank()) {
                            val label = stringResource(labelRes)
                            CopyableTestCredential(
                                label = label,
                                value = value,
                                onCopy = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.campaign_field_copied_format, label),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }
            }

            if (uiState.isAdmin) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👑", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.campaign_admin_area_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = stringResource(R.string.campaign_admin_area_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (campaign.isOpen) {
                            Button(
                                onClick = { viewModel.openAdminPauseDialog() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB26A00),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isActionLoading,
                            ) {
                                Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.campaign_admin_pause_btn), fontWeight = FontWeight.Bold)
                            }
                        } else if (campaign.isPaused) {
                            Button(
                                onClick = { showAdminResumeDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E7D32),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isActionLoading,
                            ) {
                                Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.campaign_admin_resume_btn), fontWeight = FontWeight.Bold)
                            }
                        }
                        val adminMission = uiState.adminManageableMission ?: uiState.activeMission
                        if (adminMission != null) {
                            Button(
                                onClick = {
                                    pinnedMissionIdForDelete = adminMission.id
                                    showAdminDeleteMissionDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isActionLoading,
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.mission_admin_delete_btn), fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = { showAdminDeleteDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !uiState.isActionLoading,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.campaign_admin_delete_btn), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
    }

    CreditRewardOverlay(
        reward = uiState.reward,
        onDismiss = viewModel::clearReward,
        compact = true,
    )

    if (previewImageUrl != null) {
        FullscreenImageViewer(
            imageUrl = previewImageUrl,
            onDismiss = {
                previewImageUrl = null
                previewImageExpired = false
                previewReport = null
            },
            title = stringResource(R.string.img_screenshot_title),
            subtitle = campaign?.appName,
            isExpired = previewImageExpired,
            report = previewReport,
        )
    }
}

@Composable
private fun StatColumn(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun launchOrInstallCampaignApp(
    context: Context,
    campaign: Campaign,
    isOwner: Boolean,
    viewModel: CampaignDetailViewModel,
) {
    if (!isOwner) {
        viewModel.ensureJoined()
    }
    val pkg = campaign.packageName
    viewModel.recordTestStart()

    // 1. Phương pháp 1: Mở app trực tiếp (nhờ queries launcher, tìm thấy và mở ngay app đã cài)
    val launchIntent = try {
        context.packageManager.getLaunchIntentForPackage(pkg)
    } catch (_: Exception) { null }

    if (launchIntent != null) {
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return
        } catch (_: Exception) {}
    }

    // 2. Phương pháp 2: Nếu Phương pháp 1 không mở được -> Mở thẳng app Google Play Store
    try {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(marketIntent)
        return
    } catch (_: Exception) {}

    // 3. Dự phòng: Dẫn thẳng link Closed Test / Testing track để Opt-in & tải
    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
    openUrlSafely(context, targetUrl, pkg)
}

private fun openUrlSafely(context: Context, rawUrl: String, packageName: String? = null) {
    val cleanUrl = rawUrl.trim()
    val finalUrl = when {
        cleanUrl.startsWith("http://", ignoreCase = true) || cleanUrl.startsWith("https://", ignoreCase = true) || cleanUrl.startsWith("market://", ignoreCase = true) -> cleanUrl
        cleanUrl.isNotBlank() -> "https://$cleanUrl"
        !packageName.isNullOrBlank() -> "https://play.google.com/store/apps/details?id=$packageName"
        else -> ""
    }
    if (finalUrl.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        if (!packageName.isNullOrBlank()) {
            try {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(playIntent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, SettingsRepository.wrap(context).getString(R.string.campaign_cant_open_link_toast), Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubmitReportBottomSheet(
    campaign: Campaign,
    uiState: CampaignDetailUiState,
    onDismiss: () -> Unit,
    onPickScreenshot: () -> Unit,
    onScreenshotSelected: (Uri?) -> Unit,
    onRatingChange: (Int) -> Unit,
    onCommentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPreviewImage: (String, Boolean, TesterReport?) -> Unit,
) {
    val context = LocalContext.current
    val todayReport = uiState.myTodayReport
    val isPendingToday = todayReport?.isPending == true
    val isRejectedToday = todayReport?.isRejected == true
    val alreadyRewarded = (uiState.checkedInToday || todayReport?.isApproved == true) && !isRejectedToday

    // Khi báo cáo hôm nay bị từ chối, ảnh bị từ chối chính là mốc so sánh bắt buộc.
    val selectorResult = remember(uiState.myReports, uiState.myTodayReport) {
        val today = uiState.myTodayReport
        if (today != null) {
            ReportComparisonSelector.selectComparisonTarget(
                currentReport = today,
                availableReports = uiState.myReports,
                isHistoryPartial = false,
                rejectedReportOverride = today.takeIf { it.isRejected && !it.screenshotUrl.isNullOrBlank() }
            )
        } else {
            null
        }
    }
    val previousReport = selectorResult?.targetReport

    var isCheckingDuplicate by remember { mutableStateOf(false) }
    var comparisonResult by remember { mutableStateOf<ImageHashHelper.ComparisonResult?>(null) }
    val duplicateWarning = comparisonResult?.takeIf { it.isSuspectDuplicate }?.let {
        stringResource(R.string.campaign_duplicate_warning_format, it.similarity)
    }
    var showComparisonDialog by remember { mutableStateOf(false) }

    LaunchedEffect(
        uiState.selectedScreenshotUri,
        uiState.preparedScreenshot?.imageHash,
        uiState.isPreparingScreenshot,
        uiState.myTodayReport?.status,
        uiState.myTodayReport?.screenshotUrl,
        uiState.myTodayReport?.imageHash,
        previousReport?.screenshotUrl,
        previousReport?.imageHash,
    ) {
        val currentUri = uiState.selectedScreenshotUri
        val prepared = uiState.preparedScreenshot
        val todayUrl = uiState.myTodayReport?.screenshotUrl
        val previousUrl = previousReport?.screenshotUrl
        if (uiState.isPreparingScreenshot) {
            // Ảnh đang được nén một lần trong ViewModel; chưa có hash để so.
            comparisonResult = null
            isCheckingDuplicate = true
            return@LaunchedEffect
        }
        if (isRejectedToday && currentUri == null) {
            // Chưa chọn ảnh mới: chỉ hiển thị nhắc bắt buộc chụp ảnh mới, không báo "trùng".
            comparisonResult = null
            isCheckingDuplicate = false
            return@LaunchedEffect
        }
        if (previousUrl.isNullOrBlank() || (currentUri == null && todayUrl.isNullOrBlank())) {
            comparisonResult = null
            isCheckingDuplicate = false
            return@LaunchedEffect
        }
        isCheckingDuplicate = true
        // Hash được tính trong cùng pipeline nén với lúc upload (ViewModels
        // chuẩn bị ảnh một lần), nên không nén lại ảnh ở đây.
        val newHash = when {
            prepared != null -> prepared.imageHash
            currentUri != null -> null // ảnh đang lỗi/ chưa sẵn sàng
            else -> {
                // Ưu tiên hash đã lưu trong DB — chỉ tải ảnh hôm nay khi hash trống.
                uiState.myTodayReport?.imageHash?.takeIf { it.isNotBlank() }
                    ?: ImageHashHelper.computeDHashFromUrl(context, todayUrl!!, uiState.myTodayReport)
            }
        }
        // Ưu tiên hash đã lưu trong DB — chỉ tải ảnh cũ khi hash trống và chưa hết hạn 30 ngày.
        val prevHash = previousReport.imageHash?.takeIf { it.isNotBlank() }
            ?: if (previousReport?.isScreenshotExpired != true) ImageHashHelper.computeDHashFromUrl(context, previousUrl, previousReport) else null
        if (newHash != null && prevHash != null) {
            val comp = ImageHashHelper.compare(newHash, prevHash, context)
            comparisonResult = comp
        } else {
            comparisonResult = null
        }
        isCheckingDuplicate = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            uiState.isMissionReport -> stringResource(R.string.mission_report_title)
                            alreadyRewarded -> stringResource(R.string.campaign_send_extra_btn)
                            isPendingToday -> stringResource(R.string.campaign_sheet_update_title)
                            else -> stringResource(R.string.campaign_sheet_submit_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = campaign.appName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onDismiss, enabled = !uiState.isActionLoading) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }

            if (uiState.isMissionReport && uiState.activeMission != null) {
                val mission = uiState.activeMission
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🎯", fontSize = 16.sp)
                            Text(
                                text = mission.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (mission.rewardCredits > 0) {
                            Text(
                                text = stringResource(R.string.mission_report_reward_format, mission.rewardCredits),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        mission.endsAt?.let { deadline ->
                            Text(
                                text = stringResource(R.string.mission_report_deadline_format, formatIsoUtcToLocal(deadline)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Reward Info Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (alreadyRewarded) MaterialTheme.statusColors.infoContainer else MaterialTheme.statusColors.successContainer
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (alreadyRewarded) MaterialTheme.statusColors.info.copy(alpha = 0.4f) else MaterialTheme.statusColors.success.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (alreadyRewarded) "💡" else "🎁",
                        fontSize = 20.sp
                    )
                    Text(
                        text = when {
                            alreadyRewarded ->
                                stringResource(R.string.campaign_sheet_note_extra)
                            isPendingToday ->
                                stringResource(R.string.campaign_sheet_note_update)
                            else ->
                                stringResource(R.string.campaign_sheet_note_submit)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (alreadyRewarded) MaterialTheme.statusColors.onInfoContainer else MaterialTheme.statusColors.onSuccessContainer,
                    )
                }
            }

            // Screenshot Preview Area (Hero Size!)
            Text(
                text = stringResource(R.string.campaign_sheet_photo_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (alreadyRewarded) {
                Text(
                    text = stringResource(R.string.campaign_approved_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (isRejectedToday) {
                Text(
                    text = stringResource(R.string.campaign_resubmit_new_photo_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (uiState.selectedScreenshotUri != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val previousImageUrl = previousReport?.screenshotUrl
                        if (previousImageUrl != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                 ComparisonPhotoCard(
                                     model = uiState.selectedScreenshotUri,
                                     label = stringResource(R.string.campaign_photo_new_label),
                                     modifier = Modifier.weight(1f),
                                     onClick = { onPreviewImage(uiState.selectedScreenshotUri.toString(), false, null) },
                                 )
                                 val prevCardLabel = if (selectorResult?.relation == ReportComparisonSelector.TargetRelation.REJECTED_SAME_DAY) {
                                     stringResource(R.string.report_selector_rejected_same_day)
                                 } else {
                                     stringResource(R.string.campaign_photo_previous_label, previousReport?.createdAt?.let { ReportComparisonSelector.formatIsoUtcToVnDate(it) }.orEmpty())
                                 }
                                 ComparisonPhotoCard(
                                     model = previousImageUrl,
                                     label = prevCardLabel,
                                     modifier = Modifier.weight(1f),
                                     report = previousReport,
                                     onClick = { onPreviewImage(previousImageUrl, previousReport?.isScreenshotExpired ?: false, previousReport) },
                                 )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.05f))
                                    .clickable { onPreviewImage(uiState.selectedScreenshotUri.toString(), false, null) },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = uiState.selectedScreenshotUri,
                                    contentDescription = stringResource(R.string.campaign_photo_selected_cd),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                // Overlay zoom badge
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xB3000000)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.campaign_tap_to_zoom), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        // Duplicate warning if detected
                        if (isCheckingDuplicate) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.campaign_comparing_photos), style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (duplicateWarning != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.warningContainer),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.statusColors.warning.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = duplicateWarning!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.statusColors.onWarningContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        // Compare with previous day's photo in detail (side-by-side / slider / blink)
                        if (previousReport?.screenshotUrl != null) {
                            OutlinedButton(
                                onClick = { showComparisonDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.campaign_compare_photos_btn))
                            }
                        }

                        if (!alreadyRewarded) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onPickScreenshot,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.campaign_change_photo_btn))
                                }
                                OutlinedButton(
                                    onClick = { onScreenshotSelected(null) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.campaign_remove_photo_btn))
                                }
                            }
                        }
                    }
                }
            } else if (!uiState.myTodayReport?.screenshotUrl.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val previousImageUrl = previousReport?.screenshotUrl
                        val todayImageUrl = uiState.myTodayReport?.screenshotUrl
                        if (previousImageUrl != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ComparisonPhotoCard(
                                    model = todayImageUrl,
                                    label = stringResource(R.string.campaign_today_photo_title),
                                    modifier = Modifier.weight(1f),
                                    report = uiState.myTodayReport,
                                    onClick = { todayImageUrl?.let { onPreviewImage(it, uiState.myTodayReport?.isScreenshotExpired ?: false, uiState.myTodayReport) } },
                                )
                                 val prevCardLabel = if (selectorResult?.relation == ReportComparisonSelector.TargetRelation.REJECTED_SAME_DAY) {
                                     stringResource(R.string.report_selector_rejected_same_day)
                                 } else {
                                     stringResource(R.string.campaign_photo_previous_label, previousReport?.createdAt?.let { ReportComparisonSelector.formatIsoUtcToVnDate(it) }.orEmpty())
                                 }
                                 ComparisonPhotoCard(
                                     model = previousImageUrl,
                                     label = prevCardLabel,
                                     modifier = Modifier.weight(1f),
                                     report = previousReport,
                                     onClick = { onPreviewImage(previousImageUrl, previousReport?.isScreenshotExpired ?: false, previousReport) },
                                 )
                            }
                            OutlinedButton(
                                onClick = { showComparisonDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.campaign_compare_photos_btn))
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.05f))
                                    .clickable { onPreviewImage(uiState.myTodayReport!!.screenshotUrl!!, uiState.myTodayReport?.isScreenshotExpired ?: false, uiState.myTodayReport) },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                model = remember(context, uiState.myTodayReport) { ScreenshotRequestFactory.createRequest(context, uiState.myTodayReport, variant = ScreenshotVariant.THUMBNAIL) } ?: (uiState.myTodayReport?.thumbnailUrl ?: uiState.myTodayReport!!.screenshotUrl),
                                    contentDescription = stringResource(R.string.campaign_submitted_photo_cd),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xB3000000)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.campaign_today_photo_zoom_label), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        if (!alreadyRewarded) {
                            OutlinedButton(
                                onClick = onPickScreenshot,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.campaign_choose_new_photo))
                            }
                        }
                    }
                }
            } else if (!alreadyRewarded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickScreenshot() },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AddPhotoAlternate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.campaign_tap_to_pick_photo),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.campaign_pick_photo_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Rating
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.campaign_rating_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val ratingLabel = when (uiState.myRating) {
                        1 -> stringResource(R.string.campaign_rating_1)
                        2 -> stringResource(R.string.campaign_rating_2)
                        3 -> stringResource(R.string.campaign_rating_3)
                        4 -> stringResource(R.string.campaign_rating_4)
                        else -> stringResource(R.string.campaign_rating_5)
                    }
                    Text(
                        text = ratingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AmberStar
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    (1..5).forEach { star ->
                        IconButton(
                            onClick = { onRatingChange(star) },
                            enabled = !alreadyRewarded,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = stringResource(R.string.campaign_rating_star_cd_format, star),
                                tint = if (star <= uiState.myRating) AmberStar
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }

            // Comment
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.campaign_comment_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = uiState.myComment,
                    onValueChange = onCommentChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.campaign_comment_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // Submit Button
            val isSending = uiState.submitStage == ReportSubmitManager.Stage.SENDING ||
                uiState.submitStage == ReportSubmitManager.Stage.RETRYING
            val missingNewScreenshot = isRejectedToday && uiState.preparedScreenshot == null
            val canSubmit = !uiState.isActionLoading &&
                !uiState.isPreparingScreenshot &&
                !isCheckingDuplicate &&
                !missingNewScreenshot &&
                !isSending && (
                uiState.myComment.isNotBlank() ||
                uiState.selectedScreenshotUri != null ||
                !uiState.myTodayReport?.screenshotUrl.isNullOrBlank()
            )

            if (!uiState.submitError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = uiState.submitError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
            ) {
                if (uiState.isActionLoading || isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.campaign_submitting), fontSize = 15.sp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            alreadyRewarded -> stringResource(R.string.campaign_send_extra_btn)
                            isPendingToday -> stringResource(R.string.campaign_send_update_btn)
                            else -> stringResource(R.string.campaign_send_submit_btn)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showComparisonDialog) {
        val prevDialogLabel = if (selectorResult?.relation == ReportComparisonSelector.TargetRelation.REJECTED_SAME_DAY) {
            stringResource(R.string.report_selector_rejected_same_day)
        } else {
            stringResource(R.string.img_previous_day)
        }
        TesterImageComparisonDialog(
            currentImage = uiState.preparedScreenshot?.file?.let { Uri.fromFile(it) }
                ?: uiState.selectedScreenshotUri ?: uiState.myTodayReport?.screenshotUrl,
            previousImage = previousReport?.screenshotUrl,
            currentDate = if (uiState.selectedScreenshotUri != null) null
                else uiState.myTodayReport?.createdAt?.let { formatIsoUtcToLocal(it) },
            previousDate = previousReport?.createdAt?.let { formatIsoUtcToLocal(it) },
            previousLabel = prevDialogLabel,
            comparisonResult = comparisonResult,
            isCalculatingHash = isCheckingDuplicate,
            currentReport = uiState.myTodayReport,
            previousReport = previousReport,
            onDismiss = { showComparisonDialog = false },
        )
    }
}

@Composable
private fun CopyableTestCredential(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.campaign_copy_field_format, label),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ComparisonPhotoCard(
    model: Any?,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    report: TesterReport? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageRequest = remember(context, model, report) {
        ScreenshotRequestFactory.createRequest(context, model, report)
    }
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.05f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageRequest ?: model,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xB3000000),
            ) {
                Icon(
                    imageVector = Icons.Filled.ZoomIn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(14.dp),
                )
            }
        }
    }
}
