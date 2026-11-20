package com.ntt.crosstest.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.ui.viewmodels.MissionReportViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MissionReportScreen(
    campaignId: String,
    missionId: String,
    onBack: () -> Unit,
    viewModel: MissionReportViewModel? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val actualViewModel: MissionReportViewModel = viewModel ?: viewModel(
        key = "$campaignId:$missionId",
        factory = viewModelFactory {
            initializer {
                MissionReportViewModel(
                    campaignId = campaignId,
                    missionId = missionId,
                    repository = app.campaignRepository,
                    reportSubmitManager = app.reportSubmitManager,
                    appContext = app,
                )
            }
        }
    )
    val uiState by actualViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val multiplePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            actualViewModel.addScreenshots(uris)
        }
    }

    val singlePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            actualViewModel.addScreenshots(listOf(uri))
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        actualViewModel.onForegroundResume()
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            actualViewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            actualViewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.mission_report_window_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val camp = uiState.campaign
        val mission = uiState.mission

        if (camp == null || mission == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.mission_err_not_found))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) {
                        Text(text = stringResource(R.string.close))
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 1. Mission Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            iconUrl = camp.iconUrl.orEmpty(),
                            packageName = camp.packageName,
                            appName = camp.appName,
                            size = 48.dp,
                            shapeRadius = 12.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = camp.appName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = mission.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.statusColors.successContainer,
                            contentColor = MaterialTheme.statusColors.onSuccessContainer,
                        ) {
                            Text(
                                text = if (mission.isDailyMode) {
                                    stringResource(R.string.mission_reward_badge_daily_format, mission.rewardCredits)
                                } else {
                                    stringResource(R.string.mission_report_reward_format, mission.rewardCredits)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = stringResource(
                                    if (mission.isDailyMode) R.string.mission_mode_daily else R.string.mission_mode_once
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (mission.daysRemaining != null || mission.isDailyMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            mission.daysRemaining?.let { days ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DateRange,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.mission_days_remaining_format, days),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (mission.isDailyMode) {
                                Text(
                                    text = stringResource(
                                        R.string.mission_progress_daily_format,
                                        mission.submittedDaysCount,
                                        mission.durationDays,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    if (mission.description.isNotBlank()) {
                        Text(
                            text = mission.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. Hai nút phía trên: Mở app & Link Play Store (Side by Side)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val notInstalledMsg = stringResource(R.string.mission_not_installed_hint)
                Button(
                    onClick = {
                        val pkg = camp.packageName
                        val launchIntent = try {
                            context.packageManager.getLaunchIntentForPackage(pkg)
                        } catch (_: Exception) { null }

                        if (launchIntent != null) {
                            try {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                actualViewModel.recordTestStart()
                                return@Button
                            } catch (_: Exception) {}
                        }
                        Toast.makeText(context, notInstalledMsg, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.mission_report_open_app_btn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val pkg = camp.packageName
                        try {
                            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                setPackage("com.android.vending")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(marketIntent)
                        } catch (_: Exception) {
                            val fallbackUrl = if (camp.isProduction) {
                                camp.playUrl.ifBlank { camp.testingUrl }.ifBlank { "https://play.google.com/store/apps/details?id=$pkg" }
                            } else {
                                camp.testingUrl.ifBlank { camp.playUrl }.ifBlank { "https://play.google.com/store/apps/details?id=$pkg" }
                            }
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Shop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.mission_report_play_store_btn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Test Duration indicator
            if (uiState.minTestDurationRemainingSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mission_report_testing_countdown_format, uiState.minTestDurationRemainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(20.dp))

            // 3. Phần biểu mẫu: Báo cáo nhiệm vụ app
            Text(
                text = stringResource(R.string.mission_report_form_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(10.dp))

            // Trạng thái báo cáo hôm nay (nếu đã nộp / duyệt / từ chối)
            val todayRep = uiState.todayReport
            if (todayRep != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (todayRep.status) {
                            "approved" -> MaterialTheme.statusColors.successContainer
                            "rejected" -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when (todayRep.status) {
                            "approved" -> MaterialTheme.statusColors.onSuccessContainer
                            "rejected" -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = when (todayRep.status) {
                                "approved" -> stringResource(R.string.mission_report_status_approved)
                                "rejected" -> stringResource(R.string.mission_report_status_rejected)
                                else -> stringResource(R.string.mission_report_status_pending)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        todayRep.reportDate?.let { dateStr ->
                            Text(
                                text = stringResource(R.string.mission_report_date_badge_format, dateStr),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (todayRep.status == "rejected" && !todayRep.rejectionReason.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.mission_report_rejection_reason_format, todayRep.rejectionReason),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Star Rating
            Text(
                text = stringResource(R.string.mission_report_rating_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (star in 1..5) {
                    IconButton(
                        onClick = { if (todayRep?.status != "approved") actualViewModel.updateRating(star) },
                        modifier = Modifier.size(36.dp),
                        enabled = todayRep?.status != "approved",
                    ) {
                        Icon(
                            imageVector = if (star <= uiState.rating) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = "$star star",
                            tint = if (star <= uiState.rating) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Comment textfield
            OutlinedTextField(
                value = uiState.comment,
                onValueChange = { actualViewModel.updateComment(it) },
                label = { Text(stringResource(R.string.mission_report_comment_label)) },
                placeholder = { Text(stringResource(R.string.mission_report_comment_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(16.dp))

            // 4. Chọn và hiển thị ảnh minh chứng (tối đa 3 ảnh)
            val serverAttachments = todayRep?.allAttachments.orEmpty()
            val displayedPhotoCount = if (uiState.preparedScreenshots.isNotEmpty()) {
                uiState.preparedScreenshots.size
            } else {
                serverAttachments.size
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mission_report_photos_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.mission_report_photos_count_format,
                        displayedPhotoCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Grid / Row of photos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Hiển thị các ảnh đã chọn và chuẩn bị
                uiState.preparedScreenshots.forEachIndexed { index, prep ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { actualViewModel.setPreviewScreenshot(prep) },
                    ) {
                        AsyncImage(
                            model = prep.thumbnailFile ?: prep.file,
                            contentDescription = "Photo ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Nút xóa ảnh
                        if (todayRep?.status != "approved") {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .clickable { actualViewModel.removeScreenshot(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Xóa",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }

                // Nếu chưa đủ 3 ảnh và chưa approved: hiện ô "Thêm ảnh"
                val remainingSlots = 3 - uiState.preparedScreenshots.size
                if (remainingSlots > 0 && todayRep?.status != "approved") {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                            .clickable {
                                if (remainingSlots == 1) {
                                    singlePhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                } else {
                                    multiplePhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Thêm ảnh",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.mission_report_add_photo),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Empty placeholder slots để cân bằng 3 cột
                val emptySlots = 3 - uiState.preparedScreenshots.size - (if (uiState.preparedScreenshots.size < 3 && todayRep?.status != "approved") 1 else 0)
                for (s in 0 until emptySlots) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            if (uiState.isCompressing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.mission_report_compressing_wait),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            uiState.compressionError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Hiển thị ảnh cũ từ server nếu có và chưa chọn ảnh mới
            if (uiState.preparedScreenshots.isEmpty() && todayRep != null) {
                if (serverAttachments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.mission_report_server_photos_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        serverAttachments.forEach { att ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { actualViewModel.setPreviewUrl(att.screenshotUrl, att.id) },
                            ) {
                                AsyncImage(
                                    model = remember(context, att.screenshotUrl, att.id) {
                                        com.ntt.crosstest.util.ScreenshotRequestFactory.createRequest(
                                            context = context,
                                            model = att.thumbnailUrl ?: att.screenshotUrl,
                                            report = todayRep,
                                            variant = com.ntt.crosstest.util.ScreenshotVariant.THUMBNAIL,
                                            attachmentId = att.id,
                                        )
                                    } ?: (att.thumbnailUrl ?: att.screenshotUrl),
                                    contentDescription = "Attachment",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        for (s in 0 until (3 - serverAttachments.size)) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 5. Submit Progress / Actions
            val subState = uiState.submitState
            if (subState != null) {
                when (subState.stage) {
                    ReportSubmitManager.Stage.SENDING, ReportSubmitManager.Stage.RETRYING -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.mission_report_sending_status),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    ReportSubmitManager.Stage.FAILED -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.mission_report_failed_status,
                                        com.ntt.crosstest.ui.viewmodels.friendlyError(subState.error ?: Exception(), com.ntt.crosstest.data.Localizer(context))
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { actualViewModel.retrySubmit() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text(stringResource(R.string.mission_report_retry_btn))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    ReportSubmitManager.Stage.DONE -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.statusColors.success,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.mission_report_submitted_waiting),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.statusColors.success,
                            )
                        }
                    }
                }
            }

            // Action Button: Nhận nhiệm vụ hoặc Gửi báo cáo
            if (!uiState.isJoined) {
                if (mission.isBlockedPreviousParticipant) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            text = stringResource(R.string.mission_blocked_previous_participant_reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = { actualViewModel.joinMission() },
                    enabled = !uiState.isJoining && !mission.isBlockedPreviousParticipant,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (uiState.isJoining) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.mission_report_join_first_btn),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                val isApproved = todayRep?.status == "approved"
                val isPending = todayRep?.status == "pending"
                val isExemptFromTimer = isApproved || isPending
                val canSubmit = isExemptFromTimer || (uiState.testStartedAtMs != null && uiState.minTestDurationRemainingSeconds == 0)
                Button(
                    onClick = { actualViewModel.submitReport() },
                    enabled = !uiState.isCompressing && subState?.stage != ReportSubmitManager.Stage.SENDING && canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    val btnText = when {
                        isApproved || isPending -> stringResource(R.string.mission_report_btn_update_comment)
                        !isExemptFromTimer && uiState.testStartedAtMs == null -> stringResource(R.string.mission_report_btn_need_test)
                        !isExemptFromTimer && uiState.minTestDurationRemainingSeconds > 0 -> stringResource(R.string.mission_report_btn_testing_countdown, uiState.minTestDurationRemainingSeconds)
                        else -> stringResource(R.string.mission_report_submit_btn)
                    }
                    Text(
                        text = btnText,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Full Screen Preview Dialog
    if (uiState.previewScreenshot != null || uiState.previewUrl != null) {
        Dialog(
            onDismissRequest = {
                actualViewModel.setPreviewScreenshot(null)
                actualViewModel.setPreviewUrl(null)
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = remember(context, uiState.previewUrl, uiState.previewAttachmentId) {
                            if (uiState.previewScreenshot != null) {
                                uiState.previewScreenshot?.file
                            } else {
                                com.ntt.crosstest.util.ScreenshotRequestFactory.createRequest(
                                    context = context,
                                    model = uiState.previewUrl,
                                    report = uiState.todayReport,
                                    variant = com.ntt.crosstest.util.ScreenshotVariant.FULL,
                                    attachmentId = uiState.previewAttachmentId,
                                )
                            }
                        } ?: uiState.previewScreenshot?.file ?: uiState.previewUrl,
                        contentDescription = "Full preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = {
                            actualViewModel.setPreviewScreenshot(null)
                            actualViewModel.setPreviewUrl(null)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }
}
