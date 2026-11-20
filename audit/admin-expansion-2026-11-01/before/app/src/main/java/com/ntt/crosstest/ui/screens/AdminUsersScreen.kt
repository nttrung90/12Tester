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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.ui.components.AdminDailyTaskPolicySheet
import com.ntt.crosstest.ui.viewmodels.AdminFilter
import com.ntt.crosstest.ui.viewmodels.AdminUsersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: AdminUsersViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AdminUsersViewModel(app.campaignRepository, app.authRepository, app.appConfigRepository, Localizer(app))
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var targetUserLockId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetUserCreditId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetUserNotifId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBroadcastDialog by rememberSaveable { mutableStateOf(false) }
    var showUpdateConfigDialog by rememberSaveable { mutableStateOf(false) }
    var showHomeStatsCycleDialog by rememberSaveable { mutableStateOf(false) }
    var showDailyTaskPolicySheet by rememberSaveable { mutableStateOf(false) }
    var isMembersExpanded by rememberSaveable { mutableStateOf(true) }
    val memberListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val userToToggleLock = remember(targetUserLockId, uiState.allUsers) {
        targetUserLockId?.let { id -> uiState.allUsers.find { it.id == id } }
    }
    val userToAdjustCredits = remember(targetUserCreditId, uiState.allUsers) {
        targetUserCreditId?.let { id -> uiState.allUsers.find { it.id == id } }
    }
    val userToSendNotification = remember(targetUserNotifId, uiState.allUsers) {
        targetUserNotifId?.let { id -> uiState.allUsers.find { it.id == id } }
    }

    LaunchedEffect(uiState.isLoading, uiState.allUsers) {
        if (!uiState.isLoading && uiState.allUsers.isNotEmpty()) {
            if (targetUserLockId != null && userToToggleLock == null) targetUserLockId = null
            if (targetUserCreditId != null && userToAdjustCredits == null) targetUserCreditId = null
            if (targetUserNotifId != null && userToSendNotification == null) targetUserNotifId = null
        }
    }

    var savedSessionUid by rememberSaveable { mutableStateOf(uiState.currentAdminId) }
    var savedSessionGen by rememberSaveable { mutableStateOf(uiState.sessionGeneration) }

    var lastConsumedHomeStatsSignal by rememberSaveable { mutableIntStateOf(uiState.homeStatsSaveSignal) }
    var lastConsumedConfigSignal by rememberSaveable { mutableIntStateOf(uiState.configSaveSignal) }

    LaunchedEffect(uiState.currentAdminId, uiState.sessionGeneration) {
        if (uiState.currentAdminId == null) {
            targetUserLockId = null
            targetUserCreditId = null
            targetUserNotifId = null
            showBroadcastDialog = false
            showUpdateConfigDialog = false
            showHomeStatsCycleDialog = false
            showDailyTaskPolicySheet = false
            savedSessionUid = null
            savedSessionGen = 0L
        } else if (savedSessionUid != uiState.currentAdminId || savedSessionGen != uiState.sessionGeneration) {
            targetUserLockId = null
            targetUserCreditId = null
            targetUserNotifId = null
            showBroadcastDialog = false
            showUpdateConfigDialog = false
            showHomeStatsCycleDialog = false
            showDailyTaskPolicySheet = false
            savedSessionUid = uiState.currentAdminId
            savedSessionGen = uiState.sessionGeneration
            lastConsumedHomeStatsSignal = uiState.homeStatsSaveSignal
            lastConsumedConfigSignal = uiState.configSaveSignal
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadDailyTaskConfig()
    }

    LaunchedEffect(showDailyTaskPolicySheet) {
        if (showDailyTaskPolicySheet) viewModel.loadDailyTaskConfig()
    }

    LaunchedEffect(showHomeStatsCycleDialog) {
        if (showHomeStatsCycleDialog) viewModel.loadHomeStatsConfig()
    }

    LaunchedEffect(uiState.homeStatsSaveSignal) {
        if (uiState.homeStatsSaveSignal > lastConsumedHomeStatsSignal) {
            lastConsumedHomeStatsSignal = uiState.homeStatsSaveSignal
            showHomeStatsCycleDialog = false
        }
    }

    LaunchedEffect(showUpdateConfigDialog) {
        if (showUpdateConfigDialog) viewModel.loadAppConfig()
    }

    // Close the config dialog once the save succeeded (signal from ViewModel).
    LaunchedEffect(uiState.configSaveSignal) {
        if (uiState.configSaveSignal > lastConsumedConfigSignal) {
            lastConsumedConfigSignal = uiState.configSaveSignal
            showUpdateConfigDialog = false
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    // Dialog: Lock / Unlock User
    userToToggleLock?.let { target ->
        val willLock = !target.isLocked
        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) targetUserLockId = null },
            title = {
                Text(
                    stringResource(
                        if (willLock) R.string.admin_lock_confirm_title
                        else R.string.admin_unlock_confirm_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (willLock) R.string.admin_lock_confirm_msg
                        else R.string.admin_unlock_confirm_msg,
                        target.displayName ?: target.email ?: target.id
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setUserLock(target.id, willLock)
                        targetUserLockId = null
                    },
                    colors = if (willLock) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(),
                    enabled = !uiState.isActionLoading,
                ) {
                    if (uiState.isActionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (willLock) R.string.admin_lock_user else R.string.admin_unlock_user))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { targetUserLockId = null },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Dialog: Grant / Deduct Credits
    userToAdjustCredits?.let { target ->
        var isGrant by rememberSaveable(target.id, uiState.currentAdminId, uiState.sessionGeneration) { mutableStateOf(true) }
        var amountText by rememberSaveable(target.id, uiState.currentAdminId, uiState.sessionGeneration) { mutableStateOf("50") }
        var reasonText by rememberSaveable(target.id, uiState.currentAdminId, uiState.sessionGeneration) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) targetUserCreditId = null },
            title = {
                Column {
                    Text(stringResource(R.string.admin_adjust_credits_dialog_title))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = target.displayName ?: target.email ?: target.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.admin_current_balance_format, target.credits),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    // Type toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = isGrant,
                            onClick = { isGrant = true },
                            label = { Text(stringResource(R.string.admin_type_grant)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = !isGrant,
                            onClick = { isGrant = false },
                            label = { Text(stringResource(R.string.admin_type_deduct)) },
                            leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() }) amountText = input
                        },
                        label = { Text(stringResource(R.string.admin_credit_amount)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        label = { Text(stringResource(R.string.admin_reason_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amountVal = amountText.toIntOrNull() ?: 0
                        if (amountVal > 0) {
                            val finalAmount = if (isGrant) amountVal else -amountVal
                            viewModel.adjustCredits(target.id, finalAmount, reasonText)
                            targetUserCreditId = null
                        }
                    },
                    enabled = !uiState.isActionLoading && (amountText.toIntOrNull() ?: 0) > 0,
                ) {
                    if (uiState.isActionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { targetUserCreditId = null },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Dialog: Broadcast notification to ALL users
    if (showBroadcastDialog) {
        AdminSendNotificationDialog(
            targetUser = null,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { showBroadcastDialog = false },
            onSend = { title, message, type ->
                viewModel.sendNotification(null, title, message, type)
                showBroadcastDialog = false
            }
        )
    }

    // Sheet: Daily task policy configuration (migration 058)
    if (showDailyTaskPolicySheet) {
        AdminDailyTaskPolicySheet(
            config = uiState.dailyTaskConfig,
            isLoading = uiState.isDailyTaskLoading,
            isSaving = uiState.isDailyTaskSaving,
            isCancelling = uiState.isDailyTaskCancelling,
            draftRatio = uiState.dailyTaskDraftRatio,
            draftEffectiveFrom = uiState.dailyTaskDraftEffectiveFrom,
            draftNote = uiState.dailyTaskDraftNote,
            futurePolicies = uiState.dailyTaskFuturePolicies,
            isFutureLoading = uiState.isDailyTaskFutureLoading,
            futureHasMore = uiState.dailyTaskFutureHasMore,
            history = uiState.dailyTaskHistory,
            isHistoryLoading = uiState.isDailyTaskHistoryLoading,
            historyHasMore = uiState.dailyTaskHistoryHasMore,
            errorMessage = uiState.dailyTaskError,
            actionMessage = uiState.dailyTaskActionMessage,
            saveSignal = uiState.dailyTaskSaveSignal,
            onDismiss = { showDailyTaskPolicySheet = false },
            onDraftChange = { ratio, effectiveFrom, note ->
                viewModel.setDailyTaskDraft(ratio, effectiveFrom, note)
            },
            onSaveSchedule = { ratio, effectiveFrom, note ->
                viewModel.saveDailyTaskSchedule(ratio, effectiveFrom, note)
            },
            onCancelSchedule = { effectiveFrom, note ->
                viewModel.cancelDailyTaskSchedule(effectiveFrom, note)
            },
            onLoadMoreFuture = {
                viewModel.loadMoreDailyTaskPolicies()
            },
            onLoadHistory = { reset ->
                viewModel.loadDailyTaskHistory(reset)
            },
            onRetryLoad = {
                viewModel.loadDailyTaskConfig()
            },
            onClearMessages = {
                viewModel.clearDailyTaskMessages()
            },
        )
    }

    // Dialog: Home stats aggregation cycle configuration (migration 047)
    if (showHomeStatsCycleDialog) {
        AdminHomeStatsCycleDialog(
            config = uiState.homeStatsConfig,
            isLoading = uiState.isHomeStatsLoading,
            isSaving = uiState.isHomeStatsSaving,
            onDismiss = { showHomeStatsCycleDialog = false },
            onSave = { cycleDays ->
                viewModel.saveHomeStatsCycle(cycleDays)
            },
        )
    }

    // Dialog: App update configuration (version gate for all clients)
    if (showUpdateConfigDialog) {
        AdminUpdateConfigDialog(
            config = uiState.appConfig,
            isLoading = uiState.isConfigLoading,
            isSaving = uiState.isActionLoading,
            onDismiss = { showUpdateConfigDialog = false },
            onSave = { min, latest, name, force, title, message, storeUrl, titleVi, titleEn, messageVi, messageEn ->
                viewModel.saveAppConfig(min, latest, name, force, title, message, storeUrl, titleVi, titleEn, messageVi, messageEn)
            },
        )
    }

    // Dialog: Direct notification to 1 user
    userToSendNotification?.let { target ->
        AdminSendNotificationDialog(
            targetUser = target,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isActionLoading,
            onDismiss = { targetUserNotifId = null },
            onSend = { title, message, type ->
                viewModel.sendNotification(target.id, title, message, type)
                targetUserNotifId = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_panel_title), fontWeight = FontWeight.Bold) },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "member_management") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                                .clickable { isMembersExpanded = !isMembersExpanded }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.admin_member_management_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.admin_total_users_count, uiState.allUsers.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { isMembersExpanded = !isMembersExpanded },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (isMembersExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = stringResource(if (isMembersExpanded) R.string.admin_collapse else R.string.admin_expand),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        if (isMembersExpanded) {
                            // Search and Filters
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = uiState.query,
                                    onValueChange = viewModel::setQuery,
                                    placeholder = { Text(stringResource(R.string.admin_search_users_hint)) },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = uiState.filter == AdminFilter.ALL,
                                        onClick = { viewModel.setFilter(AdminFilter.ALL) },
                                        label = { Text("${stringResource(R.string.admin_filter_all)} (${uiState.allUsers.size})") },
                                    )
                                    FilterChip(
                                        selected = uiState.filter == AdminFilter.ACTIVE,
                                        onClick = { viewModel.setFilter(AdminFilter.ACTIVE) },
                                        label = { Text(stringResource(R.string.admin_filter_active)) },
                                    )
                                    FilterChip(
                                        selected = uiState.filter == AdminFilter.LOCKED,
                                        onClick = { viewModel.setFilter(AdminFilter.LOCKED) },
                                        label = { Text(stringResource(R.string.admin_filter_locked)) },
                                    )
                                    FilterChip(
                                        selected = uiState.filter == AdminFilter.ADMIN,
                                        onClick = { viewModel.setFilter(AdminFilter.ADMIN) },
                                        label = { Text(stringResource(R.string.admin_filter_admin)) },
                                    )
                                }
                            }

                            if (uiState.filteredUsers.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp)
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.isLoading) {
                                        CircularProgressIndicator()
                                    } else {
                                        Text(
                                            text = stringResource(R.string.admin_empty_users),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                // Bound the nested list so all five dashboard cards stay reachable.
                                LazyColumn(
                                    state = memberListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 360.dp),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(uiState.filteredUsers, key = { it.id }) { user ->
                                        AdminUserCard(
                                            user = user,
                                            isCurrentAdmin = user.id == uiState.currentAdminId,
                                            onAdjustCredits = { targetUserCreditId = user.id },
                                            onToggleLock = { targetUserLockId = user.id },
                                            onSendNotification = { targetUserNotifId = user.id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "daily_tasks") {
                    val activeRatio = uiState.dailyTaskConfig?.activePolicy?.testsPerActiveApp
                    AdminFeatureCard(
                        title = stringResource(R.string.admin_daily_task_title),
                        subtitle = if (activeRatio != null) {
                            stringResource(R.string.admin_daily_task_active_format, activeRatio)
                        } else {
                            stringResource(R.string.admin_daily_task_subtitle)
                        },
                        icon = Icons.Filled.Settings,
                        onClick = { showDailyTaskPolicySheet = true },
                    )
                }

                item(key = "home_stats_cycle") {
                    AdminFeatureCard(
                        title = stringResource(R.string.admin_home_stats_cycle_title),
                        subtitle = stringResource(R.string.admin_home_stats_cycle_desc),
                        icon = Icons.Filled.BarChart,
                        onClick = { showHomeStatsCycleDialog = true },
                    )
                }

                item(key = "app_update_config") {
                    AdminFeatureCard(
                        title = stringResource(R.string.admin_update_config_title),
                        subtitle = stringResource(R.string.admin_update_config_subtitle),
                        icon = Icons.Filled.SystemUpdate,
                        onClick = { showUpdateConfigDialog = true },
                    )
                }

                item(key = "broadcast_notification") {
                    AdminFeatureCard(
                        title = stringResource(R.string.admin_send_broadcast_cd),
                        subtitle = stringResource(R.string.admin_broadcast_subtitle),
                        icon = Icons.Filled.Campaign,
                        onClick = { showBroadcastDialog = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminFeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AdminUserCard(
    user: Profile,
    isCurrentAdmin: Boolean,
    onAdjustCredits: () -> Unit,
    onToggleLock: () -> Unit,
    onSendNotification: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (user.isLocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header Row: Avatar, Name, Email
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val initial = (user.displayName?.firstOrNull() ?: user.email?.firstOrNull() ?: '?').uppercaseChar()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (user.isAdmin) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initial.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (user.isAdmin) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName ?: stringResource(R.string.admin_user_fallback),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (user.isAdmin) {
                            Spacer(Modifier.width(6.dp))
                            Text("👑", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    user.email?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Balance Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🪙 ${user.credits}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Status badges row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Role badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (user.isAdmin) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (user.isAdmin) stringResource(R.string.admin_badge) else stringResource(R.string.member_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (user.isAdmin) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Lock status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (user.isLocked) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (user.isLocked) stringResource(R.string.status_locked) else stringResource(R.string.status_active),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (user.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    )
                }

                user.createdAt?.take(10)?.let { date ->
                    Text(
                        text = stringResource(R.string.admin_joined_format, date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Actions
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onAdjustCredits,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Text(
                        text = stringResource(R.string.admin_adjust_credits_btn),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                IconButton(
                    onClick = onSendNotification,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Campaign,
                        contentDescription = stringResource(R.string.admin_send_private_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                if (!isCurrentAdmin) {
                    Button(
                        onClick = onToggleLock,
                        colors = if (user.isLocked) ButtonDefaults.buttonColors()
                        else ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Icon(
                            imageVector = if (user.isLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(if (user.isLocked) R.string.admin_unlock_user else R.string.admin_lock_user),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSendNotificationDialog(
    targetUser: Profile?,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSend: (title: String, message: String, type: String) -> Unit,
) {
    var title by rememberSaveable(targetUser?.id, currentAdminId, sessionGeneration) { mutableStateOf("") }
    var message by rememberSaveable(targetUser?.id, currentAdminId, sessionGeneration) { mutableStateOf("") }
    var selectedType by rememberSaveable(targetUser?.id, currentAdminId, sessionGeneration) { mutableStateOf("admin") }

    val types = listOf(
        "admin" to stringResource(R.string.admin_type_notification),
        "system" to stringResource(R.string.admin_type_system),
        "warning" to stringResource(R.string.admin_type_warning),
        "credit" to stringResource(R.string.admin_type_gift),
    )

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Column {
                Text(
                    text = if (targetUser == null) stringResource(R.string.admin_broadcast_title)
                    else stringResource(R.string.admin_private_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (targetUser != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.admin_notif_to_format, targetUser.displayName ?: targetUser.email ?: targetUser.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Type selection chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    types.forEach { (typeKey, typeLabel) ->
                        FilterChip(
                            selected = selectedType == typeKey,
                            onClick = { selectedType = typeKey },
                            label = { Text(typeLabel, fontSize = 12.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.admin_notif_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.admin_notif_message_label)) },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && message.isNotBlank()) {
                        onSend(title.trim(), message.trim(), selectedType)
                    }
                },
                enabled = !isLoading && title.isNotBlank() && message.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.admin_send_now))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AdminUpdateConfigDialog(
    config: AppConfig?,
    isLoading: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String, Boolean, String?, String?, String?, String?, String?, String?, String?) -> Unit,
) {
    var initialized by rememberSaveable { mutableStateOf(false) }
    var minVersionCode by rememberSaveable { mutableStateOf("") }
    var latestVersionCode by rememberSaveable { mutableStateOf("") }
    var latestVersionName by rememberSaveable { mutableStateOf("") }
    var forceUpdate by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var titleVi by rememberSaveable { mutableStateOf("") }
    var titleEn by rememberSaveable { mutableStateOf("") }
    var messageVi by rememberSaveable { mutableStateOf("") }
    var messageEn by rememberSaveable { mutableStateOf("") }
    var storeUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(config) {
        if (config != null && !initialized) {
            minVersionCode = config.minVersionCode.toString()
            latestVersionCode = config.latestVersionCode.toString()
            latestVersionName = config.latestVersionName
            forceUpdate = config.forceUpdate
            title = config.title.orEmpty()
            message = config.message.orEmpty()
            titleVi = config.titleVi ?: config.title.orEmpty()
            titleEn = config.titleEn.orEmpty()
            messageVi = config.messageVi ?: config.message.orEmpty()
            messageEn = config.messageEn.orEmpty()
            storeUrl = config.storeUrl.orEmpty()
            initialized = true
        }
    }

    val minVal = minVersionCode.toIntOrNull()
    val latestVal = latestVersionCode.toIntOrNull()
    val isValid = minVal != null && minVal >= 1 &&
        latestVal != null && latestVal >= minVal &&
        latestVersionName.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.admin_update_config_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (isLoading && config == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.admin_update_config_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = minVersionCode,
                        onValueChange = { minVersionCode = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.admin_config_min_version_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = latestVersionCode,
                        onValueChange = { latestVersionCode = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.admin_config_latest_version_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = latestVersionName,
                        onValueChange = { latestVersionName = it },
                        label = { Text(stringResource(R.string.admin_config_latest_version_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.admin_config_force_update),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = forceUpdate,
                            onCheckedChange = { forceUpdate = it },
                        )
                    }
                    OutlinedTextField(
                        value = titleVi,
                        onValueChange = { titleVi = it },
                        label = { Text(stringResource(R.string.admin_update_title_vi_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = titleEn,
                        onValueChange = { titleEn = it },
                        label = { Text(stringResource(R.string.admin_update_title_en_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = messageVi,
                        onValueChange = { messageVi = it },
                        label = { Text(stringResource(R.string.admin_update_msg_vi_label)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = messageEn,
                        onValueChange = { messageEn = it },
                        label = { Text(stringResource(R.string.admin_update_msg_en_label)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = storeUrl,
                        onValueChange = { storeUrl = it },
                        label = { Text(stringResource(R.string.admin_config_store_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val vTitleVi = titleVi.trim().ifBlank { null }
                    val vTitleEn = titleEn.trim().ifBlank { null }
                    val vMsgVi = messageVi.trim().ifBlank { null }
                    val vMsgEn = messageEn.trim().ifBlank { null }
                    onSave(
                        minVal ?: return@Button,
                        latestVal ?: return@Button,
                        latestVersionName.trim(),
                        forceUpdate,
                        vTitleVi ?: vTitleEn,
                        vMsgVi ?: vMsgEn,
                        storeUrl.trim().ifBlank { null },
                        vTitleVi,
                        vTitleEn,
                        vMsgVi,
                        vMsgEn,
                    )
                },
                enabled = !isSaving && !isLoading && isValid,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.admin_config_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AdminHomeStatsCycleDialog(
    config: com.ntt.crosstest.data.HomeStatsPolicyConfig?,
    isLoading: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var selectedCycle by rememberSaveable { mutableStateOf(config?.activeCycleDays ?: 1) }

    LaunchedEffect(config) {
        if (config != null) {
            selectedCycle = config.pendingCycleDays ?: config.activeCycleDays
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.admin_home_stats_cycle_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (isLoading && config == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.admin_home_stats_cycle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    config?.let { cfg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.admin_home_stats_current_active,
                                        cfg.activeCycleDays,
                                        cfg.activeEffectiveFrom ?: cfg.today
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (cfg.pendingCycleDays != null && cfg.pendingEffectiveFrom != null) {
                                    Text(
                                        text = stringResource(
                                            R.string.admin_home_stats_pending_schedule,
                                            cfg.pendingCycleDays,
                                            cfg.pendingEffectiveFrom
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.admin_home_stats_no_pending),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.admin_home_stats_cycle_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    val cycles = listOf(
                        1 to stringResource(R.string.admin_home_stats_cycle_1_day),
                        2 to stringResource(R.string.admin_home_stats_cycle_2_days),
                        3 to stringResource(R.string.admin_home_stats_cycle_3_days),
                    )

                    cycles.forEach { (days, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedCycle == days,
                                    onClick = { if (!isSaving) selectedCycle = days },
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCycle == days,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text(
                        text = stringResource(R.string.admin_home_stats_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedCycle) },
                enabled = !isSaving && !isLoading && config != null,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
