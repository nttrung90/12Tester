package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
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

    var userToToggleLock by rememberSaveable { mutableStateOf<Profile?>(null) }
    var userToAdjustCredits by rememberSaveable { mutableStateOf<Profile?>(null) }
    var userToSendNotification by rememberSaveable { mutableStateOf<Profile?>(null) }
    var showBroadcastDialog by rememberSaveable { mutableStateOf(false) }
    var showUpdateConfigDialog by rememberSaveable { mutableStateOf(false) }
    var showHomeStatsCycleDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showHomeStatsCycleDialog) {
        if (showHomeStatsCycleDialog) viewModel.loadHomeStatsConfig()
    }

    LaunchedEffect(uiState.homeStatsSaveSignal) {
        if (uiState.homeStatsSaveSignal > 0) showHomeStatsCycleDialog = false
    }

    LaunchedEffect(showUpdateConfigDialog) {
        if (showUpdateConfigDialog) viewModel.loadAppConfig()
    }

    // Close the config dialog once the save succeeded (signal from ViewModel).
    LaunchedEffect(uiState.configSaveSignal) {
        if (uiState.configSaveSignal > 0) showUpdateConfigDialog = false
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
            onDismissRequest = { if (!uiState.isActionLoading) userToToggleLock = null },
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
                        userToToggleLock = null
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
                    onClick = { userToToggleLock = null },
                    enabled = !uiState.isActionLoading,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Dialog: Grant / Deduct Credits
    userToAdjustCredits?.let { target ->
        var isGrant by rememberSaveable { mutableStateOf(true) }
        var amountText by rememberSaveable { mutableStateOf("50") }
        var reasonText by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!uiState.isActionLoading) userToAdjustCredits = null },
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
                            userToAdjustCredits = null
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
                    onClick = { userToAdjustCredits = null },
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
            isLoading = uiState.isActionLoading,
            onDismiss = { showBroadcastDialog = false },
            onSend = { title, message, type ->
                viewModel.sendNotification(null, title, message, type)
                showBroadcastDialog = false
            }
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
            onSave = { min, latest, name, force, title, message, storeUrl ->
                viewModel.saveAppConfig(min, latest, name, force, title, message, storeUrl)
            },
        )
    }

    // Dialog: Direct notification to 1 user
    userToSendNotification?.let { target ->
        AdminSendNotificationDialog(
            targetUser = target,
            isLoading = uiState.isActionLoading,
            onDismiss = { userToSendNotification = null },
            onSend = { title, message, type ->
                viewModel.sendNotification(target.id, title, message, type)
                userToSendNotification = null
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
                    IconButton(onClick = { showHomeStatsCycleDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.admin_home_stats_cycle_title),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showUpdateConfigDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = stringResource(R.string.admin_update_config_title),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showBroadcastDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Campaign,
                            contentDescription = stringResource(R.string.admin_send_broadcast_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search and Filters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (uiState.filteredUsers.isEmpty() && !uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.admin_empty_users),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.filteredUsers, key = { it.id }) { user ->
                            AdminUserCard(
                                user = user,
                                isCurrentAdmin = user.id == uiState.currentAdminId,
                                onAdjustCredits = { userToAdjustCredits = user },
                                onToggleLock = { userToToggleLock = user },
                                onSendNotification = { userToSendNotification = user },
                            )
                        }
                    }
                }
            }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                    )
                }
            }

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onAdjustCredits,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.admin_adjust_credits_btn),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                IconButton(
                    onClick = onSendNotification,
                    modifier = Modifier.size(36.dp),
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
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
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSend: (title: String, message: String, type: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf("admin") }

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
    onSave: (Int, Int, String, Boolean, String?, String?, String?) -> Unit,
) {
    var initialized by rememberSaveable { mutableStateOf(false) }
    var minVersionCode by rememberSaveable { mutableStateOf("") }
    var latestVersionCode by rememberSaveable { mutableStateOf("") }
    var latestVersionName by rememberSaveable { mutableStateOf("") }
    var forceUpdate by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var storeUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(config) {
        if (config != null && !initialized) {
            minVersionCode = config.minVersionCode.toString()
            latestVersionCode = config.latestVersionCode.toString()
            latestVersionName = config.latestVersionName
            forceUpdate = config.forceUpdate
            title = config.title.orEmpty()
            message = config.message.orEmpty()
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
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.admin_config_title_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(stringResource(R.string.admin_config_message_label)) },
                        minLines = 3,
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
                    onSave(
                        minVal ?: return@Button,
                        latestVal ?: return@Button,
                        latestVersionName.trim(),
                        forceUpdate,
                        title.trim().ifBlank { null },
                        message.trim().ifBlank { null },
                        storeUrl.trim().ifBlank { null },
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
