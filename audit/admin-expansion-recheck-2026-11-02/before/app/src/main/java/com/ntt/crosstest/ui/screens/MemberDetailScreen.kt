package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminCreditHistoryItem
import com.ntt.crosstest.data.AdminMemberDailyTaskItem
import com.ntt.crosstest.data.AdminMemberOverview
import com.ntt.crosstest.data.AdminMemberWarningItem
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.viewmodels.CompensationIntent
import com.ntt.crosstest.ui.viewmodels.MemberDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: MemberDetailViewModel = viewModel(
        key = "MemberDetail_$userId",
        factory = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle()
                MemberDetailViewModel(
                    targetUserId = userId,
                    adminManagementRepository = app.adminManagementRepository,
                    authRepository = app.authRepository,
                    localizer = Localizer(app),
                    savedStateHandle = handle,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showRoleDialog by rememberSaveable { mutableStateOf(false) }
    var showCompensateDialog by rememberSaveable { mutableStateOf(false) }
    var revokeTargetWarningId by rememberSaveable { mutableStateOf<String?>(null) }

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
                title = { Text(stringResource(R.string.admin_member_detail_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshAll, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.admin_refresh_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.admin_tab_overview)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.admin_tab_credits)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.admin_tab_daily_tasks)) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text(stringResource(R.string.admin_tab_warnings)) }
                    )
                }

                when (selectedTab) {
                    0 -> OverviewTab(
                        overview = uiState.overview,
                        isLoading = uiState.isLoading,
                        isActionLoading = uiState.isActionLoading,
                        pendingRetryCompensation = uiState.pendingRetryCompensation,
                        onRetryPendingCompensation = {
                            uiState.pendingRetryCompensation?.let { comp ->
                                viewModel.compensateCredits(
                                    amount = comp.amount,
                                    reason = comp.reason,
                                    originalCreditLogId = comp.originalCreditLogId,
                                    contactReportId = comp.contactReportId,
                                    confirmingAdminId = uiState.currentAdminId,
                                    confirmingSessionGen = uiState.sessionGeneration,
                                )
                            }
                        },
                        onDismissPendingCompensation = viewModel::dismissPendingCompensation,
                        onChangeRole = { showRoleDialog = true },
                        onCompensate = { showCompensateDialog = true },
                    )
                    1 -> CreditsTab(
                        credits = uiState.creditHistory,
                        isLoading = uiState.isCreditsLoading,
                        hasMore = uiState.creditHasMore,
                        onLoadMore = viewModel::loadMoreCreditHistory,
                    )
                    2 -> DailyTasksTab(
                        dailyTasks = uiState.dailyTasks,
                        isLoading = uiState.isDailyTasksLoading,
                        hasMore = uiState.dailyTasksHasMore,
                        onLoadMore = viewModel::loadMoreDailyTasks,
                    )
                    3 -> WarningsTab(
                        warnings = uiState.warnings,
                        isLoading = uiState.isWarningsLoading,
                        hasMore = uiState.warningsHasMore,
                        onLoadMore = viewModel::loadMoreWarnings,
                        onRevokeWarning = { revokeTargetWarningId = it },
                    )
                }
            }
        }
    }

    // Role Change Dialog
    if (showRoleDialog && uiState.overview != null) {
        RoleChangeDialog(
            currentRole = uiState.overview?.role ?: "member",
            canManageAdmins = uiState.myCapabilities?.canManageAdmins ?: false,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { showRoleDialog = false },
            onConfirm = { newRole, reason ->
                viewModel.changeRole(
                    newRole = newRole,
                    reason = reason,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                showRoleDialog = false
            }
        )
    }

    // Compensation Dialog
    if (showCompensateDialog) {
        CompensateCreditsDialog(
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { showCompensateDialog = false },
            onConfirm = { amount, reason, origLog, contactId ->
                viewModel.compensateCredits(
                    amount = amount,
                    reason = reason,
                    originalCreditLogId = origLog,
                    contactReportId = contactId,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                showCompensateDialog = false
            }
        )
    }

    // Revoke Warning Dialog
    revokeTargetWarningId?.let { warningId ->
        RevokeWarningDialog(
            warningId = warningId,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { revokeTargetWarningId = null },
            onConfirm = { reason ->
                viewModel.revokeWarning(
                    warningId = warningId,
                    reason = reason,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                revokeTargetWarningId = null
            }
        )
    }
}

@Composable
private fun OverviewTab(
    overview: AdminMemberOverview?,
    isLoading: Boolean,
    isActionLoading: Boolean,
    pendingRetryCompensation: CompensationIntent?,
    onRetryPendingCompensation: () -> Unit,
    onDismissPendingCompensation: () -> Unit,
    onChangeRole: () -> Unit,
    onCompensate: () -> Unit,
) {
    if (overview == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Pending retry banner if previous compensation was ambiguous / lost response
        pendingRetryCompensation?.let { pendingComp ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.admin_compensation_pending_retry_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.admin_compensation_pending_retry_msg, pendingComp.amount, pendingComp.reason),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetryPendingCompensation,
                            enabled = !isActionLoading,
                        ) {
                            Text(stringResource(R.string.admin_compensation_pending_retry_btn))
                        }
                        TextButton(
                            onClick = onDismissPendingCompensation,
                            enabled = !isActionLoading,
                        ) {
                            Text(stringResource(R.string.admin_compensation_pending_dismiss_btn))
                        }
                    }
                }
            }
        }
        // User Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val initial = (overview.displayName?.firstOrNull() ?: overview.email?.firstOrNull() ?: '?').uppercaseChar()
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initial.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = overview.displayName ?: stringResource(R.string.admin_user_fallback),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        overview.email?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Role badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (overview.role == "admin") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = overview.role.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Lock badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (overview.isLocked) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (overview.isLocked) stringResource(R.string.status_locked) else stringResource(R.string.status_active),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (overview.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        )
                    }

                    // Credits badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🪙 ${overview.credits}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // ID
                Text(
                    text = stringResource(R.string.admin_user_id_format, overview.id),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Stats Grid
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.admin_tab_overview), fontWeight = FontWeight.Bold)

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_campaigns), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.admin_stat_campaigns_detail, overview.campaignsTotal, overview.campaignsOpen), fontWeight = FontWeight.SemiBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_slots), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.admin_stat_slots_detail, overview.slotsTotal, overview.slotsActive), fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_tester_reports), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${overview.testerReportsCount}", fontWeight = FontWeight.SemiBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_contact_reports), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${overview.contactReportsCount}", fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_missed_streak), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${overview.dailyMissedStreak}", fontWeight = FontWeight.SemiBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_stat_last_login), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(overview.lastLoginDate ?: "-", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Slot Details Grid
        if (overview.slots.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.admin_stat_slots), fontWeight = FontWeight.Bold)
                    overview.slots.forEach { slot ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.admin_slot_number_format, slot.slotNumber),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                slot.currentCampaign?.let { camp ->
                                    Text(
                                        text = "${camp.appName} (${camp.packageName})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } ?: Text(
                                    text = stringResource(R.string.admin_slot_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (slot.isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (slot.isActive) stringResource(R.string.admin_slot_active)
                                    else stringResource(R.string.admin_slot_expired),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Actions
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onChangeRole,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(stringResource(R.string.admin_action_change_role))
            }
            OutlinedButton(
                onClick = onCompensate,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(stringResource(R.string.admin_action_compensate))
            }
        }
    }
}

@Composable
private fun CreditsTab(
    credits: List<AdminCreditHistoryItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (credits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator()
            else Text(stringResource(R.string.admin_credit_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(credits, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.amount > 0) "+${item.amount}" else "${item.amount}",
                            fontWeight = FontWeight.Bold,
                            color = if (item.amount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.admin_credit_balance_after, item.balance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.action?.let {
                        Text(text = it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                    item.note?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = item.createdAt.take(19).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.admin_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyTasksTab(
    dailyTasks: List<AdminMemberDailyTaskItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (dailyTasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator()
            else Text(stringResource(R.string.admin_daily_tasks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(dailyTasks, key = { it.taskDate }) { item ->
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
                        Text(text = item.taskDate, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (item.isFinalized) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.admin_daily_task_finalized),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            if (item.incompleteHistory) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.admin_daily_task_incomplete),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    item.status?.let { status ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = stringResource(R.string.admin_daily_task_completed, status.completedTests),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.admin_daily_task_required, status.effectiveRequiredTests),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (item.penaltyCredits > 0) {
                        Text(
                            text = stringResource(R.string.admin_daily_task_penalty, item.penaltyCredits),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        if (hasMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else OutlinedButton(onClick = onLoadMore) { Text(stringResource(R.string.admin_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun WarningsTab(
    warnings: List<AdminMemberWarningItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onRevokeWarning: (String) -> Unit,
) {
    if (warnings.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator()
            else Text(stringResource(R.string.admin_warning_list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(warnings, key = { it.id }) { item ->
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
                            text = item.warningDate ?: item.createdAt.take(10),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (item.isRevoked) MaterialTheme.colorScheme.outlineVariant
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (item.isRevoked) stringResource(R.string.admin_warning_revoked_badge)
                                else stringResource(R.string.admin_warning_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (item.isRevoked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Text(text = item.message, style = MaterialTheme.typography.bodyMedium)

                    if (item.isRevoked && item.revocation != null) {
                        Text(
                            text = stringResource(R.string.admin_revoke_reason_format, item.revocation.reason.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!item.isRevoked) {
                        OutlinedButton(
                            onClick = { onRevokeWarning(item.id) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.admin_revoke_warning_btn))
                        }
                    }
                }
            }
        }

        if (hasMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else OutlinedButton(onClick = onLoadMore) { Text(stringResource(R.string.admin_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun RoleChangeDialog(
    currentRole: String,
    canManageAdmins: Boolean,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (newRole: String, reason: String) -> Unit,
) {
    val roles = listOf("member", "moderator", "admin")
    var selectedRole by rememberSaveable(currentRole, currentAdminId, sessionGeneration) { mutableStateOf(currentRole) }
    var reason by rememberSaveable(currentRole, currentAdminId, sessionGeneration) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_role_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.admin_role_current, currentRole), fontWeight = FontWeight.SemiBold)

                roles.forEach { role ->
                    val isRoleAdmin = role == "admin"
                    val isAppointingAdmin = isRoleAdmin && currentRole != "admin"
                    val isDemotingAdmin = currentRole == "admin" && !isRoleAdmin
                    val requiresSpecialCap = isAppointingAdmin || isDemotingAdmin
                    val isAllowed = !requiresSpecialCap || canManageAdmins

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isAllowed) { selectedRole = role },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedRole == role,
                            onClick = { selectedRole = role },
                            enabled = isAllowed,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(role.uppercase(), fontWeight = FontWeight.Medium)
                            if (requiresSpecialCap && !canManageAdmins) {
                                Text(
                                    stringResource(R.string.admin_role_insufficient_privilege),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.admin_role_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedRole, reason) },
                enabled = !isLoading && selectedRole != currentRole && reason.isNotBlank(),
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
private fun CompensateCreditsDialog(
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, reason: String, origLog: String?, contactId: String?) -> Unit,
) {
    var isAdd by rememberSaveable(currentAdminId, sessionGeneration) { mutableStateOf(true) }
    var amountText by rememberSaveable(currentAdminId, sessionGeneration) { mutableStateOf("10") }
    var reason by rememberSaveable(currentAdminId, sessionGeneration) { mutableStateOf("") }
    var origLogId by rememberSaveable(currentAdminId, sessionGeneration) { mutableStateOf("") }
    var contactId by rememberSaveable(currentAdminId, sessionGeneration) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_compensate_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isAdd,
                        onClick = { isAdd = true },
                        label = { Text(stringResource(R.string.admin_type_grant)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = !isAdd,
                        onClick = { isAdd = false },
                        label = { Text(stringResource(R.string.admin_type_deduct)) },
                        leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { if (it.all { c -> c.isDigit() }) amountText = it },
                    label = { Text(stringResource(R.string.admin_compensate_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.admin_compensate_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = origLogId,
                    onValueChange = { origLogId = it },
                    label = { Text(stringResource(R.string.admin_compensate_original_log)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = contactId,
                    onValueChange = { contactId = it },
                    label = { Text(stringResource(R.string.admin_compensate_contact_report)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val amt = (amountText.toIntOrNull() ?: 0) * (if (isAdd) 1 else -1)
            Button(
                onClick = {
                    onConfirm(amt, reason, origLogId.ifBlank { null }, contactId.ifBlank { null })
                },
                enabled = !isLoading && amt != 0 && reason.isNotBlank(),
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
private fun RevokeWarningDialog(
    warningId: String,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by rememberSaveable(warningId, currentAdminId, sessionGeneration) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_revoke_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.admin_revoke_warning_confirm))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.admin_revoke_warning_reason)) },
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
