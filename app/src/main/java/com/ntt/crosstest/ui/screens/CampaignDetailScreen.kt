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
import com.ntt.crosstest.ui.components.FullscreenImageViewer
import com.ntt.crosstest.util.ImageHashHelper
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
                CampaignDetailViewModel(campaignId, app.campaignRepository, app.authRepository.currentUserId())
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
    var previewImageUrl by rememberSaveable { mutableStateOf<String?>(null) }

    var isInstalledLocally by remember { mutableStateOf(false) }

    val pickScreenshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onScreenshotSelected(uri)
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
                    text = "CẢNH BÁO TỪ HỆ THỐNG ⚠️",
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
                            text = "Lưu ý: Số lần bị cảnh báo trong tuần: ${uiState.weeklyWarningsCount}/3. Nếu bị 3 lần trong 1 tuần, tài khoản sẽ tự động bị tạm khóa.",
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
                    Text("Tôi đã hiểu")
                }
            }
        )
    }

    val campaign = uiState.campaign

    // Dialog: Gợi ý test app tiếp theo sau khi nộp báo cáo thành công
    if (uiState.showNextAppPrompt) {
        val nextCamp = uiState.nextCampaignToTest
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
                    text = if (nextCamp != null) "Kiểm thử app tiếp theo? 🚀" else "Hoàn thành kiểm thử! 🎉",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (nextCamp != null) {
                        Text(
                            text = "Nộp báo cáo thành công! Bạn có muốn Test app tiếp theo không?",
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
                                        text = "Thưởng: +2 Credit khi hoàn thành",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF2E7D32),
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Tuyệt vời! Bạn đã hoàn thành kiểm thử tất cả các ứng dụng có sẵn hôm nay.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                if (nextCamp != null) {
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
                        Text("Có, test ngay")
                    }
                } else {
                    Button(
                        onClick = { viewModel.dismissNextAppPrompt() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Xong")
                    }
                }
            },
            dismissButton = {
                if (nextCamp != null) {
                    TextButton(onClick = { viewModel.dismissNextAppPrompt() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Không")
                    }
                }
            }
        )
    }

    // Dialog: Chưa cài ứng dụng
    if (showNotInstalledDialog && campaign != null) {
        AlertDialog(
            onDismissRequest = { showNotInstalledDialog = false },
            title = { Text("Ứng dụng chưa được cài") },
            text = { Text("Ứng dụng \"${campaign.appName}\" (${campaign.packageName}) chưa có trên thiết bị này. Bạn có muốn mở link để cài đặt không?") },
            confirmButton = {
                Button(onClick = {
                    showNotInstalledDialog = false
                    viewModel.ensureJoined()
                    val targetUrl = campaign.testingUrl.ifBlank { campaign.playUrl }
                    openUrlSafely(context, targetUrl, campaign.packageName)
                }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cài đặt ngay")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotInstalledDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Đóng")
                }
            }
        )
    }

    // Dialog: Hướng dẫn cài đặt
    if (showHowToInstallDialog && campaign != null) {
        val groupUrl = stringResource(R.string.post_app_guide_group_url)
        AlertDialog(
            onDismissRequest = { showHowToInstallDialog = false },
            title = { Text("Hướng dẫn cài đặt app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Bạn hãy đảm bảo đã tham gia Google Group kiểm thử của cộng đồng (nếu app yêu cầu).")
                    OutlinedButton(
                        onClick = {
                            openUrlSafely(context, groupUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tham gia Google Group kiểm thử", fontSize = 12.sp)
                    }
                    Text("2. Mở link Closed Testing hoặc Google Play bên dưới để chấp nhận lời mời và tải app về máy.")
                    Text("3. Sau khi cài xong, quay lại đây bấm \"Test app (mở app trên máy)\" và nộp báo cáo ảnh.")
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
                    Text("Mở link tải app")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHowToInstallDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Đóng")
                }
            }
        )
    }

    // Dialog: Không tải được
    if (showCannotDownloadDialog && campaign != null) {
        val groupUrl = stringResource(R.string.post_app_guide_group_url)
        AlertDialog(
            onDismissRequest = { showCannotDownloadDialog = false },
            title = { Text("Không tải được ứng dụng? ⚠") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Các nguyên nhân phổ biến:", fontWeight = FontWeight.Bold)
                    Text("• Chưa tham gia Google Group của cộng đồng kiểm thử.")
                    OutlinedButton(
                        onClick = {
                            openUrlSafely(context, groupUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mở tham gia Google Group", fontSize = 12.sp)
                    }
                    Text("• Tài khoản Google Play trên điện thoại chưa khớp với email bạn dùng tham gia nhóm test.")
                    Text("• Chủ app chưa thêm email/group vào danh sách tester trên Google Play Console.")
                    Text("• Bạn chưa bấm \"Chấp nhận lời mời\" trên trang Closed Test của Google Play.")
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
                    Text("Mở link web Closed Test")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCannotDownloadDialog = false }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Đóng")
                }
            }
        )
    }

    // Dialog: Lịch sử test
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Lịch sử kiểm thử của bạn 🕒") },
            text = {
                val reports = uiState.myReports
                val checkinDates = uiState.myCheckinDates
                if (reports.isEmpty() && checkinDates.isEmpty()) {
                    Text("Bạn chưa thực hiện ngày test nào cho ứng dụng này. Hãy mở app và nộp báo cáo để tích lũy ngày test!")
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
                                                text = "Ngày: ${rep.createdAt?.take(10) ?: "Hôm nay"}",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            val badgeText = when {
                                                rep.isApproved -> "✓ Đã duyệt (+${if (rep.bonusAwarded > 0) rep.bonusAwarded else 2} C)"
                                                rep.isPending -> "⏳ Chờ duyệt (còn ${rep.remainingHoursUntilAutoAccept()}h)"
                                                else -> "✕ Từ chối"
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
                                            Text(text = "Lý do: ${rep.rejectionReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                                                    contentDescription = "Ảnh chụp đã nộp",
                                                    modifier = Modifier
                                                        .size(width = 60.dp, height = 42.dp)
                                                        .clip(RoundedCornerShape(4.dp)),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Text(
                                                    text = "Xem ảnh đã nộp 🔍",
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
                                        Text("Ngày test: $date", fontWeight = FontWeight.SemiBold)
                                        Text("✓ +2 Credit", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
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
                    Text("Đóng")
                }
            }
        )
    }

    // BottomSheet: Nộp báo cáo kiểm thử (+2 Credit khi gửi) - Rộng rãi, preview ảnh lớn, phát hiện trùng lặp
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
            onScreenshotSelected = viewModel::onScreenshotSelected,
            onRatingChange = viewModel::onRatingChange,
            onCommentChange = viewModel::onCommentChange,
            onSubmit = {
                viewModel.submitFeedback(context)
                showReportDialog = false
            },
            onPreviewImage = { url: String -> previewImageUrl = url },
        )
    }

    if (showAdminDeleteDialog) {
        val appName = uiState.campaign?.appName ?: "ứng dụng này"
        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: "chủ app"

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
                Text("Gỡ bỏ ứng dụng (Admin)")
            },
            text = {
                Text(
                    text = "Bạn có chắc chắn muốn gỡ bỏ app \"$appName\" của $ownerName?\n\nHành động này sẽ xóa vĩnh viễn chiến dịch cùng toàn bộ hồ sơ kiểm thử, check-in, đánh giá và báo cáo của app này khỏi hệ thống."
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
                    Text("Gỡ bỏ & Xóa toàn bộ hồ sơ")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAdminDeleteDialog = false },
                    enabled = !uiState.isActionLoading,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hủy")
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
                        Icon(Icons.Filled.History, contentDescription = "Lịch sử test")
                    }
                    if (uiState.isAdmin) {
                        IconButton(onClick = { showAdminDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Gỡ app (Admin)",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Làm mới")
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
                Text(uiState.error ?: "Không tìm thấy thông tin app.")
            }
            return@Scaffold
        }

        val ownerName = uiState.ownerProfile?.displayName?.ifBlank { null }
            ?: uiState.ownerProfile?.email?.substringBefore("@")
            ?: "test app"
        val ownerPoints = "${uiState.ownerProfile?.credits ?: 0}"

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
                                text = "Đây là ứng dụng của bạn",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Quản lý người cài, duyệt báo cáo và cài đặt app tại màn hình quản lý.",
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
                                Text("Quản lý")
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
                            text = "App đã đạt mục tiêu ${campaign.dailyTarget} lượt test hôm nay. Bạn vẫn được hưởng đầy đủ quyền lợi và nhận +2 Credit khi tham gia test!",
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
                                text = "Chủ app: $ownerName · $ownerPoints Credit",
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
                            label = "đã cài",
                        )
                        StatColumn(
                            icon = Icons.Filled.CalendarMonth,
                            value = "${uiState.todayTestedCount}/${campaign.dailyTarget}",
                            label = "test hôm nay",
                        )
                        StatColumn(
                            icon = Icons.AutoMirrored.Filled.Assignment,
                            value = "${uiState.reportsCount}",
                            label = "báo cáo",
                        )
                        StatColumn(
                            icon = Icons.Filled.Star,
                            value = if (uiState.avgRating > 0) "%.1f".format(uiState.avgRating) else "-",
                            label = "đánh giá",
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
                                    text = "Bước 1: Cài đặt app vào máy",
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
                                    text = "Chưa cài đặt",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC2410C),
                                )
                            }
                        }

                        Text(
                            text = "Bạn cần vào link Google Play Closed Test để nhấn 'Chấp nhận tham gia' (Opt-in) và tải ứng dụng về máy.",
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
                                text = "Mở link Closed Test & Tải app",
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
                                    text = "Đã cài đặt trên máy",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF14532D),
                                )
                                Text(
                                    text = "Sẵn sàng mở trực tiếp để kiểm thử",
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
                                text = "Xem link test",
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
                    campaign.isProduction -> "App đã lên production"
                    campaign.isCompleted -> "Đã hoàn thành kiểm thử"
                    else -> "App đang tạm dừng"
                }
                val bannerText = when {
                    campaign.isProduction -> "App đã hoàn thành kiểm thử và không còn nhận báo cáo mới. Cảm ơn bạn đã đồng hành!"
                    campaign.isCompleted -> "App đã hết 15 ngày hiển thị kiểm thử và không còn nhận báo cáo mới."
                    else -> "App tạm dừng nên chưa thể nộp báo cáo. Chủ app có thể bật test lại bất cứ lúc nào."
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
                            text = if (uiState.isOwner) "Mở app (Tự test)" else if (isInstalledLocally) "Mở app kiểm thử" else "Tải & Cài đặt app",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }

                    if (!uiState.isOwner && isInstalledLocally) {
                        Text(
                            text = "💡 Mẹo: Bạn cũng có thể mở app trực tiếp từ Màn hình chính của điện thoại để test rồi quay lại nộp báo cáo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    if (uiState.isOwner) {
                        Text(
                            text = "Bạn là chủ ứng dụng. Thao tác tự test không tính điểm thưởng. Xem người cài và duyệt báo cáo từ các tester tại màn hình Quản lý.",
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
                                Text("Mở trang quản lý ứng dụng", fontWeight = FontWeight.Bold)
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
                                text = "Tiến độ của bạn:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (myDays >= targetDays) "Đạt chuẩn $myDays/$targetDays ngày ✓" else "$myDays/$targetDays ngày test",
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
                            "✓ Đã duyệt hôm nay: Nhận +${if ((todayReport?.bonusAwarded ?: 0) > 0) todayReport?.bonusAwarded else 2} Credit."
                        } else if (isPendingToday) {
                            "⏳ Đã nộp kết quả hôm nay (+2 Credit tạm nhận). Chờ chủ app duyệt (+1 C thưởng) — Tự động duyệt sau ${todayReport?.remainingHoursUntilAutoAccept() ?: 8}h."
                        } else if (isRejectedToday) {
                            val reason = todayReport?.rejectionReason?.ifBlank { null }
                            if (reason != null) "✕ Bị từ chối hôm nay ($reason). 2 Credit tạm nhận đã bị thu hồi. Đề nghị test lại và chụp ảnh khác."
                            else "✕ Bị từ chối hôm nay: 2 Credit tạm nhận đã bị thu hồi. Đề nghị test lại nghiêm túc và chụp ảnh khác."
                        } else {
                            "Đã cài. Dùng thử rồi nộp ảnh chụp màn hình (Tạm nhận +2 Credit ngay khi gửi)."
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
                                        contentDescription = "Ảnh bạn đã nộp",
                                        modifier = Modifier
                                            .size(width = 72.dp, height = 50.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Ảnh bạn đã nộp hôm nay",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Chạm để phóng to xem ảnh lớn 🔍",
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
                                campaign.isProduction -> "App đã lên production 🎉"
                                campaign.isCompleted -> "App đã hoàn thành kiểm thử"
                                else -> "App đang tạm dừng — chưa thể nộp báo cáo"
                            }
                        } else if (isApprovedToday) {
                            "Đã duyệt hôm nay (Chạm để xem lại / góp ý)"
                        } else if (isPendingToday) {
                            "Cập nhật / Nộp lại ảnh khác hôm nay"
                        } else if (isRejectedToday) {
                            "Chụp lại & Nộp lại kết quả hôm nay"
                        } else {
                            "Nộp kết quả kiểm thử (+2 Credit)"
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
                            Text("Chưa cài", style = MaterialTheme.typography.labelMedium)
                        }

                        TextButton(onClick = { showCannotDownloadDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFFD32F2F),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Không tải được", style = MaterialTheme.typography.labelMedium, color = Color(0xFFD32F2F))
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
                            text = "Hướng dẫn thêm từ chủ app",
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
                            Toast.makeText(context, "Đã sao chép ghi chú!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Sao chép ghi chú",
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
                            text = "mình để link tải đây cho bạn nào không cài được theo cách thông thường nhé:\n$downloadLink",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }

                    if (campaign.safeTestUsername.isNotBlank() || campaign.safeTestPassword.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Tài khoản test đăng nhập: ${campaign.safeTestUsername} / ${campaign.safeTestPassword}",
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
                                text = "Khu vực Quản trị viên (Admin)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = "Admin có quyền gỡ bỏ bất kỳ ứng dụng nào. Thao tác này sẽ xóa vĩnh viễn app và toàn bộ hồ sơ kiểm thử liên quan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            Text("Gỡ bỏ ứng dụng & Xóa toàn bộ hồ sơ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
    }

    if (previewImageUrl != null) {
        FullscreenImageViewer(
            imageUrl = previewImageUrl,
            onDismiss = { previewImageUrl = null },
            title = "Ảnh chụp màn hình",
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
        Toast.makeText(context, "Không thể mở liên kết này trên thiết bị.", Toast.LENGTH_SHORT).show()
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
    val alreadyRewarded = (uiState.checkedInToday || todayReport?.isApproved == true) && todayReport?.isRejected != true

    // Check for previous report from this tester to detect duplicate image
    val previousReport = remember(uiState.myReports, uiState.myTodayReport) {
        uiState.myReports.firstOrNull { it.id != uiState.myTodayReport?.id && !it.screenshotUrl.isNullOrBlank() }
    }

    var duplicateWarning by remember { mutableStateOf<String?>(null) }
    var isCheckingDuplicate by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.selectedScreenshotUri, previousReport?.screenshotUrl) {
        val uri = uiState.selectedScreenshotUri
        val prevUrl = previousReport?.screenshotUrl
        if (uri != null && !prevUrl.isNullOrBlank()) {
            isCheckingDuplicate = true
            val newHash = ImageHashHelper.computeDHashFromUri(context, uri)
            val prevHash = ImageHashHelper.computeDHashFromUrl(context, prevUrl)
            if (newHash != null && prevHash != null) {
                val comp = ImageHashHelper.compare(newHash, prevHash)
                if (comp.isSuspectDuplicate) {
                    duplicateWarning = "⚠️ Cảnh báo tương đồng cao (${comp.similarity}%): Ảnh bạn vừa chọn rất giống với ảnh bạn đã nộp kiểm thử trước đó. Bạn nên chụp màn hình tính năng hoặc trang khác của ứng dụng để chắc chắn được chủ app duyệt thưởng và không bị từ chối nhé!"
                } else {
                    duplicateWarning = null
                }
            } else {
                duplicateWarning = null
            }
            isCheckingDuplicate = false
        } else {
            duplicateWarning = null
            isCheckingDuplicate = false
        }
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
                            alreadyRewarded -> "Gửi góp ý bổ sung"
                            isPendingToday -> "Cập nhật kết quả kiểm thử"
                            else -> "Nộp kết quả kiểm thử"
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
                    Icon(Icons.Filled.Close, contentDescription = "Đóng")
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
                                "Hôm nay bạn đã nhận thưởng kiểm thử cho app này. Bạn có thể gửi thêm nhận xét hoặc ảnh chụp màn hình góp ý bổ sung để hỗ trợ chủ app (thao tác này không cộng thêm Credit)."
                            isPendingToday ->
                                "Bạn đã tạm nhận 2 Credit cho kết quả hôm nay và đang chờ chủ app duyệt. Cập nhật thêm ảnh/nhận xét sẽ không cộng thêm Credit; chủ app vẫn có 8 giờ để duyệt."
                            else ->
                                "Bạn sẽ tạm nhận ngay 2 Credit sau khi gửi kết quả. Chủ app có 8 giờ để duyệt (+1 Credit thưởng thêm). Hãy chụp các tính năng khác nhau để được duyệt dễ dàng!"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (alreadyRewarded) Color(0xFF1B5E20) else Color(0xFF166534),
                    )
                }
            }

            // Screenshot Preview Area (Hero Size!)
            Text(
                text = "Ảnh chụp màn hình kiểm thử:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

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
                                contentDescription = "Ảnh đã chọn",
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
                                    Text("Chạm để phóng to", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Duplicate warning if detected
                        if (isCheckingDuplicate) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Đang đối chiếu ảnh với các ngày trước...", style = MaterialTheme.typography.labelSmall)
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
                                Text("Đổi ảnh khác")
                            }
                            OutlinedButton(
                                onClick = { onScreenshotSelected(null) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Gỡ")
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
                                contentDescription = "Ảnh đã nộp",
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
                                    Text("Ảnh đã nộp hôm nay · Phóng to", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onPickScreenshot,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chọn ảnh mới để thay thế")
                        }
                    }
                }
            } else {
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
                            text = "Chạm để tải ảnh chụp màn hình",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Chụp lại giao diện tính năng đang hoạt động trong app",
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
                        text = "Đánh giá ứng dụng:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val ratingLabel = when (uiState.myRating) {
                        1 -> "Chưa đạt (1 sao)"
                        2 -> "Tạm được (2 sao)"
                        3 -> "Bình thường (3 sao)"
                        4 -> "Khá tốt (4 sao)"
                        else -> "Rất tốt (5 sao)"
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
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "$star sao",
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
                    text = "Góp ý & nhận xét (tùy chọn):",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = uiState.myComment,
                    onValueChange = onCommentChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Mô tả lỗi gặp phải, góp ý giao diện hoặc tính năng cho nhà phát triển...") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // Submit Button
            val canSubmit = !uiState.isActionLoading && (
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
                    Text("Đang tải ảnh và gửi báo cáo...", fontSize = 15.sp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            alreadyRewarded -> "Gửi góp ý bổ sung"
                            isPendingToday -> "Cập nhật kết quả đã nộp"
                            else -> "Gửi kết quả (Tạm nhận +2 Credit)"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
