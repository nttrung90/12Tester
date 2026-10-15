package com.ntt.crosstest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.ntt.crosstest.data.TesterItem
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerAppDetailScreen(
    campaignId: String,
    onBack: () -> Unit,
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
    var showReportsDialog by rememberSaveable { mutableStateOf(false) }
    var showPauseConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showTipDialog by rememberSaveable { mutableStateOf(false) }
    var showProductionDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showSelfTestNotInstalledDialog by rememberSaveable { mutableStateOf(false) }
    var showRenewSlotDialog by rememberSaveable { mutableStateOf(false) }

    val campaign = uiState.campaign

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
                    text = "CẢNH BÁO DUYỆT BÁO CÁO ⚠️",
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
                    Text("Tôi đã hiểu")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = campaign?.appName ?: "Quản lý App",
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
                        Icon(Icons.Filled.Edit, contentDescription = "Chỉnh sửa")
                    }
                    IconButton(onClick = { showLinkDialog = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Liên kết")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Làm mới")
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
                    text = uiState.error ?: "Không tìm thấy thông tin ứng dụng.",
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
                                contentDescription = "Đóng",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Top Summary Card
            OwnerSummaryCard(
                campaign = campaign,
                ownerName = uiState.ownerProfile?.displayName ?: "Bạn",
                ownerCredits = uiState.ownerProfile?.credits ?: 100,
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
                        title = "Người đang cài",
                        subtitle = "${uiState.stats.installedTestersCount} người đang cài · xem từng người và báo cáo của họ",
                        onClick = { showTestersDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Item 2: Báo cáo của tester
                    OwnerActionItem(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        title = "Báo cáo của tester",
                        subtitle = "${uiState.stats.pendingReportsCount} chờ duyệt (tự chấp nhận sau 24h) · ${uiState.stats.totalReportsCount} báo cáo",
                        badgeNumber = uiState.stats.pendingReportsCount,
                        onClick = { showReportsDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Item 3: Tạm dừng nhận tester
                    OwnerActionItem(
                        icon = if (campaign.isPaused) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                        title = if (campaign.isPaused) "Tiếp tục nhận tester" else "Tạm dừng nhận tester",
                        subtitle = if (campaign.isPaused) "Mở lại để hiển thị app trên Trang chủ." else "App sẽ không hiển thị.",
                        onClick = { showPauseConfirmDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Item 4: Boa cho người test - tạm ẩn chưa dùng đến theo yêu cầu

                    // Item 5: App đã lên production
                    OwnerActionItem(
                        icon = Icons.Filled.RocketLaunch,
                        title = "App đã lên production 🎉",
                        subtitle = "Bấm khi app đã phát hành công khai trên Google Play — hiện chấm xanh cạnh tên app. Bot cũng tự phát hiện.",
                        onClick = { showProductionDialog = true },
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Item 6: Xóa app
                    OwnerActionItem(
                        icon = Icons.Filled.Delete,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = "Xóa app",
                        titleColor = MaterialTheme.colorScheme.error,
                        subtitle = "Xóa vĩnh viễn app này khỏi Test chéo.",
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
                        text = "Tự test app của bạn",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Bạn có thể cài và nộp báo cáo để tự kiểm tra luồng test. Báo cáo tự test không được điểm, không tính vào quota ngày, chuỗi ngày hay xếp hạng.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(campaign.packageName)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            } else {
                                showSelfTestNotInstalledDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Mở ứng dụng test")
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
            title = { Text(if (isPaused) "Tiếp tục nhận tester?" else "Tạm dừng nhận tester?") },
            text = {
                Text(
                    if (isPaused) "Chiến dịch sẽ hiển thị lại công khai để các tester khác có thể tham gia và cài đặt."
                    else "App sẽ tạm thời ẩn khỏi Trang chủ và tester mới sẽ không thể tham gia cho đến khi bạn mở lại."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPauseConfirmDialog = false
                        viewModel.togglePauseResume()
                    }
                ) {
                    Text("Đồng ý")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPauseConfirmDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showProductionDialog) {
        AlertDialog(
            onDismissRequest = { showProductionDialog = false },
            title = { Text("App đã lên Production 🎉") },
            text = {
                Text("Chúc mừng bạn! Xác nhận ứng dụng của bạn đã hoàn thành Closed Testing 14 ngày và đã được Google Play duyệt phát hành công khai?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showProductionDialog = false
                        viewModel.markProduction()
                    }
                ) {
                    Text("Xác nhận Production 🚀")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProductionDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Xác nhận xóa app?") },
            text = {
                Text("Hành động này sẽ xóa vĩnh viễn chiến dịch kiểm thử của ứng dụng \"${campaign?.appName}\" khỏi hệ thống Test Chéo và không thể hoàn tác.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCampaign(onDeleted = onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Xóa vĩnh viễn")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showTipDialog && campaign != null) {
        TipSettingsDialog(
            currentTip = campaign.tipCredits,
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
            title = { Text("Gia hạn Slot ${campaign.slotNumber}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gia hạn thêm 16 ngày sử dụng cho Slot ${campaign.slotNumber} với chi phí $cost Credit.")
                    Text(
                        text = "Số dư hiện tại: ${uiState.ownerProfile?.credits ?: 0} Credit",
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
                    Text("Gia hạn ($cost Credit)")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRenewSlotDialog = false },
                    enabled = !uiState.isActionLoading
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showSelfTestNotInstalledDialog && campaign != null) {
        AlertDialog(
            onDismissRequest = { showSelfTestNotInstalledDialog = false },
            title = { Text("Ứng dụng chưa được cài đặt") },
            text = {
                Text("Ứng dụng \"${campaign.appName}\" (${campaign.packageName}) chưa được cài trên thiết bị này. Bạn có muốn mở link để cài đặt không?")
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
                                Toast.makeText(context, "Không thể mở liên kết.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Tải cài đặt")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelfTestNotInstalledDialog = false }) {
                    Text("Đóng")
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
                    val slotLabel = if (campaign.slotNumber == 1) "Slot 1 (Vĩnh viễn)" else "Slot ${campaign.slotNumber}"
                    Text(
                        text = "Chủ app: $ownerName · $ownerCredits điểm · $slotLabel · Mục tiêu ${campaign.dailyTarget} test/ngày",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (slotInfo != null && !slotInfo.isPermanent) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (slotInfo.isExpired) {
                                Text(
                                    text = "⚠️ Slot đã hết hạn",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Gia hạn ngay",
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
                                    text = "⏱ Còn ${slotInfo.remainingDays} ngày",
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Gia hạn thêm",
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
                    label = "đã cài",
                )
                MetricColumn(
                    icon = Icons.Filled.CalendarMonth,
                    value = "${stats.testedTodayCount}/${campaign.dailyTarget}",
                    label = "test hôm nay",
                )
                MetricColumn(
                    icon = Icons.Filled.Description,
                    value = "${stats.totalReportsCount}",
                    label = "báo cáo",
                )
                MetricColumn(
                    icon = Icons.Filled.Star,
                    value = stats.averageRating?.let { "%.1f".format(it) } ?: "—",
                    label = "đánh giá",
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
                        text = "Mục tiêu Closed Testing Google Play",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (isTargetReached) "✓ Đã đủ $completed14Days/$targetTesters tester (14 ngày)" else "$completed14Days/$targetTesters tester đủ 14 ngày (Cao nhất: $maxDays ngày)",
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
                            text = "Đủ chuẩn duyệt Play 🚀",
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
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var selectedTip by remember { mutableIntStateOf(currentTip) }
    val options = listOf(0, 1, 2, 3, 5, 10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Boa cho người test 🎁") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Tặng thêm Credit cho mỗi tester khi họ check-in kiểm thử app của bạn. App sẽ được đẩy lên vị trí nổi bật tại Trang chủ để thu hút tester nhanh chóng.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            Text(if (tip == 0) "Tắt" else "+$tip")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selectedTip) }) {
                Text("Lưu thiết lập")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
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
        title = { Text("Chỉnh sửa thông tin app") },
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
                    label = { Text("Tên ứng dụng") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Hướng dẫn thêm cho Tester") },
                    placeholder = { Text("Nhập lưu ý, tính năng cần test hoặc ghi chú cho tester...") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = playUrl,
                    onValueChange = { playUrl = it },
                    label = { Text("Link Google Play") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testingUrl,
                    onValueChange = { testingUrl = it },
                    label = { Text("Link tham gia Closed Test") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testUser,
                    onValueChange = { testUser = it },
                    label = { Text("Tên đăng nhập test (nếu có)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = testPass,
                    onValueChange = { testPass = it },
                    label = { Text("Mật khẩu test (nếu có)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(appName, description, playUrl, testingUrl, testUser, testPass)
            }) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
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
        title = { Text("Liên kết ứng dụng 🔗") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (campaign.testingUrl.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Link tham gia Closed Test:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(campaign.testingUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Testing URL", campaign.testingUrl))
                                    Toast.makeText(context, "Đã sao chép link Closed Test!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sao chép")
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(campaign.testingUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Mở link")
                            }
                        }
                    }
                }

                if (campaign.playUrl.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Link Google Play:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(campaign.playUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Play URL", campaign.playUrl))
                                    Toast.makeText(context, "Đã sao chép link Google Play!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Sao chép")
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(campaign.playUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Mở link")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
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
        title = { Text("Người đang cài (${testers.size})") },
        text = {
            if (testers.isEmpty()) {
                Text(
                    text = "Chưa có tester nào tham gia chiến dịch này.",
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
                                        text = "Đã check-in: ${item.checkinCount} ngày" +
                                                (item.lastCheckinDate?.let { " · Gần nhất: $it" } ?: ""),
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
                                            text = "✓ Đủ 14 ngày",
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
                                            text = "Còn ${14 - item.checkinCount} ngày",
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
                Text("Đóng")
            }
        }
    )
}

@Composable
private fun TesterReportsDialog(
    reports: List<TesterReport>,
    onDismiss: () -> Unit,
    onApprove: (TesterReport, Int) -> Unit,
    onReject: (TesterReport, String?) -> Unit,
) {
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var reportToReject by remember { mutableStateOf<TesterReport?>(null) }

    val sortedReports = remember(reports) {
        reports.sortedWith(
            compareByDescending<TesterReport> { it.isPending }
                .thenByDescending { it.createdAt }
        )
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

    val pendingCount = reports.count { it.isPending }
    val titleText = if (pendingCount > 0) "Báo cáo của tester ($pendingCount chờ duyệt / ${reports.size})" else "Báo cáo của tester (${reports.size})"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            if (sortedReports.isEmpty()) {
                Text(
                    text = "Chưa có báo cáo nào từ tester.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(sortedReports, key = { index, r -> "rep_${r.id}_$index" }) { _, report ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Tester Header Row: Avatar + Name + Status tag
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val testerDisplayName = report.resolvedTesterName
                                        val initial = (testerDisplayName.take(1)).uppercase()
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = initial,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = testerDisplayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    report.createdAt?.let {
                                        Text(text = it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (report.status == "pending") "⏳ Chờ duyệt (còn ${report.remainingHoursUntilAutoAccept()}h)"
                                               else if (report.status == "approved") "✓ Đã duyệt (+${if (report.bonusAwarded > 0) report.bonusAwarded else 2} C)"
                                               else "✕ Đã từ chối",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (report.status == "pending") Color(0xFFE65100) else if (report.status == "approved") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                if (report.status == "pending") {
                                    Text(
                                        text = "⏱ Cần duyệt trong vòng 24 giờ. Quá 24 giờ hệ thống sẽ tự động duyệt và cộng 2 Credit cho người kiểm thử.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFE65100),
                                    )
                                }
                                if (report.content.isNotBlank()) {
                                    Text(text = report.content, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (report.isRejected && !report.rejectionReason.isNullOrBlank()) {
                                    Text(
                                        text = "Lý do từ chối: ${report.rejectionReason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                if (!report.screenshotUrl.isNullOrBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { previewImageUrl = report.screenshotUrl }
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(6.dp)
                                    ) {
                                        AsyncImage(
                                            model = report.screenshotUrl,
                                            contentDescription = "Ảnh chụp màn hình",
                                            modifier = Modifier
                                                .size(width = 80.dp, height = 55.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Column {
                                            Text(
                                                "Ảnh chụp đính kèm",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                "Chạm để xem ảnh lớn 🔍",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }

                                if (report.status == "pending") {
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { reportToReject = report },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Từ chối")
                                        }
                                        Button(
                                            onClick = { onApprove(report, 0) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Text("Duyệt (+2)")
                                        }
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
                Text("Đóng")
            }
        }
    )

    if (previewImageUrl != null) {
        Dialog(onDismissRequest = { previewImageUrl = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Ảnh chụp màn hình tester",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { previewImageUrl = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Đóng")
                        }
                    }
                    AsyncImage(
                        model = previewImageUrl,
                        contentDescription = "Full screenshot",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
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
    val predefinedReasons = listOf(
        "Ảnh trùng lặp với ngày hôm trước",
        "Ảnh không rõ ràng / không phải app này",
        "Chưa mở ứng dụng hoặc chưa test thực tế",
        "Lý do khác...",
    )
    var selectedReasonIndex by remember { mutableStateOf(0) }
    var customReason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Xác nhận từ chối báo cáo ⚠️",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Vui lòng chọn lý do từ chối để tester có thể rút kinh nghiệm và nộp lại:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "⚠ Lưu ý: Nếu tester bị từ chối 3 lần/ngày sẽ bị Cảnh báo. Ngược lại, nếu chủ app từ chối quá 5 lần/ngày cũng sẽ bị Cảnh báo từ hệ thống. Hãy đối chiếu kỹ ảnh trước khi từ chối.",
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
                        placeholder = { Text("Nhập lý do cụ thể...") },
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
                        customReason.trim().ifBlank { "Chưa đạt yêu cầu kiểm thử" }
                    } else {
                        predefinedReasons[selectedReasonIndex]
                    }
                    onConfirm(report, finalReason)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Xác nhận từ chối")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}
