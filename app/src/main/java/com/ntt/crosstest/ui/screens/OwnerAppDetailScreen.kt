package com.ntt.crosstest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.ui.components.FullscreenImageViewer
import com.ntt.crosstest.ui.components.ImageComparisonDialog
import com.ntt.crosstest.ui.theme.AmberStar
import com.ntt.crosstest.util.ImageHashHelper
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.TesterItem
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerAppDetailScreen(
    campaignId: String,
    onBack: () -> Unit,
    openReports: Boolean = false,
    onAccountLocked: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OwnerAppDetailViewModel = run {
        val app = LocalContext.current.applicationContext as TestCheoApp
        viewModel(
            key = "owner_app_$campaignId",
            factory = viewModelFactory {
                initializer {
                    OwnerAppDetailViewModel(
                        campaignId = campaignId,
                        repository = app.campaignRepository,
                        localizer = Localizer(app),
                    )
                }
            }
        )
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.isOwnerLocked) {
        if (uiState.isOwnerLocked) {
            onAccountLocked()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showTestersDialog by rememberSaveable { mutableStateOf(false) }
    var showReportsDialog by rememberSaveable { mutableStateOf(openReports) }
    var showPauseConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showTipDialog by rememberSaveable { mutableStateOf(false) }
    var showProductionDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showSelfTestNotInstalledDialog by rememberSaveable { mutableStateOf(false) }
    var showRenewSlotDialog by rememberSaveable { mutableStateOf(false) }

    val campaign = uiState.campaign

    // Mở dialog báo cáo: tải lại danh sách để không bị dữ liệu cũ (báo cáo đã duyệt/từ chối).
    LaunchedEffect(showReportsDialog) {
        if (showReportsDialog) viewModel.refresh(forceRefresh = true)
    }

    if (uiState.warningMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearWarning() },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.oad_warning_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = uiState.warningMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearWarning()
                        if (uiState.isOwnerLocked) {
                            onAccountLocked()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
                ) {
                    Text(stringResource(R.string.oad_understood))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = campaign?.appName ?: stringResource(R.string.oad_title_fallback),
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
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.oad_edit_cd))
                    }
                    IconButton(onClick = { showLinkDialog = true }) {
                        Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.oad_link_cd))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.oad_refresh_cd))
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading && campaign == null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (campaign == null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.oad_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = modifier
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
            // Error banner if any action or state failed
            uiState.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = viewModel::clearError,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Lifecycle banner: app tạm dừng / đã hoàn thành kiểm thử
            if (campaign.isFinalized) {
                val isProduction = campaign.isProduction
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isProduction) Icons.Filled.RocketLaunch else Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isProduction) stringResource(R.string.oad_production_title) else stringResource(R.string.oad_completed_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                            )
                            Text(
                                text = if (isProduction) {
                                    stringResource(R.string.oad_production_desc)
                                } else {
                                    stringResource(R.string.oad_completed_desc)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (campaign.isPaused) {
                val adminNote = campaign.adminNote?.takeIf { it.isNotBlank() }
                val adminNoteSuffix = adminNote?.let {
                    "\n" + stringResource(R.string.oad_paused_admin_note_format, it)
                }.orEmpty()
                val pausedDescription = when {
                    campaign.isAdminPaused -> stringResource(R.string.oad_paused_admin_desc) + adminNoteSuffix
                    campaign.isAutoPaused -> stringResource(R.string.oad_paused_auto_desc)
                    campaign.isSlotExpiredPause -> stringResource(R.string.oad_paused_slot_desc, campaign.slotNumber)
                    else -> stringResource(R.string.oad_paused_manual_desc)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PauseCircle,
                            contentDescription = null,
                            tint = Color(0xFFB26A00),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (campaign.isAdminPaused) {
                                    stringResource(R.string.oad_paused_admin_title)
                                } else {
                                    stringResource(R.string.oad_paused_title)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB26A00),
                            )
                            Text(
                                text = pausedDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Top Summary Card
            OwnerSummaryCard(
                campaign = campaign,
                ownerName = uiState.ownerProfile?.displayName ?: stringResource(R.string.oad_owner_you),
                ownerCredits = uiState.ownerProfile?.credits ?: 0,
                stats = uiState.stats,
                testers = uiState.testers,
                slotInfo = uiState.slotInfo,
                onRenewSlot = { showRenewSlotDialog = true },
            )

            // Menu Items Card (Matching Image 2)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Item 1: Người đang cài
                    OwnerActionItem(
                        icon = Icons.Filled.People,
                        title = stringResource(R.string.oad_installing_title),
                        subtitle = stringResource(R.string.oad_installing_subtitle_format, uiState.stats.installedTestersCount),
                        onClick = { showTestersDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Item 2: Báo cáo của tester
                    OwnerActionItem(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        title = stringResource(R.string.oad_reports_title),
                        subtitle = stringResource(R.string.oad_reports_subtitle_format, uiState.stats.pendingReportsCount, uiState.stats.totalReportsCount),
                        badgeNumber = uiState.stats.pendingReportsCount,
                        onClick = { showReportsDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    if (!campaign.isFinalized) {
                        // Item 3: Tạm dừng / Tiếp tục nhận tester.
                        // App bị Admin tạm dừng: chủ app không được tự mở lại (chỉ Admin thấy nút).
                        if (!campaign.isAdminPaused || uiState.isAdmin) {
                            OwnerActionItem(
                                icon = if (campaign.isPaused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                                title = if (campaign.isPaused) stringResource(R.string.oad_resume_title) else stringResource(R.string.oad_pause_title),
                                subtitle = when {
                                    campaign.isPaused && campaign.isAutoPaused -> stringResource(R.string.oad_resume_sub_auto)
                                    campaign.isPaused && campaign.isSlotExpiredPause -> stringResource(R.string.oad_resume_sub_slot_format, campaign.slotNumber)
                                    campaign.isPaused && campaign.isAdminPaused -> stringResource(R.string.oad_resume_sub_admin)
                                    campaign.isPaused -> stringResource(R.string.oad_resume_sub_manual)
                                    campaign.testingDaysRemaining != null -> stringResource(R.string.oad_pause_sub_days_format, campaign.testingDaysRemaining ?: 0)
                                    else -> stringResource(R.string.oad_pause_sub_plain)
                                },
                                onClick = { showPauseConfirmDialog = true },
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }

                        // Item 4: Boa cho người test - tạm ẩn chưa dùng đến theo yêu cầu

                        // Item 5: App đã lên production
                        OwnerActionItem(
                            icon = Icons.Filled.RocketLaunch,
                            title = stringResource(R.string.oad_production_item_title),
                            subtitle = stringResource(R.string.oad_production_item_sub),
                            onClick = { showProductionDialog = true },
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    // Item 6: Xóa app
                    OwnerActionItem(
                        icon = Icons.Filled.Delete,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.oad_delete_title),
                        titleColor = MaterialTheme.colorScheme.error,
                        subtitle = stringResource(R.string.oad_delete_sub),
                        onClick = { showDeleteConfirmDialog = true },
                    )
                }
            }

            // Bottom Section: Tự test app của bạn
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oad_self_test_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.oad_self_test_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val launchIntent = try {
                                context.packageManager.getLaunchIntentForPackage(campaign.packageName)
                            } catch (_: Exception) { null }
                            if (launchIntent != null) {
                                try {
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(launchIntent)
                                } catch (_: Exception) {
                                    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                                        .ifBlank { "https://play.google.com/store/apps/details?id=${campaign.packageName}" }
                                    if (targetUrl.isNotBlank()) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.getString(R.string.oad_open_link_failed), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                val pkg = campaign.packageName
                                try {
                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                        setPackage("com.android.vending")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(marketIntent)
                                } catch (_: Exception) {
                                    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                                        .ifBlank { "https://play.google.com/store/apps/details?id=$pkg" }
                                    if (targetUrl.isNotBlank()) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, context.getString(R.string.oad_open_link_failed), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.oad_open_test_app))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
        }
    }

    // Dialogs
    if (showPauseConfirmDialog) {
        val isPaused = campaign?.isPaused == true
        AlertDialog(
            onDismissRequest = { showPauseConfirmDialog = false },
            title = { Text(if (isPaused) stringResource(R.string.oad_pause_confirm_resume_q) else stringResource(R.string.oad_pause_confirm_pause_q)) },
            text = {
                Text(
                    if (isPaused) {
                        if (campaign.isAutoPaused) {
                            stringResource(R.string.oad_resume_dialog_auto)
                        } else {
                            stringResource(R.string.oad_resume_dialog_manual)
                        }
                    } else stringResource(R.string.oad_pause_dialog_desc)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPauseConfirmDialog = false
                        viewModel.togglePauseResume()
                    }
                ) {
                    Text(stringResource(R.string.oad_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPauseConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showProductionDialog) {
        AlertDialog(
            onDismissRequest = { showProductionDialog = false },
            title = { Text(stringResource(R.string.oad_production_dialog_title)) },
            text = {
                Text(stringResource(R.string.oad_production_dialog_desc))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showProductionDialog = false
                        viewModel.markProduction()
                    }
                ) {
                    Text(stringResource(R.string.oad_production_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showProductionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.oad_delete_dialog_title)) },
            text = {
                Text(stringResource(R.string.oad_delete_dialog_desc_format, campaign?.appName.orEmpty()))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCampaign(onDeleted = onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.oad_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showTipDialog && campaign != null) {
        TipSettingsDialog(
            currentTip = campaign.tipCredits,
            ownerCredits = uiState.ownerProfile?.credits ?: 0,
            onDismiss = { showTipDialog = false },
            onSave = { newTip ->
                showTipDialog = false
                viewModel.updateTip(newTip)
            }
        )
    }

    if (showEditDialog && campaign != null) {
        EditAppDialog(
            campaign = campaign,
            onDismiss = { showEditDialog = false },
            onSave = { name, desc, play, test, user, pass ->
                showEditDialog = false
                viewModel.updateDetails(name, desc, play, test, user, pass)
            }
        )
    }

    if (showLinkDialog && campaign != null) {
        ShareLinkDialog(
            campaign = campaign,
            onDismiss = { showLinkDialog = false },
        )
    }

    if (showTestersDialog) {
        TestersListDialog(
            testers = uiState.testers,
            onDismiss = { showTestersDialog = false },
        )
    }

    if (showReportsDialog) {
        TesterReportsDialog(
            reports = uiState.reports,
            isActionLoading = uiState.isActionLoading,
            onDismiss = { showReportsDialog = false },
            onApprove = { report, bonus ->
                viewModel.approveReport(report, bonus)
            },
            onReject = { report, reason ->
                viewModel.rejectReport(report, reason)
            }
        )
    }

    if (showRenewSlotDialog && campaign != null) {
        val cost = com.ntt.crosstest.data.SlotInfo.costForSlot(campaign.slotNumber)
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showRenewSlotDialog = false },
            title = { Text(stringResource(R.string.oad_renew_dialog_title_format, campaign.slotNumber)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.oad_renew_dialog_desc_format, 16, campaign.slotNumber, cost))
                    Text(
                        text = stringResource(R.string.oad_renew_dialog_balance_format, uiState.ownerProfile?.credits ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRenewSlotDialog = false
                        viewModel.renewCurrentSlot()
                    },
                    enabled = !uiState.isActionLoading && (uiState.ownerProfile?.credits ?: 0) >= cost
                ) {
                    Text(stringResource(R.string.oad_renew_btn_format, cost))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRenewSlotDialog = false },
                    enabled = !uiState.isActionLoading
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSelfTestNotInstalledDialog && campaign != null) {
        AlertDialog(
            onDismissRequest = { showSelfTestNotInstalledDialog = false },
            title = { Text(stringResource(R.string.oad_not_installed_title)) },
            text = {
                Text(stringResource(R.string.oad_not_installed_desc_format, campaign.appName, campaign.packageName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSelfTestNotInstalledDialog = false
                        val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                            .ifBlank { "https://play.google.com/store/apps/details?id=${campaign.packageName}" }
                        if (targetUrl.isNotBlank()) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.oad_open_link_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.oad_install_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelfTestNotInstalledDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun OwnerSummaryCard(
    campaign: Campaign,
    ownerName: String,
    ownerCredits: Int,
    stats: CampaignOwnerStats,
    testers: List<TesterItem> = emptyList(),
    slotInfo: com.ntt.crosstest.data.SlotInfo? = null,
    onRenewSlot: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    iconUrl = campaign.safeIconUrl,
                    packageName = campaign.packageName,
                    appName = campaign.appName,
                    size = 56.dp,
                    shapeRadius = 12.dp,
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
                    val slotLabel = if (campaign.slotNumber == 1) stringResource(R.string.oad_slot_permanent_label) else "Slot ${campaign.slotNumber}"
                    Text(
                        text = stringResource(R.string.oad_summary_line_format, ownerName, ownerCredits, slotLabel, campaign.dailyTarget),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (slotInfo != null && !slotInfo.isPermanent) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (slotInfo.isExpired) {
                                Text(
                                    text = stringResource(R.string.oad_slot_expired_label),
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.oad_renew_now),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onRenewSlot() }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.oad_slot_remaining_days_format, slotInfo.remainingDays),
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.oad_renew_more),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onRenewSlot() }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            // 4 Metrics Grid matching Image 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetricColumn(
                    icon = Icons.Filled.Check,
                    value = "${stats.installedTestersCount}",
                    label = stringResource(R.string.oad_metric_installed),
                )
                MetricColumn(
                    icon = Icons.Filled.CalendarMonth,
                    value = "${stats.testedTodayCount}/${campaign.dailyTarget}",
                    label = stringResource(R.string.oad_metric_today),
                )
                MetricColumn(
                    icon = Icons.Filled.Description,
                    value = "${stats.totalReportsCount}",
                    label = stringResource(R.string.oad_metric_reports),
                )
                MetricColumn(
                    icon = Icons.Filled.Star,
                    value = stats.averageRating?.let { "%.1f".format(it) } ?: "—",
                    label = stringResource(R.string.oad_metric_rating),
                )
            }

            // Closed Testing 14-day / 20-tester Target Progress
            val completed14Days = testers.count { it.checkinCount >= 14 }
            val targetTesters = campaign.targetTesters.coerceAtLeast(20)
            val maxDays = testers.maxOfOrNull { it.checkinCount } ?: 0
            val isTargetReached = completed14Days >= targetTesters

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isTargetReached) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.oad_ct_target_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (isTargetReached) stringResource(R.string.oad_ct_target_reached_format, completed14Days, targetTesters) else stringResource(R.string.oad_ct_target_progress_format, completed14Days, targetTesters, maxDays),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isTargetReached) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    )
                }
                if (isTargetReached) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E7D32))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.oad_ct_qualified),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
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
        )
    }
}

@Composable
private fun OwnerActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeNumber: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            if (badgeNumber != null && badgeNumber > 0) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD32F2F)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$badgeNumber",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TipSettingsDialog(
    currentTip: Int,
    ownerCredits: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var selectedTip by remember { mutableIntStateOf(currentTip) }
    val options = listOf(0, 1, 2, 3, 5, 10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.oad_tip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.oad_tip_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.oad_tip_balance_format, ownerCredits),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { tip ->
                        OutlinedButton(
                            onClick = { selectedTip = tip },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = if (selectedTip == tip) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(if (tip == 0) stringResource(R.string.oad_tip_off) else "+$tip")
                        }
                    }
                }
                if (selectedTip > 0) {
                    Text(
                        text = stringResource(R.string.oad_tip_cost_format, selectedTip),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ownerCredits >= selectedTip) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selectedTip) }) {
                Text(stringResource(R.string.oad_tip_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun EditAppDialog(
    campaign: Campaign,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit,
) {
    var appName by rememberSaveable { mutableStateOf(campaign.appName) }
    var description by rememberSaveable { mutableStateOf(campaign.description) }
    var playUrl by rememberSaveable { mutableStateOf(campaign.playUrl) }
    var testingUrl by rememberSaveable { mutableStateOf(campaign.testingUrl) }
    var testUser by rememberSaveable { mutableStateOf(campaign.safeTestUsername) }
    var testPass by rememberSaveable { mutableStateOf(campaign.safeTestPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.oad_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text(stringResource(R.string.oad_edit_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.oad_edit_guide_label)) },
                    placeholder = { Text(stringResource(R.string.oad_edit_guide_placeholder)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = playUrl,
                    onValueChange = { playUrl = it },
                    label = { Text(stringResource(R.string.oad_edit_play_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testingUrl,
                    onValueChange = { testingUrl = it },
                    label = { Text(stringResource(R.string.oad_edit_testing_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testUser,
                    onValueChange = { testUser = it },
                    label = { Text(stringResource(R.string.oad_edit_user_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testPass,
                    onValueChange = { testPass = it },
                    label = { Text(stringResource(R.string.oad_edit_pass_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(appName, description, playUrl, testingUrl, testUser, testPass)
            }) {
                Text(stringResource(R.string.oad_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ShareLinkDialog(
    campaign: Campaign,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.oad_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (campaign.testingUrl.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.oad_share_testing_label), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(campaign.testingUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Testing URL", campaign.testingUrl))
                                    Toast.makeText(context, context.getString(R.string.oad_copied_testing), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.oad_copy))
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(campaign.testingUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.oad_open))
                            }
                        }
                    }
                }

                if (campaign.playUrl.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.oad_share_play_label), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(campaign.playUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Play URL", campaign.playUrl))
                                    Toast.makeText(context, context.getString(R.string.oad_copied_play), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.oad_copy))
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(campaign.playUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.oad_open))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun TestersListDialog(
    testers: List<TesterItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.oad_testers_dialog_title_format, testers.size)) },
        text = {
            if (testers.isEmpty()) {
                Text(
                    text = stringResource(R.string.oad_no_testers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(testers, key = { index, item -> "t_${item.member.userId}_$index" }) { _, item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = (item.profile?.displayName?.take(1) ?: "T").uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.profile?.displayName ?: "Tester",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(R.string.oad_checkin_days_format, item.checkinCount) +
                                                (item.lastCheckinDate?.let { stringResource(R.string.oad_checkin_last_format, it) } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.checkinCount >= 14) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFE8F5E9))
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.oad_enough_14),
                                            color = Color(0xFF2E7D32),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.oad_remaining_14_format, 14 - item.checkinCount),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TesterReportsDialog(
    reports: List<TesterReport>,
    isActionLoading: Boolean,
    onDismiss: () -> Unit,
    onApprove: (TesterReport, Int) -> Unit,
    onReject: (TesterReport, String?) -> Unit,
) {
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var reportToReject by remember { mutableStateOf<TesterReport?>(null) }
    var reportToCompare by remember { mutableStateOf<Pair<TesterReport, TesterReport?>?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chờ duyệt, 1: Đã duyệt, 2: Đã từ chối, 3: Tất cả

    val pendingReports = remember(reports) { reports.filter { it.isPending } }
    val approvedReports = remember(reports) { reports.filter { it.isApproved } }
    val rejectedReports = remember(reports) { reports.filter { it.isRejected } }

    val displayedReports = remember(reports, selectedTab) {
        when (selectedTab) {
            0 -> pendingReports
            1 -> approvedReports
            2 -> rejectedReports
            else -> reports.sortedWith(
                compareByDescending<TesterReport> { it.isPending }
                    .thenByDescending { it.createdAt }
            )
        }
    }

    if (reportToReject != null) {
        RejectConfirmDialog(
            report = reportToReject!!,
            onDismiss = { reportToReject = null },
            onConfirm = { rep, reason ->
                onReject(rep, reason)
                reportToReject = null
            }
        )
    }

    if (reportToCompare != null) {
        ImageComparisonDialog(
            currentReport = reportToCompare!!.first,
            previousReport = reportToCompare!!.second,
            onDismiss = { reportToCompare = null },
            onApprove = {
                onApprove(reportToCompare!!.first, 0)
                reportToCompare = null
            },
            onReject = {
                reportToReject = reportToCompare!!.first
                reportToCompare = null
            }
        )
    }

    if (previewImageUrl != null) {
        FullscreenImageViewer(
            imageUrl = previewImageUrl,
            onDismiss = { previewImageUrl = null },
            title = stringResource(R.string.oad_screenshot_tester_title),
            subtitle = stringResource(R.string.img_zoom_hint)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.oad_reports_dialog_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.oad_reports_dialog_sub_format, pendingReports.size, reports.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Tab filter row
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = stringResource(R.string.oad_tab_pending_format, pendingReports.size),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = stringResource(R.string.oad_tab_approved_format, approvedReports.size),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                text = stringResource(R.string.oad_tab_rejected_format, rejectedReports.size),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = {
                            Text(
                                text = stringResource(R.string.oad_tab_all_format, reports.size),
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                // Reminder banner for Pending tab
                if (selectedTab == 0 && pendingReports.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⏱", fontSize = 16.sp)
                            Text(
                                text = stringResource(R.string.oad_review_reminder),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                if (displayedReports.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = when (selectedTab) {
                                    0 -> stringResource(R.string.oad_empty_pending)
                                    1 -> stringResource(R.string.oad_empty_approved)
                                    2 -> stringResource(R.string.oad_empty_rejected)
                                    else -> stringResource(R.string.oad_empty_all)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(displayedReports, key = { index, r -> "rep_${r.id}_$index" }) { _, report ->
                            // Find previous report from the same tester
                            val previousReport = remember(reports, report) {
                                reports.filter {
                                    it.userId == report.userId &&
                                    it.id != report.id &&
                                    !it.screenshotUrl.isNullOrBlank() &&
                                    (it.createdAt.orEmpty() < report.createdAt.orEmpty())
                                }.maxByOrNull { it.createdAt.orEmpty() }
                            }

                            ReportReviewCard(
                                report = report,
                                previousReport = previousReport,
                                isActionLoading = isActionLoading,
                                onPreviewImage = { url -> previewImageUrl = url },
                                onCompare = { reportToCompare = Pair(report, previousReport) },
                                onApprove = { onApprove(report, 0) },
                                onReject = { reportToReject = report }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportReviewCard(
    report: TesterReport,
    previousReport: TesterReport?,
    isActionLoading: Boolean,
    onPreviewImage: (String) -> Unit,
    onCompare: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val context = LocalContext.current
    var comparison by remember(report.screenshotUrl, previousReport?.screenshotUrl) {
        mutableStateOf<ImageHashHelper.ComparisonResult?>(null)
    }
    var isCheckingHash by remember { mutableStateOf(false) }

    LaunchedEffect(report.screenshotUrl, previousReport?.screenshotUrl, report.imageHash, previousReport?.imageHash) {
        val u1 = report.screenshotUrl
        val u2 = previousReport?.screenshotUrl
        if (!u1.isNullOrBlank() && !u2.isNullOrBlank()) {
            isCheckingHash = true
            // Ưu tiên image_hash đã lưu trong DB — chỉ tải + decode ảnh khi hash trống.
            val h1 = report.imageHash?.takeIf { it.isNotBlank() }
                ?: ImageHashHelper.computeDHashFromUrl(context, u1)
            val h2 = previousReport.imageHash?.takeIf { it.isNotBlank() }
                ?: ImageHashHelper.computeDHashFromUrl(context, u2)
            if (h1 != null && h2 != null) {
                comparison = ImageHashHelper.compare(h1, h2, context)
            }
            isCheckingHash = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(
            width = if (comparison?.isSuspectDuplicate == true) 1.5.dp else 1.dp,
            color = if (comparison?.isSuspectDuplicate == true) Color(0xFFEF5350) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Avatar, Tester Name, Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val testerDisplayName = report.resolvedTesterName
                    val initial = (testerDisplayName.take(1)).uppercase()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initial,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = testerDisplayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        report.createdAt?.let {
                            Text(
                                text = com.ntt.crosstest.util.formatIsoUtcToLocal(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Status Chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (report.status) {
                        "pending" -> Color(0xFFFFF3E0)
                        "approved" -> Color(0xFFE8F5E9)
                        else -> Color(0xFFFFEBEE)
                    }
                ) {
                    Text(
                        text = if (report.status == "pending") stringResource(R.string.oad_status_remaining_format, report.remainingHoursUntilAutoAccept())
                               else if (report.status == "approved") stringResource(R.string.oad_status_approved)
                               else stringResource(R.string.oad_status_rejected),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (report.status == "pending") Color(0xFFE65100)
                                else if (report.status == "approved") Color(0xFF2E7D32)
                                else Color(0xFFC62828),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Image Comparison Badge (Smart AI/Perceptual Hash Duplicate Indicator)
            if (isCheckingHash) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.oad_analyzing), style = MaterialTheme.typography.labelSmall)
                }
            } else if (comparison != null) {
                val comp = comparison!!
                val isDup = comp.isSuspectDuplicate
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDup) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                    ),
                    border = BorderStroke(1.dp, if (isDup) Color(0xFFEF9A9A) else Color(0xFFA5D6A7))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = if (isDup) "🔴" else "🟢", fontSize = 14.sp)
                            Column {
                                Text(
                                    text = if (isDup) stringResource(R.string.oad_dup_warning_format, comp.similarity) else stringResource(R.string.oad_new_feature_format, 100 - comp.similarity),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDup) Color(0xFFC62828) else Color(0xFF2E7D32)
                                )
                                Text(
                                    text = if (isDup) stringResource(R.string.oad_similar_to_format, previousReport?.createdAt?.take(10).orEmpty()) else stringResource(R.string.oad_differs_from_format, previousReport?.createdAt?.take(10).orEmpty()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TextButton(
                            onClick = onCompare,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.oad_compare_short), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (previousReport != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.oad_has_photo_format, previousReport.createdAt?.take(10).orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onCompare,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.oad_compare_prev), fontSize = 12.sp)
                    }
                }
            }

            // Spacious Screenshot Preview Card (height 200dp, no cropping!)
            if (!report.screenshotUrl.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPreviewImage(report.screenshotUrl) },
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.06f)),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = report.screenshotUrl,
                            contentDescription = stringResource(R.string.oad_screenshot_tester_title),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )

                        // Bottom overlay banner
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                            color = Color(0xB3000000)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.oad_tap_zoom), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                if (previousReport != null) {
                                    Text(
                                        text = stringResource(R.string.oad_compare_2days),
                                        color = Color(0xFF90CAF9),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { onCompare() }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Rating Stars
            report.rating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (star <= rating) AmberStar else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.oad_rating_out_of_5_format, rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Report Feedback / Comment
            if (report.content.isNotBlank()) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.oad_tester_comment),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = report.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Rejection reason if rejected
            if (report.isRejected && !report.rejectionReason.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.oad_reject_reason_format, report.rejectionReason.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Action Buttons for Pending Reports
            if (report.status == "pending") {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !isActionLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.oad_reject))
                    }
                    Button(
                        onClick = onApprove,
                        enabled = !isActionLoading,
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.img_approve_btn), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RejectConfirmDialog(
    report: TesterReport,
    onDismiss: () -> Unit,
    onConfirm: (TesterReport, String) -> Unit,
) {
    val context = LocalContext.current
    val predefinedReasons = listOf(
        stringResource(R.string.oad_reject_reason_1),
        stringResource(R.string.oad_reject_reason_2),
        stringResource(R.string.oad_reject_reason_3),
        stringResource(R.string.oad_reject_reason_4),
    )
    var selectedReasonIndex by remember { mutableStateOf(0) }
    var customReason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.oad_reject_dialog_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.oad_reject_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oad_reject_dialog_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(8.dp)
                    )
                }
                predefinedReasons.forEachIndexed { index, reason ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedReasonIndex = index }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selectedReasonIndex == index,
                            onClick = { selectedReasonIndex = index },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(text = reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selectedReasonIndex == predefinedReasons.lastIndex) {
                    OutlinedTextField(
                        value = customReason,
                        onValueChange = { customReason = it },
                        placeholder = { Text(stringResource(R.string.oad_reject_custom_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalReason = if (selectedReasonIndex == predefinedReasons.lastIndex) {
                        customReason.trim().ifBlank { context.getString(R.string.oad_reject_fallback_reason) }
                    } else {
                        predefinedReasons[selectedReasonIndex]
                    }
                    onConfirm(report, finalReason)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.oad_reject_confirm_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
