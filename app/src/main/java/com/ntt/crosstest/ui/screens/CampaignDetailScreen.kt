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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.ntt.crosstest.util.formatIsoUtcToLocal
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.ui.theme.AmberStar
import com.ntt.crosstest.ui.viewmodels.CampaignDetailUiState
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignDetailScreen(
    campaignId: String,
    onBack: () -> Unit,
    onOwnerManageClick: ((String) -> Unit)? = null,
    onNavigateToCampaign: ((String) -> Unit)? = null,
    onAccountLocked: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val viewModel: CampaignDetailViewModel = viewModel(
        key = campaignId,
        factory = viewModelFactory {
            initializer {
                CampaignDetailViewModel(
                    campaignId,
                    app.campaignRepository,
                    app.reportSubmitManager,
                    app.authRepository.currentUserId(),
                    Localizer(app),
                    app,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    var showHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showNotInstalledDialog by rememberSaveable { mutableStateOf(false) }
    var showHowToInstallDialog by rememberSaveable { mutableStateOf(false) }
    var showCannotDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showAdminDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showAdminPauseDialog by rememberSaveable { mutableStateOf(false) }
    var showAdminResumeDialog by rememberSaveable { mutableStateOf(false) }
    var adminPauseReason by rememberSaveable { mutableStateOf("") }
    var previewImageUrl by rememberSaveable { mutableStateOf<String?>(null) }

    var isInstalledLocally by remember { mutableStateOf(false) }

    val pickScreenshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onScreenshotSelected(uri, context)
    }

    LifecycleResumeEffect(campaignId, uiState.campaign?.packageName) {
        val pkg = uiState.campaign?.packageName.orEmpty()
        if (pkg.isNotBlank()) {
            isInstalledLocally = try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) { false }
        }
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.isUserLocked) {
        if (uiState.isUserLocked) {
            onAccountLocked()
        }
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
                    tint = Color(0xFFF57F17),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.warning_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = activeWarning.message,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.warning_quota_format, uiState.weeklyWarningsCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC62828),
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
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
        AlertDialog(
            onDismissRequest = { viewModel.dismissNextAppPrompt() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.RocketLaunch,
                    contentDescription = null,
                    tint = Color(0xFF1E88E5),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(if (findingNext || nextCamp != null) R.string.campaign_next_dialog_title else R.string.campaign_done_dialog_title),
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFE65100))
                            Text(
                                text = stringResource(R.string.campaign_submit_retrying_format, uiState.submitAttempt),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE65100),
                            )
                        }
                        ReportSubmitManager.Stage.DONE -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.campaign_submit_sent_ok),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                            )
                        }
                        ReportSubmitManager.Stage.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                val nextId = nextCamp.id.orEmpty()
                                viewModel.dismissNextAppPrompt()
                                if (nextId.isNotBlank() && onNavigateToCampaign != null) {
                                    onNavigateToCampaign(nextId)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
                        ) {
                            Text(stringResource(R.string.campaign_finding_next_btn))
                        }
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.dismissNextAppPrompt() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            },
            dismissButton = {
                if (nextCamp != null) {
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
                                                rep.isApproved -> Color(0xFF2E7D32)
                                                rep.isPending -> Color(0xFFE65100)
                                                else -> MaterialTheme.colorScheme.error
                                            }
                                            Text(text = badgeText, color = badgeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (rep.content.isNotBlank()) {
                                            Text(text = rep.content, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (rep.isRejected && !rep.rejectionReason.isNullOrBlank()) {
                                            Text(text = stringResource(R.string.campaign_history_reason_format, rep.rejectionReason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        if (!rep.screenshotUrl.isNullOrBlank()) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { previewImageUrl = rep.screenshotUrl }
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                    .padding(6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                AsyncImage(
                                                    model = rep.screenshotUrl,
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
                                        Text(stringResource(R.string.campaign_history_reward_format, 2), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
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
                viewModel.startSubmit()
                showReportDialog = false
            },
            onPreviewImage = { url: String -> previewImageUrl = url },
        )
    }

    if (showAdminPauseDialog) {
        val appName = uiState.campaign?.appName ?: stringResource(R.string.campaign_this_app)
        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: stringResource(R.string.campaign_owner_fallback)

        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showAdminPauseDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.PauseCircle,
                    contentDescription = null,
                    tint = Color(0xFFB26A00),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = { Text(stringResource(R.string.campaign_admin_pause_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.campaign_admin_pause_dialog_desc, appName, ownerName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = adminPauseReason,
                        onValueChange = { if (it.length <= 200) adminPauseReason = it },
                        label = { Text(stringResource(R.string.campaign_admin_pause_reason_label)) },
                        placeholder = { Text(stringResource(R.string.campaign_admin_pause_reason_hint)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminPauseCampaign(adminPauseReason.ifBlank { null })
                        showAdminPauseDialog = false
                        adminPauseReason = ""
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
                    onClick = { showAdminPauseDialog = false },
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
                        viewModel.adminDeleteCampaign(reason = "Gỡ bỏ bởi Admin", onDeleted = onBack)
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
                    IconButton(onClick = { viewModel.refresh() }) {
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
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
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.campaign_target_reached_msg_format, campaign.dailyTarget),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20),
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
            // CARD 2: Cài app vào máy (Kiểm tra trạng thái cài đặt)
            // -------------------------------------------------------------
            if (!isInstalledLocally) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                    border = BorderStroke(1.dp, Color(0xFFFED7AA)),
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
                                    tint = Color(0xFFC2410C),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.campaign_step1_install_title),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF9A3412),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFFEDD5))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.campaign_badge_not_installed),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC2410C),
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.campaign_install_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7C2D12),
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
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
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.campaign_badge_installed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF14532D),
                                )
                                Text(
                                    text = stringResource(R.string.campaign_installed_ready_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF166534),
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
                                color = Color(0xFF15803D),
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF15803D),
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
                    campaign.isProduction -> Color(0xFFE8F5E9)
                    campaign.isCompleted -> Color(0xFFE3F2FD)
                    else -> Color(0xFFFFF8E1)
                }
                val bannerFg = when {
                    campaign.isProduction -> Color(0xFF2E7D32)
                    campaign.isCompleted -> Color(0xFF1565C0)
                    else -> Color(0xFFB26A00)
                }
                val bannerTitle = when {
                    campaign.isProduction -> stringResource(R.string.campaign_badge_production)
                    campaign.isCompleted -> stringResource(R.string.campaign_badge_completed)
                    else -> stringResource(R.string.campaign_badge_paused)
                }
                val adminNoteSuffix = if (
                    campaign.isAdminPaused &&
                    (uiState.isOwner || uiState.isAdmin) &&
                    !campaign.adminNote.isNullOrBlank()
                ) {
                    "\n" + stringResource(R.string.oad_paused_admin_note_format, campaign.adminNote.orEmpty())
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
                    // Blue pill button: Test app
                    Button(
                        onClick = {
                            if (!uiState.isOwner) {
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
                                    return@Button
                                } catch (_: Exception) {}
                            }

                            // 2. Phương pháp 2: Nếu Phương pháp 1 không mở được -> Mở thẳng app Google Play Store
                            try {
                                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                    setPackage("com.android.vending")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(marketIntent)
                                return@Button
                            } catch (_: Exception) {}

                            // 3. Dự phòng: Dẫn thẳng link Closed Test / Testing track để Opt-in & tải
                            val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                            openUrlSafely(context, targetUrl, pkg)
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
                            stringResource(R.string.campaign_today_approved_format, todayReport?.approvedReward ?: 2)
                        } else if (isPendingToday) {
                            stringResource(R.string.campaign_today_pending_format, todayReport?.remainingHoursUntilAutoAccept() ?: 8)
                        } else if (isRejectedToday) {
                            val reason = todayReport?.rejectionReason?.ifBlank { null }
                            if (reason != null) stringResource(R.string.campaign_today_rejected_reason_format, reason)
                            else stringResource(R.string.campaign_today_rejected)
                        } else {
                            stringResource(R.string.campaign_today_ready)
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
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { previewImageUrl = todayReport.screenshotUrl },
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
                                        model = todayReport.screenshotUrl,
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

                        IconButton(onClick = {
                            val textToCopy = listOfNotNull(
                                campaign.description.takeIf { it.isNotBlank() },
                                campaign.testingUrl.takeIf { it.isNotBlank() },
                                campaign.playUrl.takeIf { it.isNotBlank() },
                            ).joinToString("\n")
                            clipboard.setPrimaryClip(ClipData.newPlainText("App note", textToCopy))
                            Toast.makeText(context, context.getString(R.string.campaign_note_copied_toast), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.campaign_copy_note_cd),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (campaign.description.isNotBlank()) {
                        Text(
                            text = campaign.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    val downloadLink = campaign.testingUrl.ifBlank { campaign.playUrl }
                    if (downloadLink.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.campaign_note_download_link_format, downloadLink),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }

                    if (campaign.safeTestUsername.isNotBlank() || campaign.safeTestPassword.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.campaign_test_account_format, campaign.safeTestUsername, campaign.safeTestPassword),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                                onClick = { showAdminPauseDialog = true },
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
                        Button(
                            onClick = { showAdminDeleteDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
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
            onDismiss = { previewImageUrl = null },
            title = stringResource(R.string.img_screenshot_title),
            subtitle = campaign?.appName
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
    onPreviewImage: (String) -> Unit,
) {
    val context = LocalContext.current
    val todayReport = uiState.myTodayReport
    val isPendingToday = todayReport?.isPending == true
    val isRejectedToday = todayReport?.isRejected == true
    val alreadyRewarded = (uiState.checkedInToday || todayReport?.isApproved == true) && !isRejectedToday

    // Khi báo cáo hôm nay bị từ chối, ảnh bị từ chối chính là mốc so sánh bắt buộc.
    val previousReport = remember(uiState.myReports, uiState.myTodayReport) {
        val rejectedToday = uiState.myTodayReport?.takeIf {
            it.isRejected && !it.screenshotUrl.isNullOrBlank()
        }
        rejectedToday ?: uiState.myReports.firstOrNull {
            it.id != uiState.myTodayReport?.id && !it.screenshotUrl.isNullOrBlank()
        }
    }

    var duplicateWarning by remember { mutableStateOf<String?>(null) }
    var isCheckingDuplicate by remember { mutableStateOf(false) }
    var comparisonResult by remember { mutableStateOf<ImageHashHelper.ComparisonResult?>(null) }
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
            duplicateWarning = null
            comparisonResult = null
            isCheckingDuplicate = true
            return@LaunchedEffect
        }
        if (isRejectedToday && currentUri == null) {
            // Chưa chọn ảnh mới: chỉ hiển thị nhắc bắt buộc chụp ảnh mới, không báo "trùng".
            duplicateWarning = null
            comparisonResult = null
            isCheckingDuplicate = false
            return@LaunchedEffect
        }
        if (previousUrl.isNullOrBlank() || (currentUri == null && todayUrl.isNullOrBlank())) {
            duplicateWarning = null
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
                    ?: ImageHashHelper.computeDHashFromUrl(context, todayUrl!!)
            }
        }
        // Ưu tiên hash đã lưu trong DB — chỉ tải ảnh cũ khi hash trống.
        val prevHash = previousReport.imageHash?.takeIf { it.isNotBlank() }
            ?: ImageHashHelper.computeDHashFromUrl(context, previousUrl)
        if (newHash != null && prevHash != null) {
            val comp = ImageHashHelper.compare(newHash, prevHash, context)
            comparisonResult = comp
            duplicateWarning = if (comp.isSuspectDuplicate) {
                context.getString(R.string.campaign_duplicate_warning_format, comp.similarity)
            } else {
                null
            }
        } else {
            comparisonResult = null
            duplicateWarning = null
        }
        isCheckingDuplicate = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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

            // Reward Info Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = if (alreadyRewarded) Color(0xFFE8F5E9) else Color(0xFFF0FDF4)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (alreadyRewarded) Color(0xFFA5D6A7) else Color(0xFF86EFAC))
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
                        color = if (alreadyRewarded) Color(0xFF1B5E20) else Color(0xFF166534),
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
                                    onClick = { onPreviewImage(uiState.selectedScreenshotUri.toString()) },
                                )
                                ComparisonPhotoCard(
                                    model = previousImageUrl,
                                    label = stringResource(R.string.campaign_photo_previous_label, previousReport?.createdAt?.take(10).orEmpty()),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPreviewImage(previousImageUrl) },
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.05f))
                                    .clickable { onPreviewImage(uiState.selectedScreenshotUri.toString()) },
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
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFFFB74D))
                            ) {
                                Text(
                                    text = duplicateWarning!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE65100),
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
                                    onClick = { todayImageUrl?.let(onPreviewImage) },
                                )
                                ComparisonPhotoCard(
                                    model = previousImageUrl,
                                    label = stringResource(R.string.campaign_photo_previous_label, previousReport?.createdAt?.take(10).orEmpty()),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPreviewImage(previousImageUrl) },
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
                                    .clickable { onPreviewImage(uiState.myTodayReport!!.screenshotUrl!!) },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = uiState.myTodayReport!!.screenshotUrl,
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
            val missingNewScreenshot = isRejectedToday && uiState.preparedScreenshot == null
            val rejectedImageDuplicate = isRejectedToday && comparisonResult?.isBlockingDuplicate == true
            val canSubmit = !uiState.isActionLoading &&
                !uiState.isPreparingScreenshot &&
                !isCheckingDuplicate &&
                !missingNewScreenshot &&
                !rejectedImageDuplicate && (
                uiState.myComment.isNotBlank() ||
                uiState.selectedScreenshotUri != null ||
                !uiState.myTodayReport?.screenshotUrl.isNullOrBlank()
            )

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
            ) {
                if (uiState.isActionLoading) {
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
        TesterImageComparisonDialog(
            currentImage = uiState.selectedScreenshotUri ?: uiState.myTodayReport?.screenshotUrl,
            previousImage = previousReport?.screenshotUrl,
            currentDate = if (uiState.selectedScreenshotUri != null) null
                else uiState.myTodayReport?.createdAt?.let { formatIsoUtcToLocal(it) },
            previousDate = previousReport?.createdAt?.let { formatIsoUtcToLocal(it) },
            comparisonResult = comparisonResult,
            isCalculatingHash = isCheckingDuplicate,
            onDismiss = { showComparisonDialog = false },
        )
    }
}

@Composable
private fun ComparisonPhotoCard(
    model: Any?,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    onClick: () -> Unit,
) {
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
                model = model,
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
