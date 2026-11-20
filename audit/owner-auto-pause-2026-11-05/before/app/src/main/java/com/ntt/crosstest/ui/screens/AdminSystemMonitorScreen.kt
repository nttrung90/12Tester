package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminJobRunItem
import com.ntt.crosstest.data.AdminSystemMonitorSummary
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.viewmodels.AdminSystemMonitorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSystemMonitorScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: AdminSystemMonitorViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AdminSystemMonitorViewModel(
                    adminManagementRepository = app.adminManagementRepository,
                    authRepository = app.authRepository,
                    localizer = Localizer(app),
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var retryTargetJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBatchRetryDialog by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.admin_tool_monitor), fontWeight = FontWeight.Bold) },
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Summary Cards
                uiState.summary?.let { summary ->
                    item(key = "monitor_summary_grid") {
                        SystemMonitorSummarySection(summary = summary)
                    }
                }

                // Failed Jobs Header & Actions
                item(key = "jobs_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.admin_monitor_failed_jobs),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (uiState.jobs.isNotEmpty() && uiState.jobTypeFilter == "mission_settlement") {
                                OutlinedButton(
                                    onClick = { showBatchRetryDialog = true },
                                    enabled = !uiState.isActionLoading,
                                ) {
                                    Text(stringResource(R.string.admin_monitor_retry_batch, 20))
                                }
                            }
                        }

                        // Filter chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = uiState.jobTypeFilter == "mission_settlement",
                                onClick = { viewModel.setJobTypeFilter("mission_settlement") },
                                label = { Text(stringResource(R.string.admin_monitor_tab_mission)) },
                            )
                            FilterChip(
                                selected = uiState.jobTypeFilter == "cleanup",
                                onClick = { viewModel.setJobTypeFilter("cleanup") },
                                label = { Text(stringResource(R.string.admin_monitor_tab_cleanup)) },
                            )
                        }
                    }
                }

                if (uiState.jobs.isEmpty()) {
                    item(key = "empty_jobs") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.admin_monitor_empty_jobs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(uiState.jobs, key = { it.id }) { job ->
                        JobRunCard(
                            job = job,
                            canRetry = uiState.jobTypeFilter == "mission_settlement",
                            isActionLoading = uiState.isActionLoading,
                            onRetry = { retryTargetJobId = job.id },
                        )
                    }

                    if (uiState.hasMore) {
                        item(key = "load_more") {
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

    // Dialogs
    retryTargetJobId?.let { jobId ->
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) retryTargetJobId = null },
            title = { Text(stringResource(R.string.admin_monitor_retry_job)) },
            text = { Text(stringResource(R.string.admin_monitor_retry_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.retryJob(
                            jobId,
                            confirmingAdminId = uiState.currentAdminId,
                            confirmingSessionGen = uiState.sessionGeneration,
                        )
                        retryTargetJobId = null
                    },
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { retryTargetJobId = null }, enabled = !uiState.isActionLoading) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBatchRetryDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) showBatchRetryDialog = false },
            title = { Text(stringResource(R.string.admin_monitor_retry_batch, 20)) },
            text = { Text(stringResource(R.string.admin_monitor_batch_retry_confirm, 20)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.retryBatch(
                            batchLimit = 20,
                            confirmingAdminId = uiState.currentAdminId,
                            confirmingSessionGen = uiState.sessionGeneration,
                        )
                        showBatchRetryDialog = false
                    },
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRetryDialog = false }, enabled = !uiState.isActionLoading) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SystemMonitorSummarySection(summary: AdminSystemMonitorSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Members & Campaigns
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_monitor_section_members), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.admin_monitor_total_format, summary.members.total))
                    Text(stringResource(R.string.admin_monitor_locked_format, summary.members.locked), color = if (summary.members.locked > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.admin_monitor_admins_mods_format, summary.members.admins, summary.members.moderators), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_monitor_section_campaigns), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.admin_monitor_total_format, summary.campaigns.total))
                    Text(stringResource(R.string.admin_monitor_open_format, summary.campaigns.open), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.admin_monitor_paused_completed_format, summary.campaigns.paused, summary.campaigns.completed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Reports & Settlement Jobs
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_monitor_section_reports), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.admin_monitor_pending_tester_reports, summary.testerReports.pending), color = if (summary.testerReports.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.admin_monitor_pending_contact_reports, summary.contactReports.pending), color = if (summary.contactReports.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.admin_monitor_approved_rejected_format, summary.testerReports.approved, summary.testerReports.rejected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_monitor_mission_settlement_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.admin_monitor_failed_jobs_count, summary.missionSettlementJobs.failed), color = if (summary.missionSettlementJobs.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.admin_monitor_pending_jobs_count, summary.missionSettlementJobs.pending))
                    Text(stringResource(R.string.admin_monitor_completed_jobs_count, summary.missionSettlementJobs.completed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Cleanup Run
        summary.cleanupRuns.lastRun?.let { lastRun ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.admin_monitor_section_cleanup), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.admin_monitor_last_cleanup, lastRun.jobName.orEmpty(), lastRun.status.orEmpty()))
                    Text(stringResource(R.string.admin_monitor_cleanup_stats, lastRun.scanned, lastRun.deleted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun JobRunCard(
    job: AdminJobRunItem,
    canRetry: Boolean,
    isActionLoading: Boolean,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.admin_user_id_format, job.id.take(8) + "..."),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                val jobStatusLabel = when (job.status) {
                    "completed" -> stringResource(R.string.admin_status_completed)
                    "failed" -> stringResource(R.string.admin_status_rejected)
                    else -> stringResource(R.string.admin_status_pending)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (job.status) {
                                "completed" -> MaterialTheme.colorScheme.primaryContainer
                                "failed" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = jobStatusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            job.lastError?.let {
                Text(stringResource(R.string.admin_monitor_error_format, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Text(stringResource(R.string.admin_monitor_attempts_format, job.attemptCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (canRetry && job.status == "failed") {
                Button(
                    onClick = onRetry,
                    enabled = !isActionLoading,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.admin_monitor_retry_job))
                }
            }
        }
    }
}
