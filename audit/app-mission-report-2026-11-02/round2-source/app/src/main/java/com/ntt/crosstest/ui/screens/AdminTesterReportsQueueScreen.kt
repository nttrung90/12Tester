package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminTesterReportQueueItem
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.components.FullscreenImageViewer
import com.ntt.crosstest.ui.viewmodels.AdminTesterReportsQueueViewModel
import com.ntt.crosstest.util.ScreenshotRequestFactory
import com.ntt.crosstest.util.ScreenshotVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTesterReportsQueueScreen(
    onBack: () -> Unit,
    onOpenCampaign: (campaignId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: AdminTesterReportsQueueViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AdminTesterReportsQueueViewModel(
                    adminManagementRepository = app.adminManagementRepository,
                    authRepository = app.authRepository,
                    localizer = Localizer(app),
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedReportId by rememberSaveable(uiState.currentAdminId, uiState.sessionGeneration) {
        mutableStateOf<String?>(null)
    }
    var approvingReportId by rememberSaveable(uiState.currentAdminId, uiState.sessionGeneration) {
        mutableStateOf<String?>(null)
    }
    var rejectingReportId by rememberSaveable(uiState.currentAdminId, uiState.sessionGeneration) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_tool_reports_queue), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.admin_refresh_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Filters
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = uiState.statusFilter == "pending",
                            onClick = { viewModel.setStatusFilter("pending") },
                            label = { Text(stringResource(R.string.admin_status_pending)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == "approved",
                            onClick = { viewModel.setStatusFilter("approved") },
                            label = { Text(stringResource(R.string.admin_status_approved)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == "rejected",
                            onClick = { viewModel.setStatusFilter("rejected") },
                            label = { Text(stringResource(R.string.admin_status_rejected)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == null,
                            onClick = { viewModel.setStatusFilter(null) },
                            label = { Text(stringResource(R.string.admin_report_queue_all)) },
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = uiState.contextFilter == null,
                            onClick = { viewModel.setContextFilter(null) },
                            label = { Text(stringResource(R.string.admin_report_context_all)) },
                        )
                        FilterChip(
                            selected = uiState.contextFilter == "app_test",
                            onClick = { viewModel.setContextFilter("app_test") },
                            label = { Text(stringResource(R.string.admin_report_context_app_test)) },
                        )
                        FilterChip(
                            selected = uiState.contextFilter == "mission",
                            onClick = { viewModel.setContextFilter("mission") },
                            label = { Text(stringResource(R.string.admin_report_context_mission)) },
                        )
                    }
                }

                if (uiState.reports.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.isLoading) CircularProgressIndicator()
                        else Text(stringResource(R.string.admin_report_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.reports, key = { it.id }) { item ->
                            TesterReportQueueCard(
                                item = item,
                                isActionLoading = uiState.isActionLoading,
                                onOpenCampaign = { onOpenCampaign(item.campaignId) },
                                onViewScreenshot = { selectedReportId = item.id },
                                onApprove = { approvingReportId = item.id },
                                onReject = { rejectingReportId = item.id },
                            )
                        }

                        if (uiState.hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        OutlinedButton(onClick = viewModel::loadMore) {
                                            Text(stringResource(R.string.admin_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Guarded media preview: resolves report and descriptor through guarded ScreenshotRequestFactory path
    selectedReportId?.let { repId ->
        val item = uiState.reports.find { it.id == repId }
        item?.let {
            val report = it.toTesterReport()
            val descriptor = it.toScreenshotDescriptor(ScreenshotVariant.FULL)
            FullscreenImageViewer(
                imageUrl = it.screenshotUrl ?: it.thumbnailUrl,
                report = report,
                descriptor = descriptor,
                onDismiss = { selectedReportId = null },
            )
        }
    }

    approvingReportId?.let { repId ->
        val item = uiState.reports.find { it.id == repId }
        val bonus = item?.effectiveTesterBonus ?: 2
        ApproveReportDialog(
            reportId = repId,
            configuredBonus = bonus,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { approvingReportId = null },
            onConfirm = {
                viewModel.approveReport(
                    repId,
                    bonusCredits = 0,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                approvingReportId = null
            }
        )
    }

    rejectingReportId?.let { repId ->
        RejectReportDialog(
            reportId = repId,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { rejectingReportId = null },
            onConfirm = { reason ->
                viewModel.rejectReport(
                    repId,
                    reason,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                rejectingReportId = null
            }
        )
    }
}

@Composable
private fun TesterReportQueueCard(
    item: AdminTesterReportQueueItem,
    isActionLoading: Boolean,
    onOpenCampaign: () -> Unit,
    onViewScreenshot: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.campaignName ?: item.packageName ?: stringResource(R.string.admin_stat_campaigns),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.admin_report_tester_format,
                            item.testerName ?: item.testerEmail ?: item.testerId?.take(8).orEmpty()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val statusLabel = when (item.status) {
                    "approved" -> stringResource(R.string.admin_status_approved)
                    "rejected" -> stringResource(R.string.admin_status_rejected)
                    else -> stringResource(R.string.admin_status_pending)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (item.status) {
                                "approved" -> MaterialTheme.colorScheme.primaryContainer
                                "rejected" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Rating, Context & Bonus
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.rating?.let {
                        Text(stringResource(R.string.admin_report_rating, it), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.admin_report_configured_bonus_badge, item.effectiveTesterBonus),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = if (item.submissionContext == "mission") {
                        stringResource(R.string.admin_report_mission_format, item.missionTitle ?: "")
                    } else {
                        stringResource(R.string.admin_report_context_app_test)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.content?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }

            if (item.status == "rejected" && item.rejectionReason != null) {
                Text(
                    text = stringResource(R.string.admin_report_rejection_reason_format, item.rejectionReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Guarded screenshot thumbnail through ScreenshotRequestFactory
            val rawUrl = item.thumbnailUrl ?: item.screenshotUrl
            if (!rawUrl.isNullOrBlank()) {
                val imageModel = remember(item.id, rawUrl) {
                    ScreenshotRequestFactory.createRequest(
                        context = context,
                        report = item.toTesterReport(),
                        variant = ScreenshotVariant.THUMBNAIL,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onViewScreenshot),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = stringResource(R.string.admin_report_view_screenshot),
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        text = stringResource(R.string.admin_report_view_screenshot),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Actions
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = onOpenCampaign) {
                    Text(stringResource(R.string.admin_report_view_campaign))
                }

                if (item.status == "pending") {
                    Button(
                        onClick = onApprove,
                        enabled = !isActionLoading,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.admin_report_approve_btn))
                    }

                    OutlinedButton(
                        onClick = onReject,
                        enabled = !isActionLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.admin_report_reject_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApproveReportDialog(
    reportId: String,
    configuredBonus: Int,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_report_approve_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.admin_report_approve_confirm), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.admin_report_approve_explanation, configuredBonus))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RejectReportDialog(
    reportId: String,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by rememberSaveable(reportId, currentAdminId, sessionGeneration) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_report_reject_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.admin_report_reject_reason))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.admin_report_reject_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                enabled = !isLoading && reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
